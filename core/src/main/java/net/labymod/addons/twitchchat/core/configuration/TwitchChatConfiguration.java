package net.labymod.addons.twitchchat.core.configuration;

import net.labymod.addons.twitchchat.core.TwitchChatAddon;
import net.labymod.api.addon.AddonConfig;
import net.labymod.api.client.gui.screen.widget.widgets.input.ButtonWidget.ButtonSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.SwitchWidget.SwitchSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.TextFieldWidget.TextFieldSetting;
import net.labymod.api.configuration.loader.annotation.ConfigName;
import net.labymod.api.configuration.loader.annotation.Exclude;
import net.labymod.api.configuration.loader.annotation.SpriteSlot;
import net.labymod.api.configuration.loader.annotation.SpriteTexture;
import net.labymod.api.configuration.loader.property.ConfigProperty;
import net.labymod.api.configuration.settings.annotation.SettingSection;
import net.labymod.api.util.MethodOrder;

@SuppressWarnings({"FieldCanBeLocal", "FieldMayBeFinal"})
@ConfigName("settings")
@SpriteTexture("settings")
public class TwitchChatConfiguration extends AddonConfig {

  @SpriteSlot
  @SwitchSetting
  private final ConfigProperty<Boolean> enabled = new ConfigProperty<>(true);

  @SettingSection("connection")
  @SpriteSlot(x = 1)
  @TextFieldSetting(maxLength = 64)
  private final ConfigProperty<String> channel = new ConfigProperty<>("");

  @SpriteSlot(x = 2)
  @SwitchSetting
  private final ConfigProperty<Boolean> autoConnect = new ConfigProperty<>(true);

  @MethodOrder(after = "autoConnect")
  @SpriteSlot(x = 3)
  @ButtonSetting
  public void toggleConnection() {
    TwitchChatAddon.INSTANCE.controller().toggleConnection();
  }

  @MethodOrder(after = "toggleConnection")
  @SettingSection("account")
  @SpriteSlot(y = 1)
  @ButtonSetting
  public void login() {
    TwitchChatAddon.INSTANCE.controller().startLogin();
  }

  @MethodOrder(after = "login")
  @SpriteSlot(x = 1, y = 1)
  @ButtonSetting
  public void logout() {
    TwitchChatAddon.INSTANCE.controller().logout();
  }

  @SettingSection("display")
  @SpriteSlot(y = 2)
  @SwitchSetting
  private final ConfigProperty<Boolean> showInChatTab = new ConfigProperty<>(true);

  @SpriteSlot(y = 2)
  @SwitchSetting
  private final ConfigProperty<Boolean> mirrorToMainChat = new ConfigProperty<>(false);

  @SpriteSlot(x = 1, y = 2)
  @SwitchSetting
  private final ConfigProperty<Boolean> showBadges = new ConfigProperty<>(true);

  @SpriteSlot(x = 2, y = 2)
  @SwitchSetting
  private final ConfigProperty<Boolean> showEmotes = new ConfigProperty<>(true);

  @SpriteSlot(x = 3, y = 2)
  @SwitchSetting
  private final ConfigProperty<Boolean> thirdPartyEmotes = new ConfigProperty<>(true);

  @SpriteSlot(y = 3)
  @SwitchSetting
  private final ConfigProperty<Boolean> showTimestamps = new ConfigProperty<>(false);

  @SpriteSlot(x = 1, y = 3)
  @SwitchSetting
  private final ConfigProperty<Boolean> showEvents = new ConfigProperty<>(true);

  @SpriteSlot(x = 2, y = 3)
  @SwitchSetting
  private final ConfigProperty<Boolean> highlightMentions = new ConfigProperty<>(true);

  @SettingSection("advanced")
  @SpriteSlot(x = 2, y = 1)
  @TextFieldSetting(maxLength = 64)
  private final ConfigProperty<String> clientId = new ConfigProperty<>("");

  // Stored, never shown: the Twitch session obtained via the device code flow.
  @Exclude
  private final ConfigProperty<String> accessToken = new ConfigProperty<>("");

  @Exclude
  private final ConfigProperty<String> refreshToken = new ConfigProperty<>("");

  @Exclude
  private final ConfigProperty<String> login = new ConfigProperty<>("");

  @Exclude
  private final ConfigProperty<String> userId = new ConfigProperty<>("");

  @Override
  public ConfigProperty<Boolean> enabled() {
    return this.enabled;
  }

  public ConfigProperty<String> channel() {
    return this.channel;
  }

  public ConfigProperty<Boolean> autoConnect() {
    return this.autoConnect;
  }

  public ConfigProperty<Boolean> showInChatTab() {
    return this.showInChatTab;
  }

  public ConfigProperty<Boolean> mirrorToMainChat() {
    return this.mirrorToMainChat;
  }

  public ConfigProperty<Boolean> showBadges() {
    return this.showBadges;
  }

  public ConfigProperty<Boolean> showEmotes() {
    return this.showEmotes;
  }

  public ConfigProperty<Boolean> thirdPartyEmotes() {
    return this.thirdPartyEmotes;
  }

  public ConfigProperty<Boolean> showTimestamps() {
    return this.showTimestamps;
  }

  public ConfigProperty<Boolean> showEvents() {
    return this.showEvents;
  }

  public ConfigProperty<Boolean> highlightMentions() {
    return this.highlightMentions;
  }

  public ConfigProperty<String> clientId() {
    return this.clientId;
  }

  public ConfigProperty<String> accessToken() {
    return this.accessToken;
  }

  public ConfigProperty<String> refreshToken() {
    return this.refreshToken;
  }

  public ConfigProperty<String> twitchLogin() {
    return this.login;
  }

  public ConfigProperty<String> userId() {
    return this.userId;
  }

  public boolean hasSession() {
    String token = this.accessToken.get();
    return token != null && !token.isEmpty();
  }

  public void clearSession() {
    this.accessToken.set("");
    this.refreshToken.set("");
    this.login.set("");
    this.userId.set("");
  }
}
