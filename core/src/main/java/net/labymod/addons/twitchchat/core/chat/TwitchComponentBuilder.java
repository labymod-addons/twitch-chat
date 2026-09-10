package net.labymod.addons.twitchchat.core.chat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.labymod.addons.twitchchat.core.configuration.TwitchChatConfiguration;
import net.labymod.addons.twitchchat.core.emote.ThirdPartyEmoteService;
import net.labymod.addons.twitchchat.core.emote.TwitchBadgeService;
import net.labymod.addons.twitchchat.core.model.TwitchBadge;
import net.labymod.addons.twitchchat.core.model.TwitchChatMessage;
import net.labymod.addons.twitchchat.core.model.TwitchChatMessage.Kind;
import net.labymod.addons.twitchchat.core.model.TwitchEmoteRange;
import net.labymod.addons.twitchchat.core.util.TwitchColors;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.TextComponent;
import net.labymod.api.client.component.event.ClickEvent;
import net.labymod.api.client.component.event.HoverEvent;
import net.labymod.api.client.component.format.NamedTextColor;
import net.labymod.api.client.component.format.TextColor;
import net.labymod.api.client.component.format.TextDecoration;
import net.labymod.api.client.gui.icon.Icon;
import net.labymod.api.util.I18n;

/**
 * Turns a {@link TwitchChatMessage} into a chat {@link Component}: badges and emotes become
 * inline icons, the name carries the user's Twitch color, links are clickable and mentions of
 * the player are highlighted.
 */
public final class TwitchComponentBuilder {

  public static final int ICON_SIZE = 9;
  public static final TextColor PURPLE = TextColor.color(TwitchColors.TWITCH_PURPLE);
  public static final Component PREFIX = Component.text("[", NamedTextColor.DARK_GRAY)
      .append(Component.text("Twitch", PURPLE))
      .append(Component.text("]", NamedTextColor.DARK_GRAY));

  private static final Pattern URL = Pattern.compile("^https?://[^\\s]+$", Pattern.CASE_INSENSITIVE);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

  private final TwitchChatConfiguration config;
  private final TwitchBadgeService badges;
  private final ThirdPartyEmoteService thirdParty;

  public TwitchComponentBuilder(
      TwitchChatConfiguration config,
      TwitchBadgeService badges,
      ThirdPartyEmoteService thirdParty
  ) {
    this.config = config;
    this.badges = badges;
    this.thirdParty = thirdParty;
  }

  public Component build(TwitchChatMessage message, String roomId, List<String> mentionTargets,
      boolean canReply) {
    TextComponent root = Component.empty();
    if (this.config.showTimestamps().get()) {
      String time = TIME.format(Instant.ofEpochMilli(message.timestamp()).atZone(ZoneId.systemDefault()));
      root.append(Component.text("[" + time + "] ", NamedTextColor.DARK_GRAY));
    }

    switch (message.kind()) {
      case SYSTEM -> root.append(Component.text(message.text(), NamedTextColor.GRAY));
      case EVENT -> {
        root.append(Component.text("★ ", PURPLE));
        if (message.eventText() != null && !message.eventText().isEmpty()) {
          root.append(Component.text(message.eventText(), NamedTextColor.GRAY, TextDecoration.ITALIC));
        }
        if (!message.text().isEmpty()) {
          if (message.eventText() != null && !message.eventText().isEmpty()) {
            root.append(Component.text(": ", NamedTextColor.DARK_GRAY));
          }
          root.append(this.body(message, roomId, NamedTextColor.WHITE, false, mentionTargets));
        }
      }
      case CHAT, ACTION -> {
        if (this.config.showBadges().get()) {
          this.appendBadges(root, message, roomId);
        }
        TextColor nameColor = TwitchColors.nameColor(message.color(), message.login());
        root.append(this.name(message, nameColor, canReply));
        boolean action = message.kind() == Kind.ACTION;
        root.append(Component.text(action ? " " : ": ", action ? nameColor : NamedTextColor.GRAY));
        TextColor bodyColor = action ? nameColor
            : this.isMention(message, mentionTargets) ? NamedTextColor.GOLD : NamedTextColor.WHITE;
        root.append(this.body(message, roomId, bodyColor, action, mentionTargets));
      }
    }
    return root;
  }

  public boolean isMention(TwitchChatMessage message, List<String> mentionTargets) {
    if (!this.config.highlightMentions().get() || message.isSelf() || mentionTargets.isEmpty()) {
      return false;
    }
    if (message.kind() != Kind.CHAT && message.kind() != Kind.ACTION) {
      return false;
    }
    String text = message.text().toLowerCase(Locale.ROOT);
    for (String target : mentionTargets) {
      if (target != null && !target.isEmpty() && text.contains("@" + target)) {
        return true;
      }
    }
    return false;
  }

