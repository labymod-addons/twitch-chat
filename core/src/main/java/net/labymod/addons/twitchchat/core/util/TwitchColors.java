package net.labymod.addons.twitchchat.core.util;

import net.labymod.api.client.component.format.TextColor;

/**
 * Twitch's default name palette for users that never picked a color, plus hex parsing.
 */
public final class TwitchColors {

  public static final int TWITCH_PURPLE = 0x9146FF;

  private static final int[] DEFAULT_COLORS = {
      0xFF0000, 0x0000FF, 0x008000, 0xB22222, 0xFF7F50,
      0x9ACD32, 0xFF4500, 0x2E8B57, 0xDAA520, 0xD2691E,
      0x5F9EA0, 0x1E90FF, 0xFF69B4, 0x8A2BE2, 0x00FF7F
  };

  private TwitchColors() {
  }

  /**
   * @return the RGB value of a {@code #RRGGBB} tag value or {@code -1}
   */
  public static int parseHex(String hex) {
    if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') {
      return -1;
    }
    try {
      return Integer.parseInt(hex.substring(1), 16);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * Mirrors the way the Twitch web chat picks a fallback color from the login name.
   */
  public static int defaultColor(String login) {
    if (login == null || login.isEmpty()) {
      return DEFAULT_COLORS[0];
    }
    int n = login.charAt(0) + login.charAt(login.length() - 1);
    return DEFAULT_COLORS[n % DEFAULT_COLORS.length];
  }

  public static TextColor nameColor(int color, String login) {
    int rgb = color >= 0 ? color : defaultColor(login);
    return TextColor.color(brighten(rgb));
  }

  /**
   * Lifts very dark colors so they stay readable on the chat background, like Twitch does in
   * dark mode.
   */
  public static int brighten(int rgb) {
    int r = (rgb >> 16) & 0xFF;
    int g = (rgb >> 8) & 0xFF;
    int b = rgb & 0xFF;
    double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
    if (luminance >= 70) {
      return rgb;
    }
    double factor = Math.min(3.0, 90.0 / Math.max(1.0, luminance));
    r = (int) Math.min(255, r * factor + 40);
    g = (int) Math.min(255, g * factor + 40);
    b = (int) Math.min(255, b * factor + 40);
    return (r << 16) | (g << 8) | b;
  }
}
