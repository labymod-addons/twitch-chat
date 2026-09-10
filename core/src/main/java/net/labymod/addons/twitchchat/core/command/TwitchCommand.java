package net.labymod.addons.twitchchat.core.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.labymod.addons.twitchchat.core.chat.TwitchChatController;
import net.labymod.addons.twitchchat.core.chat.TwitchComponentBuilder;
import net.labymod.api.client.chat.command.Command;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.format.NamedTextColor;
import net.labymod.api.util.I18n;

/**
 * {@code /twitch <join|leave|connect|login|logout|status|say>} and the shorthand
 * {@code /tw <message>} to write into the channel from any chat tab.
 */
public final class TwitchCommand extends Command {

  private static final List<String> SUB_COMMANDS = List.of(
      "join", "leave", "connect", "login", "logout", "status", "say"
  );

  private final TwitchChatController controller;

  public TwitchCommand(TwitchChatController controller) {
    super("twitch", "tw");
    this.controller = controller;
    this.messagePrefix(TwitchComponentBuilder.PREFIX);
  }

  @Override
  public boolean execute(String prefix, String[] arguments) {
    if (prefix.equalsIgnoreCase("tw")) {
      if (arguments.length == 0) {
        this.help();
        return true;
      }
      this.controller.send(String.join(" ", arguments));
      return true;
    }

    if (arguments.length == 0) {
      this.help();
      return true;
    }

    switch (arguments[0].toLowerCase(Locale.ROOT)) {
      case "join" -> {
        if (arguments.length < 2) {
          this.displayMessage(Component.text("/twitch join <channel>", NamedTextColor.GRAY));
          return true;
        }
        this.controller.join(arguments[1]);
      }
      case "leave", "disconnect" -> this.controller.disconnect();
      case "connect" -> this.controller.connect();
      case "login" -> this.controller.startLogin();
      case "logout" -> this.controller.logout();
      case "status" -> this.displayMessage(this.controller.status());
      case "say" -> {
        if (arguments.length < 2) {
          this.displayMessage(Component.text("/twitch say <message>", NamedTextColor.GRAY));
          return true;
        }
        this.controller.send(String.join(" ", Arrays.copyOfRange(arguments, 1, arguments.length)));
      }
      default -> this.help();
    }
    return true;
  }

  @Override
  public List<String> complete(String[] arguments) {
    if (arguments.length > 1) {
      return List.of();
    }
    String typed = arguments.length == 0 ? "" : arguments[0].toLowerCase(Locale.ROOT);
    List<String> result = new ArrayList<>();
    for (String sub : SUB_COMMANDS) {
      if (sub.startsWith(typed)) {
        result.add(sub);
      }
    }
    return result;
  }

  private void help() {
    this.displayMessage(Component.text(I18n.translate("twitchchat.commands.help"), NamedTextColor.GRAY));
  }
}