  private void appendBadges(TextComponent root, TwitchChatMessage message, String roomId) {
    for (TwitchBadge badge : message.badges()) {
      String url = this.badges.url(badge, roomId);
      if (url == null) {
        continue;
      }
      root.append(Component.icon(Icon.url(url), ICON_SIZE)
          .hoverEvent(HoverEvent.showText(Component.text(this.badgeName(badge), NamedTextColor.GRAY))));
      root.append(Component.text(" "));
    }
  }

  private String badgeName(TwitchBadge badge) {
    String key = "twitchchat.badges." + badge.set().replace('-', '_');
    String translated = I18n.getTranslation(key);
    if (translated == null) {
      return badge.set().replace('-', ' ').replace('_', ' ');
    }
    if (badge.set().equals("subscriber") && !badge.version().equals("0")) {
      return translated + " (" + badge.version() + ")";
    }
    return translated;
  }

  private Component name(TwitchChatMessage message, TextColor color, boolean canReply) {
    TextComponent name = Component.text(message.displayName(), color);
    TextComponent hover = Component.text("@" + message.login(), NamedTextColor.GRAY);
    if (canReply && !message.isSelf()) {
      hover.append(Component.newline())
          .append(Component.text(I18n.translate("twitchchat.chat.clickToMention"), NamedTextColor.DARK_GRAY));
      name.clickEvent(ClickEvent.suggestCommand("/tw @" + message.displayName() + " "));
    }
    name.hoverEvent(HoverEvent.showText(hover));
    return name;
  }

  private Component body(TwitchChatMessage message, String roomId, TextColor color, boolean italic,
      List<String> mentionTargets) {
    TextComponent body = Component.empty();
    String text = message.text();
    int[] codePoints = text.codePoints().toArray();

    List<TwitchEmoteRange> emotes = new ArrayList<>();
    if (this.config.showEmotes().get()) {
      emotes.addAll(message.emotes());
      emotes.sort(Comparator.comparingInt(TwitchEmoteRange::start));
    }

    int cursor = 0;
    for (TwitchEmoteRange range : emotes) {
      if (range.start() < cursor || range.end() >= codePoints.length || range.end() < range.start()) {
        continue;
      }
      if (range.start() > cursor) {
        this.appendText(body, new String(codePoints, cursor, range.start() - cursor), roomId, color, italic);
      }
      String code = new String(codePoints, range.start(), range.end() - range.start() + 1);
      body.append(this.emote(range.url(), code));
      cursor = range.end() + 1;
    }
    if (cursor < codePoints.length) {
      this.appendText(body, new String(codePoints, cursor, codePoints.length - cursor), roomId, color, italic);
    }
    return body;
  }

  private Component emote(String url, String code) {
    return Component.icon(Icon.url(url), ICON_SIZE)
        .hoverEvent(HoverEvent.showText(Component.text(code, NamedTextColor.GRAY)));
  }

  private void appendText(TextComponent parent, String text, String roomId, TextColor color,
      boolean italic) {
    boolean thirdParty = this.config.showEmotes().get() && this.config.thirdPartyEmotes().get();
    StringBuilder buffer = new StringBuilder();
    String[] words = text.split(" ", -1);
    for (int i = 0; i < words.length; i++) {
      String word = words[i];
      if (i > 0) {
        buffer.append(' ');
      }
      if (word.isEmpty()) {
        continue;
      }

      String emoteUrl = thirdParty ? this.thirdParty.url(word, roomId) : null;
      if (emoteUrl != null) {
        this.flush(parent, buffer, color, italic);
        parent.append(this.emote(emoteUrl, word));
        continue;
      }

      if (URL.matcher(word).matches()) {
        this.flush(parent, buffer, color, italic);
        TextComponent link = Component.text(word, NamedTextColor.AQUA, TextDecoration.UNDERLINED);
        link.clickEvent(ClickEvent.openUrl(word));
        link.hoverEvent(HoverEvent.showText(
            Component.text(I18n.translate("twitchchat.chat.openLink"), NamedTextColor.GRAY)));
        parent.append(link);
        continue;
      }

      buffer.append(word);
    }
    this.flush(parent, buffer, color, italic);
  }

  private void flush(TextComponent parent, StringBuilder buffer, TextColor color, boolean italic) {
    if (buffer.isEmpty()) {
      return;
    }
    TextComponent component = Component.text(buffer.toString(), color);
    if (italic) {
      component.decorate(TextDecoration.ITALIC);
    }
    parent.append(component);
    buffer.setLength(0);
  }
}
