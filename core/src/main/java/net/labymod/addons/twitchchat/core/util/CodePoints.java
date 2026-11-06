package net.labymod.addons.twitchchat.core.util;

/**
 * Twitch counts emote ranges in code points, Java strings in chars. An emoji before an emote
 * shifts the two apart.
 */
public final class CodePoints {

  private CodePoints() {
  }

  /**
   * @return the char index of every code point in {@code text}, plus one last entry for the end
   * of the text
   */
  public static int[] offsets(String text) {
    int length = text.codePointCount(0, text.length());
    int[] offsets = new int[length + 1];
    int charIndex = 0;
    for (int i = 0; i < length; i++) {
      offsets[i] = charIndex;
      charIndex += Character.charCount(text.codePointAt(charIndex));
    }
    offsets[length] = text.length();
    return offsets;
  }
}
