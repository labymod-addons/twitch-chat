package net.labymod.addons.twitchchat.core.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CodePointsTest {

  @Test
  void plainTextMapsOneToOne() {
    assertArrayEquals(new int[]{0, 1, 2, 3}, CodePoints.offsets("abc"));
  }

  @Test
  void emojiTakesTwoCharsButOneCodePoint() {
    // Twitch sends the emote range 2-6 for "Kappa" here.
    String text = "😀 Kappa";
    int[] offsets = CodePoints.offsets(text);

    assertEquals(8, offsets.length);
    assertEquals("Kappa", text.substring(offsets[2], offsets[6 + 1]));
  }

  @Test
  void emptyTextHasOnlyTheEnd() {
    assertArrayEquals(new int[]{0}, CodePoints.offsets(""));
  }
}
