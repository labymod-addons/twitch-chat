package net.labymod.addons.twitchchat.core.irc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class IrcLineTest {

  @Test
  void privateMessageKeepsItsText() {
    IrcLine line = IrcLine.parse(
        "@display-name=Mira;user-id=42 :mira!mira@mira.tmi.twitch.tv PRIVMSG #craftycarla :hi there");

    assertEquals("PRIVMSG", line.command());
    assertEquals("mira", line.nick());
    assertEquals("craftycarla", line.channel());
    assertEquals("hi there", line.trailing());
    assertEquals("Mira", line.tag("display-name"));
  }

  @Test
  void raidWithoutMessageHasNoTrailing() {
    IrcLine line = IrcLine.parse("@msg-id=raid;system-msg=12\\sraiders\\sfrom\\smira_plays\\shave\\sjoined!"
        + " :tmi.twitch.tv USERNOTICE #craftycarla");

    assertEquals(List.of("#craftycarla"), line.params());
    assertEquals("craftycarla", line.channel());
    assertNull(line.trailing());
    assertEquals("12 raiders from mira_plays have joined!", line.tag("system-msg"));
  }

  @Test
  void resubWithMessageKeepsItsText() {
    IrcLine line = IrcLine.parse(
        "@msg-id=resub :tmi.twitch.tv USERNOTICE #craftycarla :still here after a year");

    assertEquals("still here after a year", line.trailing());
  }

  @Test
  void emptyTrailingIsKeptAsEmptyText() {
    IrcLine line = IrcLine.parse(":tmi.twitch.tv USERNOTICE #craftycarla :");

    assertEquals("", line.trailing());
  }

  @Test
  void clearChatTellsWholeChatFromSingleUser() {
    IrcLine all = IrcLine.parse(":tmi.twitch.tv CLEARCHAT #craftycarla");
    IrcLine user = IrcLine.parse(":tmi.twitch.tv CLEARCHAT #craftycarla :spammer");

    assertNull(all.trailing());
    assertEquals(1, all.params().size());
    assertEquals("spammer", user.trailing());
  }

  @Test
  void commandWithoutParameters() {
    IrcLine line = IrcLine.parse("RECONNECT");

    assertEquals("RECONNECT", line.command());
    assertNull(line.trailing());
  }
}
