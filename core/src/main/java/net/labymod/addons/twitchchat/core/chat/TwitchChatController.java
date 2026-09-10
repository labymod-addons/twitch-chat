package net.labymod.addons.twitchchat.core.chat;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.labymod.addons.twitchchat.core.TwitchChatAddon;
import net.labymod.addons.twitchchat.core.auth.TwitchAuth;
import net.labymod.addons.twitchchat.core.auth.TwitchAuth.AuthException;
import net.labymod.addons.twitchchat.core.auth.TwitchAuth.DeviceCode;
import net.labymod.addons.twitchchat.core.auth.TwitchAuth.TokenSet;
import net.labymod.addons.twitchchat.core.auth.TwitchAuth.Validation;
import net.labymod.addons.twitchchat.core.configuration.TwitchChatConfiguration;
import net.labymod.addons.twitchchat.core.emote.ThirdPartyEmoteService;
import net.labymod.addons.twitchchat.core.emote.TwitchBadgeService;
import net.labymod.addons.twitchchat.core.irc.IrcLine;
import net.labymod.addons.twitchchat.core.irc.TwitchIrcClient;
import net.labymod.addons.twitchchat.core.irc.TwitchIrcClient.Credentials;
import net.labymod.addons.twitchchat.core.model.TwitchBadge;
import net.labymod.addons.twitchchat.core.model.TwitchChatMessage;
import net.labymod.addons.twitchchat.core.model.TwitchChatMessage.Kind;
import net.labymod.addons.twitchchat.core.model.TwitchEmoteRange;
import net.labymod.addons.twitchchat.core.util.TwitchColors;
import net.labymod.api.Laby;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.TextComponent;
import net.labymod.api.client.component.event.ClickEvent;
import net.labymod.api.client.component.event.HoverEvent;
import net.labymod.api.client.component.format.NamedTextColor;
import net.labymod.api.client.component.format.TextDecoration;
import net.labymod.api.labynet.models.service.ServiceDataType;
import net.labymod.api.labynet.models.service.ServiceStatus;
import net.labymod.api.labynet.models.service.TwitchServiceData;
import net.labymod.api.util.ThreadSafe;
import net.labymod.api.util.io.LabyExecutors;
import net.labymod.api.util.logging.Logging;

/**
 * Central state of the addon: the IRC connection, the Twitch session, badge/emote lookups and
 * the message history the chat tab and the HUD widget render from. Everything that touches
 * LabyMod UI runs on the render thread.
 */
public final class TwitchChatController implements TwitchIrcClient.Listener {

  /**
   * Client id of the public Twitch application used for the device code login. Can be
   * overridden in the advanced settings. Reading chat works without it.
   */
  public static final String DEFAULT_CLIENT_ID = "icwecjnctrvn2q1zeu6r33iwnea3dw";

  public static final int HISTORY_SIZE = 60;

  private static final String ACTION_PREFIX = "ACTION ";
  private static final String ACTION_SUFFIX = "";

  public enum State {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
  }

  public record HistoryEntry(TwitchChatMessage message, Component component, boolean highlight) {

  }

  private final TwitchChatAddon addon;
  private final TwitchChatConfiguration config;
  private final Logging logger;
  private final ExecutorService executor;
  private final ScheduledExecutorService scheduler;
  private final TwitchIrcClient irc;
  private final TwitchChatTab tab;
  private final TwitchBadgeService badges;
  private final ThirdPartyEmoteService thirdParty;
  private final TwitchComponentBuilder components;
  private final Deque<HistoryEntry> history = new ArrayDeque<>();

  private volatile State state = State.DISCONNECTED;
  private volatile String roomId;
  private volatile String joinedChannel;
  private volatile boolean authenticated;
  private volatile String ownDisplayName;
  private volatile int ownColor = -1;
  private volatile List<TwitchBadge> ownBadges = Collections.emptyList();
  private volatile boolean loginInProgress;
  private volatile boolean announcedAnonymous;

