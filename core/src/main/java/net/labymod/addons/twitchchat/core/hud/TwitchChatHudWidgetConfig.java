package net.labymod.addons.twitchchat.core.hud;

import net.labymod.api.client.gui.hud.hudwidget.background.BackgroundHudWidget.BackgroundHudWidgetConfig;
import net.labymod.api.client.gui.screen.widget.widgets.input.SliderWidget.SliderSetting;
import net.labymod.api.configuration.loader.property.ConfigProperty;

@SuppressWarnings("FieldMayBeFinal")
public class TwitchChatHudWidgetConfig extends BackgroundHudWidgetConfig {

  @SliderSetting(min = 1, max = 15)
  private final ConfigProperty<Integer> maxMessages = new ConfigProperty<>(6);

  @SliderSetting(min = 0, max = 120, steps = 5)
  private final ConfigProperty<Integer> hideAfter = new ConfigProperty<>(20);

  @SliderSetting(min = 100, max = 400, steps = 10)
  private final ConfigProperty<Integer> width = new ConfigProperty<>(180);

  public ConfigProperty<Integer> maxMessages() {
    return this.maxMessages;
  }

  public ConfigProperty<Integer> hideAfter() {
    return this.hideAfter;
  }

  public ConfigProperty<Integer> width() {
    return this.width;
  }
}
