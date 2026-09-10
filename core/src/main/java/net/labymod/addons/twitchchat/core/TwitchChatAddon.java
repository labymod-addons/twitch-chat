package net.labymod.addons.twitchchat.core;

import net.labymod.addons.twitchchat.core.chat.TwitchChatController;
import net.labymod.addons.twitchchat.core.command.TwitchCommand;
import net.labymod.addons.twitchchat.core.configuration.TwitchChatConfiguration;
import net.labymod.addons.twitchchat.core.hud.TwitchChatHudWidget;
import net.labymod.addons.twitchchat.core.listener.ChatListener;
import net.labymod.addons.twitchchat.core.listener.TickListener;
import net.labymod.api.addon.LabyAddon;
import net.labymod.api.models.addon.annotation.AddonMain;

@AddonMain
public class TwitchChatAddon extends LabyAddon<TwitchChatConfiguration> {

  public static TwitchChatAddon INSTANCE;

  private TwitchChatController controller;

  public TwitchChatAddon() {
    INSTANCE = this;
  }

  @Override
  protected void enable() {
    this.registerSettingCategory();

    this.controller = new TwitchChatController(this);
    this.registerListener(new ChatListener(this.controller));
    this.registerListener(new TickListener(this.controller));
    this.registerCommand(new TwitchCommand(this.controller));
    this.labyAPI().hudWidgetRegistry().register(new TwitchChatHudWidget(this.controller));

    this.controller.initialize();
    this.logger().info("Twitch Chat enabled");
  }

  @Override
  protected void onActivated() {
    if (this.controller != null && this.configuration().autoConnect().get()) {
      this.controller.connect();
    }
  }

  @Override
  protected void onDeactivated() {
    if (this.controller != null) {
      this.controller.disconnect();
      this.controller.tab().remove();
    }
  }

  @Override
  protected Class<TwitchChatConfiguration> configurationClass() {
    return TwitchChatConfiguration.class;
  }

  public TwitchChatController controller() {
    return this.controller;
  }
}
