package net.labymod.addons.twitchchat.core.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.labymod.addons.twitchchat.core.model.TwitchChatMessage;
import net.labymod.api.Laby;
import net.labymod.api.client.chat.ChatMessage;
import net.labymod.api.client.chat.advanced.AdvancedChatController;
import net.labymod.api.client.chat.advanced.IngameChatTab;
import net.labymod.api.client.component.Component;
import net.labymod.api.configuration.labymod.chat.AdvancedChatMessage;
import net.labymod.api.configuration.labymod.chat.ChatTab;
import net.labymod.api.configuration.labymod.chat.ChatWindow;
import net.labymod.api.configuration.labymod.chat.category.GeneralChatTabConfig;
import net.labymod.api.configuration.labymod.chat.config.RootChatTabConfig.Type;
import net.labymod.api.configuration.labymod.chat.config.TemporaryChatTabConfig;
import net.labymod.api.util.I18n;

/**
 * Owns the "Twitch" tab in the main chat window. The tab is a runtime-only
 * {@link TemporaryChatTabConfig}, so it is never written into the player's chat layout and is
 * recreated whenever the advanced chat reloads.
 */
public final class TwitchChatTab {

  public static final String METADATA_KEY = "twitchchat.message";
  public static final Type TYPE = Type.of("TWITCH");
  public static final int INDEX = 900;
  private static final int HIGHLIGHT_BACKGROUND = 0x669146FF;
  private static final int TRACKED_MESSAGES = 1000;

  private final Map<String, ChatMessage> messagesById = new LinkedHashMap<>() {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, ChatMessage> eldest) {
      return this.size() > TRACKED_MESSAGES;
    }
  };
  private final Map<String, List<String>> messageIdsByLogin = new HashMap<>();

  private IngameChatTab tab;
  private ChatWindow window;

  /**
   * Creates the tab if the advanced chat is enabled and the tab is missing.
   *
   * @return true if the tab exists afterwards
   */
  public boolean ensure() {
    if (!Laby.labyAPI().config().ingame().advancedChat().enabled().get()) {
      return false;
    }

    AdvancedChatController controller = Laby.references().advancedChatController();
    if (this.tab != null && this.window != null
        && controller.getWindows().contains(this.window)
        && this.window.getTabs().contains(this.tab)) {
      this.refreshName();
      return true;
    }

    ChatWindow target = null;
    for (ChatWindow window : controller.getWindows()) {
      if (window.isMainWindow()) {
        target = window;
        break;
      }
      if (target == null) {
        target = window;
      }
    }
    if (target == null) {
      return false;
    }

    // Guard against a stale tab that survived a reload in another window instance.
    for (ChatTab existing : target.getTabs()) {
      if (existing instanceof IngameChatTab ingame
          && existing.rootConfig().type().get().equals(TYPE)) {
        this.tab = ingame;
        this.window = target;
        this.refreshName();
        return true;
      }
    }

    TemporaryChatTabConfig config = new TemporaryChatTabConfig(
        INDEX,
        TYPE,
        new GeneralChatTabConfig(tabName())
    );
    ChatTab created = target.initializeTab(config, null, false);
    if (!(created instanceof IngameChatTab ingame)) {
      return false;
    }

    this.tab = ingame;
    this.window = target;
    this.messagesById.clear();
    this.messageIdsByLogin.clear();
    return true;
  }

  private static String tabName() {
    String translated = I18n.getTranslation("twitchchat.chat.tab");
    return translated == null ? "Twitch" : translated;
  }

  private void refreshName() {
    if (this.tab == null) {
      return;
    }
    String name = tabName();
    if (!name.equals(this.tab.config().name().get())) {
      this.tab.config().name().set(name);
    }
  }

  public void remove() {
    if (this.tab != null && this.window != null && this.window.getTabs().contains(this.tab)) {
      this.window.deleteTab(this.tab);
    }
    this.tab = null;
    this.window = null;
    this.messagesById.clear();
    this.messageIdsByLogin.clear();
  }

  public void invalidate() {
    this.tab = null;
    this.window = null;
  }

  public boolean exists() {
    return this.tab != null;
  }

  public boolean isTwitchTab(ChatTab chatTab) {
    return this.tab != null && chatTab == this.tab;
  }

  /**
   * @return true if the Twitch tab is the one the player is currently looking at
   */
  public boolean isActive() {
    return this.tab != null && this.window != null && this.window.getActiveTab() == this.tab;
  }

  public void push(Component component, TwitchChatMessage message, boolean highlight) {
    if (this.tab == null) {
      return;
    }

    AdvancedChatMessage advanced = AdvancedChatMessage.component(component);
    advanced.metadata().set(METADATA_KEY, Boolean.TRUE);
    if (highlight) {
      advanced.metadata().set(IngameChatTab.CUSTOM_BACKGROUND, HIGHLIGHT_BACKGROUND);
    }
    this.tab.handleInput(advanced);

    this.messagesById.put(message.id(), advanced.chatMessage());
    if (message.hasUser()) {
      this.messageIdsByLogin.computeIfAbsent(message.login(), key -> new ArrayList<>())
          .add(message.id());
    }
  }

  public void deleteMessage(String id) {
    ChatMessage chatMessage = this.messagesById.remove(id);
    if (chatMessage != null && this.tab != null) {
      this.tab.handleMessageDelete(chatMessage);
    }
  }

  public void deleteMessagesOf(String login) {
    List<String> ids = this.messageIdsByLogin.remove(login);
    if (ids == null) {
      return;
    }
    for (String id : ids) {
      this.deleteMessage(id);
    }
  }

  public void clear() {
    if (this.tab != null) {
      this.tab.getMessages().clear();
    }
    this.messagesById.clear();
    this.messageIdsByLogin.clear();
  }
}
