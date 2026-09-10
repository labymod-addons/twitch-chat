package net.labymod.addons.twitchchat.core.listener;

import net.labymod.addons.twitchchat.core.chat.TwitchChatController;
import net.labymod.addons.twitchchat.core.chat.TwitchChatTab;
import net.labymod.api.event.Priority;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.chat.ChatMessageSendEvent;
import net.labymod.api.event.client.chat.advanced.AdvancedChatReloadEvent;
import net.labymod.api.event.client.chat.advanced.AdvancedChatTabMessageEvent;

/**
 * Routes messages: game chat stays out of the Twitch tab, Twitch messages stay in it, and text
 * typed while the Twitch tab is open goes to Twitch instead of the Minecraft server.
 */
public final class ChatListener {

  private final TwitchChatController controller;

  public ChatListener(TwitchChatController controller) {
    this.controller = controller;
  }

  @Subscribe(Priority.LATEST)
  public void onTabMessage(AdvancedChatTabMessageEvent event) {
    TwitchChatTab tab = this.controller.tab();
    boolean twitchMessage = event.message().metadata().getBoolean(TwitchChatTab.METADATA_KEY);
    if (tab.isTwitchTab(event.tab())) {
      // Our own messages always survive the user's chat filters; everything else stays out.
      event.setCancelled(!twitchMessage);
    } else if (twitchMessage) {
      event.setCancelled(true);
    }
  }

  @Subscribe
  public void onChatSend(ChatMessageSendEvent event) {
    if (!this.controller.isEnabled() || event.isMessageCommand()) {
      return;
    }
    if (!this.controller.tab().isActive()) {
      return;
    }
    event.setCancelled(true);
    this.controller.send(event.getMessage());
  }

  @Subscribe
  public void onChatReload(AdvancedChatReloadEvent event) {
    this.controller.tab().invalidate();
  }
}
