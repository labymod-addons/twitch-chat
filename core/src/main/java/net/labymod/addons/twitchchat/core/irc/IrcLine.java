package net.labymod.addons.twitchchat.core.irc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One parsed IRCv3 line as Twitch sends it: optional {@code @tags}, optional {@code :prefix},
 * the command and its parameters (the trailing parameter is the last entry of {@link #params()}).
 */
public record IrcLine(Map<String, String> tags, String prefix, String command, List<String> params) {

  public static IrcLine parse(String raw) {
    Map<String, String> tags = Collections.emptyMap();
    String prefix = null;
    String rest = raw;

    if (rest.startsWith("@")) {
      int space = rest.indexOf(' ');
      if (space < 0) {
        return new IrcLine(parseTags(rest.substring(1)), null, "", List.of());
      }
      tags = parseTags(rest.substring(1, space));
      rest = rest.substring(space + 1);
    }

    if (rest.startsWith(":")) {
      int space = rest.indexOf(' ');
      if (space < 0) {
        return new IrcLine(tags, rest.substring(1), "", List.of());
      }
      prefix = rest.substring(1, space);
      rest = rest.substring(space + 1);
    }

    String command;
    List<String> params = new ArrayList<>();
    int space = rest.indexOf(' ');
    if (space < 0) {
      command = rest;
    } else {
      command = rest.substring(0, space);
      rest = rest.substring(space + 1);
      while (!rest.isEmpty()) {
        if (rest.startsWith(":")) {
          params.add(rest.substring(1));
          break;
        }
        int next = rest.indexOf(' ');
        if (next < 0) {
          params.add(rest);
          break;
        }
        params.add(rest.substring(0, next));
        rest = rest.substring(next + 1);
      }
    }

    return new IrcLine(tags, prefix, command, Collections.unmodifiableList(params));
  }

  private static Map<String, String> parseTags(String raw) {
    Map<String, String> tags = new LinkedHashMap<>();
    for (String pair : raw.split(";")) {
      if (pair.isEmpty()) {
        continue;
      }
      int equals = pair.indexOf('=');
      if (equals < 0) {
        tags.put(pair, "");
      } else {
        tags.put(pair.substring(0, equals), unescapeTag(pair.substring(equals + 1)));
      }
    }
    return Collections.unmodifiableMap(tags);
  }

  /**
   * Reverses the IRCv3 tag value escaping ({@code \s}, {@code \:}, {@code \\}, {@code \r},
   * {@code \n}).
   */
  public static String unescapeTag(String value) {
    if (value.indexOf('\\') < 0) {
      return value;
    }
    StringBuilder builder = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c != '\\' || i + 1 >= value.length()) {
        builder.append(c);
        continue;
      }
      char next = value.charAt(++i);
      switch (next) {
        case 's' -> builder.append(' ');
        case ':' -> builder.append(';');
        case '\\' -> builder.append('\\');
        case 'r' -> builder.append('\r');
        case 'n' -> builder.append('\n');
        default -> builder.append(next);
      }
    }
    return builder.toString();
  }

  public String tag(String key) {
    return this.tags.get(key);
  }

  public String tag(String key, String fallback) {
    String value = this.tags.get(key);
    return value == null || value.isEmpty() ? fallback : value;
  }

  public String param(int index) {
    return index < this.params.size() ? this.params.get(index) : null;
  }

  public String trailing() {
    return this.params.isEmpty() ? null : this.params.get(this.params.size() - 1);
  }

  /**
   * @return the nick part of the prefix ({@code nick!user@host}) or the whole prefix
   */
  public String nick() {
    if (this.prefix == null) {
      return null;
    }
    int bang = this.prefix.indexOf('!');
    return bang < 0 ? this.prefix : this.prefix.substring(0, bang);
  }

  /**
   * @return the channel parameter without the leading {@code #}, or null
   */
  public String channel() {
    for (String param : this.params) {
      if (param.startsWith("#")) {
        return param.substring(1);
      }
    }
    return null;
  }
}
