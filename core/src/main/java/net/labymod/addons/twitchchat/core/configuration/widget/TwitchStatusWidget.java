package net.labymod.addons.twitchchat.core.configuration.widget;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.labymod.addons.twitchchat.core.TwitchChatAddon;
import net.labymod.addons.twitchchat.core.auth.TwitchAuth.DeviceCode;
import net.labymod.addons.twitchchat.core.chat.TwitchChatController;
import net.labymod.api.Laby;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.format.NamedTextColor;
import net.labymod.api.client.component.format.TextColor;
import net.labymod.api.client.component.format.TextDecoration;
import net.labymod.api.client.gui.HorizontalAlignment;
import net.labymod.api.client.gui.lss.property.annotation.AutoWidget;
import net.labymod.api.client.gui.screen.Parent;
import net.labymod.api.client.gui.screen.widget.Widget;
import net.labymod.api.client.gui.screen.widget.widgets.ComponentWidget;
import net.labymod.api.client.gui.screen.widget.widgets.input.ButtonWidget;
import net.labymod.api.client.gui.screen.widget.widgets.layout.list.HorizontalListWidget;
import net.labymod.api.configuration.settings.Setting;
import net.labymod.api.configuration.settings.SettingInfo;
import net.labymod.api.configuration.settings.accessor.SettingAccessor;
import net.labymod.api.configuration.settings.annotation.SettingElement;
import net.labymod.api.configuration.settings.annotation.SettingFactory;
import net.labymod.api.configuration.settings.annotation.SettingWidget;
import net.labymod.api.configuration.settings.widget.WidgetFactory;

/**
 * The button of a settings row that shows a live status next to it, e.g. the logged in account
 * next to "Log out". Button text, enabled state and the status label follow the controller.
 */
@AutoWidget
@SettingWidget
public class TwitchStatusWidget extends ButtonWidget {

  private static final String PREFIX = "twitchchat.settings.";
  private static final TextColor TWITCH_PURPLE = TextColor.color(0xBF94FF);

  private final Kind kind;
  private final StatusLabel label;
  private View view;

  public TwitchStatusWidget(Kind kind, StatusLabel label) {
    this.kind = kind;
    this.label = label;
    this.setPressable(() -> {
      this.press();
      this.refresh();
    });
    label.setPressable(() -> {
      if (this.view != null && this.view.copyText() != null) {
        Laby.labyAPI().minecraft().setClipboard(this.view.copyText());
      }
    });
  }

  @Override
  public void initialize(Parent parent) {
    // The settings screen reuses this widget, so pick up whatever changed while it was closed.
    this.view = null;
    this.refresh();
    super.initialize(parent);
  }

  @Override
  public void tick() {
    super.tick();
    this.refresh();
  }

  private void refresh() {
    TwitchChatController controller = TwitchChatAddon.INSTANCE.controller();
    if (controller == null) {
      return;
    }
    View next = this.kind == Kind.CONNECTION ? connection(controller) : account(controller);
    if (this.view != null && this.view.key().equals(next.key())) {
      return;
    }
    this.view = next;
    this.updateComponent(next.button());
    this.setEnabled(next.enabled());
    this.label.setStatus(next.status());
    this.label.setHoverComponent(next.hover());
  }

  private void press() {
    TwitchChatController controller = TwitchChatAddon.INSTANCE.controller();
    if (controller == null) {
      return;
    }
    if (this.kind == Kind.CONNECTION) {
      controller.toggleConnection();
    } else if (controller.pendingLogin() != null) {
      controller.cancelLogin();
    } else if (controller.accountName() != null) {
      controller.logout();
    } else {
      controller.startLogin(true);
    }
  }

  private static View connection(TwitchChatController controller) {
    String channel = controller.channel();
    Component disconnect = Component.translatable(PREFIX + "connection.disconnect");
    return switch (controller.state()) {
      case CONNECTED -> new View("connected:" + channel,
          Component.text(channel, NamedTextColor.GREEN),
          Component.translatable(PREFIX + "connection.connected", Component.text(channel)),
          disconnect, true, null);
      case CONNECTING -> new View("connecting",
          Component.translatable(PREFIX + "connection.connecting", NamedTextColor.YELLOW),
          null, disconnect, true, null);
      case DISCONNECTED -> new View("disconnected",
          Component.translatable(PREFIX + "connection.disconnected", NamedTextColor.GRAY),
          null, Component.translatable(PREFIX + "connection.connect"), true, null);
    };
  }

  private static View account(TwitchChatController controller) {
    Component login = Component.translatable(PREFIX + "account.login");
    DeviceCode code = controller.pendingLogin();
    if (code != null) {
      return new View("pending:" + code.userCode(),
          Component.text(code.userCode(), NamedTextColor.WHITE, TextDecoration.BOLD),
          Component.translatable(PREFIX + "account.codeHint"),
          Component.translatable(PREFIX + "account.cancel"), true, code.userCode());
    }
    if (controller.isLoginStarting()) {
      return new View("starting",
          Component.translatable(PREFIX + "account.starting", NamedTextColor.GRAY),
          null, login, false, null);
    }
    String name = controller.accountName();
    if (name != null) {
      return new View("account:" + name,
          Component.text(name, TWITCH_PURPLE),
          Component.translatable(PREFIX + "account.loggedIn", Component.text(name)),
          Component.translatable(PREFIX + "account.logout"), true, null);
    }
    return new View("anonymous",
        Component.translatable(PREFIX + "account.loggedOut", NamedTextColor.GRAY),
        null, login, true, null);
  }

  public enum Kind {
    CONNECTION,
    ACCOUNT
  }

  /**
   * What the row shows. {@code key} identifies the state, so the widgets are only updated when it
   * changes.
   */
  private record View(String key, Component status, Component hover, Component button,
                      boolean enabled, String copyText) {

  }

  /**
   * Holds the status text. The settings row gives every input the button's height, and a list
   * centers its entries vertically where a bare text widget would sit at the top.
   */
  @AutoWidget
  public static class StatusLabel extends HorizontalListWidget {

    private final ComponentWidget text = ComponentWidget.empty();

    @Override
    public void initialize(Parent parent) {
      super.initialize(parent);
      this.addEntry(this.text).alignment().set(HorizontalAlignment.RIGHT);
    }

    void setStatus(Component status) {
      this.text.setComponent(status);
      // A shorter text would otherwise keep the old left edge and drift away from the button.
      this.updateContentBounds();
    }
  }

  @SettingElement
  @Target({ElementType.METHOD})
  @Retention(RetentionPolicy.RUNTIME)
  public @interface TwitchStatusSetting {

    Kind value();
  }

  @SettingFactory
  public static class Factory implements WidgetFactory<TwitchStatusSetting, Widget> {

    @Override
    public Widget[] create(Setting setting, TwitchStatusSetting annotation, SettingInfo<?> info,
        SettingAccessor accessor) {
      StatusLabel label = new StatusLabel();
      // The settings row lays these out right to left: the status ends up left of the button.
      return new Widget[]{label, new TwitchStatusWidget(annotation.value(), label)};
    }

    @Override
    public Class<?>[] types() {
      return new Class[0];
    }
  }
}