  public TwitchChatController(TwitchChatAddon addon) {
    this.addon = addon;
    this.config = addon.configuration();
    this.logger = addon.logger();
    this.executor = LabyExecutors.newSingleThreadExecutor("TwitchChat-Worker-%d");
    this.scheduler = LabyExecutors.newSingleThreadScheduledExecutor("TwitchChat-Scheduler-%d");
    this.irc = new TwitchIrcClient(this.logger, this);
    this.tab = new TwitchChatTab();
    this.badges = new TwitchBadgeService(this.logger, this.executor);
    this.thirdParty = new ThirdPartyEmoteService(this.logger, this.executor);
    this.components = new TwitchComponentBuilder(this.config, this.badges, this.thirdParty);
  }

  // ---------------------------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------------------------

  public void initialize() {
    if (!this.isEnabled()) {
      return;
    }
    if (this.config.thirdPartyEmotes().get()) {
      this.thirdParty.loadGlobal();
    }
    this.validateStoredSession(() -> {
      if (this.config.autoConnect().get()) {
        this.connect();
      }
    });
  }

  public void shutdown() {
    this.irc.shutdown();
    this.scheduler.shutdownNow();
    this.executor.shutdownNow();
  }

  public boolean isEnabled() {
    return this.config.enabled().get();
  }

  public State state() {
    return this.state;
  }

  public boolean isConnected() {
    return this.state == State.CONNECTED;
  }

  public boolean isAuthenticated() {
    return this.authenticated;
  }

  public String channel() {
    return this.joinedChannel != null ? this.joinedChannel : this.irc.channel();
  }

  public TwitchChatTab tab() {
    return this.tab;
  }

  public TwitchChatConfiguration config() {
    return this.config;
  }

  public List<HistoryEntry> history() {
    synchronized (this.history) {
      return new ArrayList<>(this.history);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Connection
  // ---------------------------------------------------------------------------------------------

  public void toggleConnection() {
    if (this.state == State.DISCONNECTED) {
      this.connect();
    } else {
      this.disconnect();
    }
  }

  public void connect() {
    if (!this.isEnabled()) {
      return;
    }
    String channel = TwitchIrcClient.normalize(this.config.channel().get());
    if (channel != null) {
      this.connect(channel);
      return;
    }

    // No channel configured: fall back to the Twitch account linked on laby.net.
    this.system("twitchchat.messages.resolvingChannel");
    Laby.labyAPI().labyNetController().loadServiceData(ServiceDataType.TWITCH, result -> {
      String linked = null;
      if (result.isPresent() && result.get() instanceof TwitchServiceData data
          && data.getStatus() == ServiceStatus.Status.OK) {
        linked = TwitchIrcClient.normalize(data.getUserName());
      }
      String resolved = linked;
      ThreadSafe.executeOnRenderThread(() -> {
        if (resolved == null) {
          this.system("twitchchat.messages.noChannel");
          return;
        }
        this.config.channel().set(resolved);
        this.save();
        this.connect(resolved);
      });
    });
  }

  private void connect(String channel) {
    this.state = State.CONNECTING;
    this.roomId = null;
    this.joinedChannel = null;
    this.announcedAnonymous = false;
    if (this.config.showInChatTab().get()) {
      this.tab.ensure();
    }
    this.system("twitchchat.messages.connecting", channel);
    this.irc.connect(this.credentials(), channel);
  }

  public void disconnect() {
    boolean wasConnected = this.state != State.DISCONNECTED;
    this.state = State.DISCONNECTED;
    this.roomId = null;
    this.joinedChannel = null;
    this.irc.disconnect();
    if (wasConnected) {
      this.system("twitchchat.messages.disconnected");
    }
  }

  public void join(String channel) {
    String normalized = TwitchIrcClient.normalize(channel);
    if (normalized == null) {
      return;
    }
    this.config.channel().set(normalized);
    this.save();
    if (this.state == State.DISCONNECTED) {
      this.connect(normalized);
      return;
    }
    this.switchChannel(normalized);
  }

  private void switchChannel(String channel) {
    this.roomId = null;
    this.joinedChannel = null;
    this.tab.clear();
    synchronized (this.history) {
      this.history.clear();
    }
    this.system("twitchchat.messages.connecting", channel);
    this.irc.join(channel);
  }

  private Credentials credentials() {
    if (!this.authenticated || !this.config.hasSession()) {
      return null;
    }
    return new Credentials(this.config.twitchLogin().get(), this.config.accessToken().get());
  }

  /**
   * Called once a second from the tick listener.
   */
  public void tick() {
    if (!this.isEnabled()) {
      return;
    }

    if (this.config.showInChatTab().get()) {
      if (this.state != State.DISCONNECTED || !this.history.isEmpty()) {
        this.tab.ensure();
      }
    } else if (this.tab.exists()) {
      this.tab.remove();
    }

    if (this.state != State.DISCONNECTED) {
      String configured = TwitchIrcClient.normalize(this.config.channel().get());
      String current = this.irc.channel();
      if (configured != null && !configured.equals(current)) {
        this.switchChannel(configured);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Sending
  // ---------------------------------------------------------------------------------------------

  /**
   * @return true if the message was handed to Twitch
   */
  public boolean send(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    if (!this.authenticated) {
      this.system("twitchchat.messages.loginRequired");
      return false;
    }
    if (this.state != State.CONNECTED) {
      this.system("twitchchat.messages.notConnected");
      return false;
    }
    if (!this.irc.sendMessage(text)) {
      this.system("twitchchat.messages.sendFailed");
      return false;
    }

    // Twitch does not echo our own PRIVMSG, so render it locally.
    String login = this.config.twitchLogin().get();
    boolean action = text.startsWith("/me ");
    this.dispatch(TwitchChatMessage.builder(action ? Kind.ACTION : Kind.CHAT)
        .channel(this.channel())
        .userId(this.config.userId().get())
        .login(login)
        .displayName(this.ownDisplayName != null ? this.ownDisplayName : login)
        .color(this.ownColor)
        .badges(this.ownBadges)
        .text(action ? text.substring(4) : text)
        .self(true)
        .build());
    return true;
  }

  // ---------------------------------------------------------------------------------------------
  // Session / login
  // ---------------------------------------------------------------------------------------------

  public String clientId() {
    String configured = this.config.clientId().get();
    return configured != null && !configured.isBlank() ? configured.trim() : DEFAULT_CLIENT_ID;
  }

  public void startLogin() {
    if (this.loginInProgress) {
      this.system("twitchchat.messages.loginPending");
      return;
    }
    String clientId = this.clientId();
    if (clientId.isEmpty()) {
      this.system("twitchchat.messages.noClientId");
      return;
    }

    this.loginInProgress = true;
    this.executor.execute(() -> {
      try {
        DeviceCode code = TwitchAuth.requestDeviceCode(clientId);
        ThreadSafe.executeOnRenderThread(() -> this.showDeviceCode(code));
        this.scheduler.schedule(() -> this.pollLogin(clientId, code, code.intervalSeconds()),
            code.intervalSeconds(), TimeUnit.SECONDS);
      } catch (IOException e) {
        this.loginInProgress = false;
        this.logger.warn("Twitch login could not be started", e);
        ThreadSafe.executeOnRenderThread(
            () -> this.system("twitchchat.messages.loginFailed", e.getMessage()));
      }
    });
  }

  private void showDeviceCode(DeviceCode code) {
    String url = code.verificationUri();
    TextComponent message = Component.empty();
    message.append(Component.translatable("twitchchat.messages.loginOpen", NamedTextColor.GRAY));
    message.append(Component.text(" "));
    message.append(Component.text(url, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
        .clickEvent(ClickEvent.openUrl(url))
        .hoverEvent(HoverEvent.showText(Component.translatable("twitchchat.chat.openLink"))));
    message.append(Component.text(" "));
    message.append(Component.translatable("twitchchat.messages.loginCode", NamedTextColor.GRAY));
    message.append(Component.text(" "));
    message.append(Component.text(code.userCode(), NamedTextColor.WHITE, TextDecoration.BOLD)
        .clickEvent(ClickEvent.copyToClipboard(code.userCode()))
        .hoverEvent(HoverEvent.showText(Component.translatable("twitchchat.messages.loginCopy"))));
    this.systemComponent(message);
  }

  private void pollLogin(String clientId, DeviceCode code, int interval) {
    if (code.isExpired()) {
      this.loginInProgress = false;
      ThreadSafe.executeOnRenderThread(
          () -> this.system("twitchchat.messages.loginExpired"));
      return;
    }

    int nextInterval = interval;
    try {
      TokenSet tokens = TwitchAuth.pollDeviceToken(clientId, code);
      if (tokens != null) {
        this.completeLogin(tokens);
        return;
      }
    } catch (AuthException e) {
      if ("slow_down".equals(e.code())) {
        nextInterval = interval + 5;
      } else {
        this.loginInProgress = false;
        ThreadSafe.executeOnRenderThread(
            () -> this.system("twitchchat.messages.loginFailed", e.getMessage()));
        return;
      }
    } catch (IOException e) {
      this.logger.warn("Twitch login poll failed: {}", e.getMessage());
    }

    int delay = nextInterval;
    this.scheduler.schedule(() -> this.pollLogin(clientId, code, delay), delay, TimeUnit.SECONDS);
  }

  private void completeLogin(TokenSet tokens) {
    try {
      Validation validation = TwitchAuth.validate(tokens.accessToken());
      if (validation == null) {
        throw new IOException("token rejected");
      }
      ThreadSafe.executeOnRenderThread(() -> {
        this.storeSession(tokens, validation);
        this.loginInProgress = false;
        this.system("twitchchat.messages.loginSuccess", validation.login());
        if (this.state != State.DISCONNECTED) {
          this.connect(this.irc.channel());
        }
      });
    } catch (IOException e) {
      this.loginInProgress = false;
      ThreadSafe.executeOnRenderThread(
          () -> this.system("twitchchat.messages.loginFailed", e.getMessage()));
    }
  }

  private void storeSession(TokenSet tokens, Validation validation) {
    this.config.accessToken().set(tokens.accessToken());
    this.config.refreshToken().set(tokens.refreshToken() == null ? "" : tokens.refreshToken());
    this.config.twitchLogin().set(validation.login());
    this.config.userId().set(validation.userId());
    this.authenticated = true;
    this.ownDisplayName = validation.login();
    this.save();
    this.badges.loadGlobal(this.clientId(), tokens.accessToken());
    if (this.roomId != null) {
      this.badges.loadChannel(this.roomId, this.clientId(), tokens.accessToken());
    }
  }

  public void logout() {
    boolean hadSession = this.config.hasSession();
    this.config.clearSession();
    this.authenticated = false;
    this.ownDisplayName = null;
    this.ownColor = -1;
    this.ownBadges = Collections.emptyList();
    this.save();
    if (hadSession) {
      this.system("twitchchat.messages.loggedOut");
    }
    if (this.state != State.DISCONNECTED) {
      this.connect(this.irc.channel());
    }
  }

  private void validateStoredSession(Runnable then) {
    if (!this.config.hasSession()) {
      then.run();
      return;
    }
    String clientId = this.clientId();
    String accessToken = this.config.accessToken().get();
    String refreshToken = this.config.refreshToken().get();
    this.executor.execute(() -> {
      TokenSet tokens = null;
      Validation validation = null;
      try {
        validation = TwitchAuth.validate(accessToken);
        if (validation != null) {
          tokens = new TokenSet(accessToken, refreshToken, 0);
        } else if (!clientId.isEmpty() && refreshToken != null && !refreshToken.isEmpty()) {
          tokens = TwitchAuth.refresh(clientId, refreshToken);
          if (tokens != null) {
            validation = TwitchAuth.validate(tokens.accessToken());
          }
        }
      } catch (IOException e) {
        this.logger.warn("Could not validate the stored Twitch session: {}", e.getMessage());
        // Network hiccup: keep the session and try to use it as-is.
        tokens = new TokenSet(accessToken, refreshToken, 0);
        validation = new Validation(this.config.twitchLogin().get(), this.config.userId().get(), clientId, 0);
      }

      TokenSet finalTokens = tokens;
      Validation finalValidation = validation;
      ThreadSafe.executeOnRenderThread(() -> {
        if (finalTokens == null || finalValidation == null) {
          this.config.clearSession();
          this.authenticated = false;
          this.save();
          this.system("twitchchat.messages.sessionExpired");
        } else {
          this.storeSession(finalTokens, finalValidation);
        }
        then.run();
      });
    });
  }

  private void save() {
    try {
      this.addon.saveConfiguration();
    } catch (Exception e) {
      this.logger.warn("Could not save the Twitch chat configuration", e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // IRC callbacks (called from the IRC threads)
  // ---------------------------------------------------------------------------------------------

  @Override
  public void onConnected(boolean anonymous) {
    ThreadSafe.executeOnRenderThread(() -> {
      this.state = State.CONNECTED;
      if (anonymous && !this.announcedAnonymous) {
        this.announcedAnonymous = true;
        this.system("twitchchat.messages.readOnly");
      }
    });
  }

  @Override
  public void onDisconnected(Throwable reason, boolean willReconnect) {
    ThreadSafe.executeOnRenderThread(() -> {
      if (this.state == State.DISCONNECTED) {
        return;
      }
      this.state = State.CONNECTING;
      this.joinedChannel = null;
      String detail = reason == null ? "" : String.valueOf(reason.getMessage());
      this.system("twitchchat.messages.connectionLost", detail);
    });
  }

  @Override
  public void onAuthenticationFailed() {
    ThreadSafe.executeOnRenderThread(() -> {
      this.system("twitchchat.messages.authFailed");
      this.config.clearSession();
      this.authenticated = false;
      this.save();
      this.connect(this.irc.channel());
    });
  }

  @Override
  public void onLine(IrcLine line) {
    ThreadSafe.executeOnRenderThread(() -> this.handle(line));
  }

  private void handle(IrcLine line) {
    switch (line.command()) {
      case "PRIVMSG" -> this.handlePrivateMessage(line);
      case "USERNOTICE" -> this.handleUserNotice(line);
      case "CLEARCHAT" -> this.handleClearChat(line);
      case "CLEARMSG" -> this.tab.deleteMessage(line.tag("target-msg-id", ""));
      case "ROOMSTATE" -> this.handleRoomState(line);
      case "USERSTATE", "GLOBALUSERSTATE" -> this.handleUserState(line);
      case "NOTICE" -> this.handleNotice(line);
      case "JOIN" -> this.handleJoin(line);
      default -> {
      }
    }
  }

  private void handlePrivateMessage(IrcLine line) {
    String text = line.trailing();
    if (text == null) {
      return;
    }
    Kind kind = Kind.CHAT;
    if (text.startsWith(ACTION_PREFIX) && text.endsWith(ACTION_SUFFIX)) {
      kind = Kind.ACTION;
      text = text.substring(ACTION_PREFIX.length(), text.length() - ACTION_SUFFIX.length());
    }
    String login = line.nick();
    this.dispatch(TwitchChatMessage.builder(kind)
        .id(line.tag("id"))
        .channel(line.channel())
        .userId(line.tag("user-id"))
        .login(login)
        .displayName(line.tag("display-name", login))
        .color(TwitchColors.parseHex(line.tag("color")))
        .badges(parseBadges(line.tag("badges")))
        .emotes(parseEmotes(line.tag("emotes")))
        .text(text)
        .timestamp(parseTimestamp(line.tag("tmi-sent-ts")))
        .build());
  }

  private void handleUserNotice(IrcLine line) {
    if (!this.config.showEvents().get()) {
      return;
    }
    String login = line.tag("login");
    String text = line.trailing();
    this.dispatch(TwitchChatMessage.builder(Kind.EVENT)
        .id(line.tag("id"))
        .channel(line.channel())
        .userId(line.tag("user-id"))
        .login(login)
        .displayName(line.tag("display-name", login))
        .color(TwitchColors.parseHex(line.tag("color")))
        .badges(parseBadges(line.tag("badges")))
        .emotes(parseEmotes(line.tag("emotes")))
        .eventText(line.tag("system-msg", ""))
        .text(text == null ? "" : text)
        .timestamp(parseTimestamp(line.tag("tmi-sent-ts")))
        .build());
  }

  private void handleClearChat(IrcLine line) {
    if (line.params().size() < 2) {
      this.tab.clear();
      synchronized (this.history) {
        this.history.clear();
      }
      if (this.config.showEvents().get()) {
        this.system("twitchchat.messages.chatCleared");
      }
      return;
    }

    String target = line.trailing();
    this.tab.deleteMessagesOf(target);
    synchronized (this.history) {
      this.history.removeIf(entry -> target.equals(entry.message().login()));
    }
    if (!this.config.showEvents().get()) {
      return;
    }
    String duration = line.tag("ban-duration");
    if (duration == null || duration.isEmpty()) {
      this.system("twitchchat.messages.userBanned", target);
    } else {
      this.system("twitchchat.messages.userTimedOut", target, duration);
    }
  }

  private void handleRoomState(IrcLine line) {
    String room = line.tag("room-id");
    if (room == null || room.isEmpty()) {
      return;
    }
    this.roomId = room;
    if (this.config.thirdPartyEmotes().get()) {
      this.thirdParty.loadChannel(room);
    }
    if (this.authenticated) {
      this.badges.loadChannel(room, this.clientId(), this.config.accessToken().get());
    }
  }

  private void handleUserState(IrcLine line) {
    String displayName = line.tag("display-name");
    if (displayName != null && !displayName.isEmpty()) {
      this.ownDisplayName = displayName;
    }
    int color = TwitchColors.parseHex(line.tag("color"));
    if (color >= 0) {
      this.ownColor = color;
    }
    String badges = line.tag("badges");
    if (badges != null) {
      this.ownBadges = parseBadges(badges);
    }
  }

  private void handleNotice(IrcLine line) {
    String text = line.trailing();
    if (text == null || text.isEmpty()) {
      return;
    }
    this.systemText(text);
  }

  private void handleJoin(IrcLine line) {
    String nick = line.nick();
    String channel = line.channel();
    if (nick == null || channel == null) {
      return;
    }
    boolean self = nick.startsWith("justinfan")
        || nick.equalsIgnoreCase(this.config.twitchLogin().get());
    if (!self || channel.equals(this.joinedChannel)) {
      return;
    }
    this.joinedChannel = channel;
    this.system("twitchchat.messages.joined", channel);
  }

  // ---------------------------------------------------------------------------------------------
  // Output
  // ---------------------------------------------------------------------------------------------

  /**
   * Shows a status line. The key is resolved when the line is rendered, so messages emitted
   * before the addon's language files are loaded still come out translated.
   */
  public void system(String key, Object... arguments) {
    this.systemComponent(translatable(key, arguments));
  }

  /**
   * Shows a status line that Twitch sent us verbatim and that must not be translated.
   */
  private void systemText(String text) {
    this.dispatch(TwitchChatMessage.system(text));
  }

  private void systemComponent(Component component) {
    this.dispatch(TwitchChatMessage.system(component));
  }

  private static Component translatable(String key, Object... arguments) {
    if (arguments.length == 0) {
      return Component.translatable(key, NamedTextColor.GRAY);
    }
    Component[] components = new Component[arguments.length];
    for (int index = 0; index < arguments.length; index++) {
      components[index] = Component.text(String.valueOf(arguments[index]));
    }
    return Component.translatable(key, NamedTextColor.GRAY, components);
  }

  private void dispatch(TwitchChatMessage message) {
    List<String> mentionTargets = this.mentionTargets();
    Component component = this.components.build(message, this.roomId, mentionTargets,
        this.authenticated);
    boolean highlight = this.components.isMention(message, mentionTargets);
    this.output(message, component, highlight);
  }

  private void output(TwitchChatMessage message, Component component, boolean highlight) {
    boolean inTab = this.config.showInChatTab().get() && this.tab.ensure();
    if (inTab) {
      this.tab.push(component, message, highlight);
    }

    // Status lines must reach the player even when the tab is off or the advanced chat is
    // disabled; regular messages are mirrored only when asked for.
    boolean mirror = this.config.mirrorToMainChat().get()
        || (!inTab && message.kind() == Kind.SYSTEM);
    if (mirror) {
      Laby.labyAPI().minecraft().chatExecutor().displayClientMessage(
          Component.empty().append(TwitchComponentBuilder.PREFIX).append(Component.text(" "))
              .append(component));
    }

    if (message.kind() != Kind.SYSTEM) {
      synchronized (this.history) {
        this.history.addLast(new HistoryEntry(message, component, highlight));
        while (this.history.size() > HISTORY_SIZE) {
          this.history.removeFirst();
        }
      }
    }
  }

  public List<String> mentionTargets() {
    List<String> targets = new ArrayList<>(2);
    String login = this.config.twitchLogin().get();
    if (login != null && !login.isEmpty()) {
      targets.add(login.toLowerCase(Locale.ROOT));
    }
    String name = Laby.labyAPI().getName();
    if (name != null && !name.isEmpty()) {
      targets.add(name.toLowerCase(Locale.ROOT));
    }
    return targets;
  }

  public Component status() {
    String channel = this.channel();
    String stateKey = switch (this.state) {
      case CONNECTED -> "twitchchat.messages.status.connected";
      case CONNECTING -> "twitchchat.messages.status.connecting";
      case DISCONNECTED -> "twitchchat.messages.status.disconnected";
    };
    TextComponent status = Component.empty();
    status.append(translatable(stateKey, channel == null ? "-" : channel));
    status.append(Component.newline());
    String login = this.config.twitchLogin().get();
    status.append(this.authenticated
        ? translatable("twitchchat.messages.status.loggedIn", login)
        : translatable("twitchchat.messages.status.anonymous"));
    return status;
  }

  // ---------------------------------------------------------------------------------------------
  // Tag parsing
  // ---------------------------------------------------------------------------------------------

  static List<TwitchBadge> parseBadges(String tag) {
    if (tag == null || tag.isEmpty()) {
      return Collections.emptyList();
    }
    List<TwitchBadge> badges = new ArrayList<>();
    for (String entry : tag.split(",")) {
      int slash = entry.indexOf('/');
      if (slash <= 0) {
        continue;
      }
      badges.add(new TwitchBadge(entry.substring(0, slash), entry.substring(slash + 1)));
    }
    return badges;
  }

  static List<TwitchEmoteRange> parseEmotes(String tag) {
    if (tag == null || tag.isEmpty()) {
      return Collections.emptyList();
    }
    List<TwitchEmoteRange> emotes = new ArrayList<>();
    for (String emote : tag.split("/")) {
      int colon = emote.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      String id = emote.substring(0, colon);
      for (String range : emote.substring(colon + 1).split(",")) {
        int dash = range.indexOf('-');
        if (dash <= 0) {
          continue;
        }
        try {
          int start = Integer.parseInt(range.substring(0, dash));
          int end = Integer.parseInt(range.substring(dash + 1));
          emotes.add(new TwitchEmoteRange(id, start, end));
        } catch (NumberFormatException ignored) {
        }
      }
    }
    return emotes;
  }

  static long parseTimestamp(String tag) {
    if (tag == null || tag.isEmpty()) {
      return 0;
    }
    try {
      return Long.parseLong(tag);
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
