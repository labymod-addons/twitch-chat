# Twitch Chat for LabyMod 4

Follow and write in a Twitch chat directly inside Minecraft.

## What it does

- **Own chat tab.** The Twitch chat appears as a "Twitch" tab in the LabyMod chat window. Game
  chat never leaks into that tab and Twitch messages never leak into the game tabs. Anything you
  type while the Twitch tab is open goes to Twitch, not to the Minecraft server.
- **Badges, colors, emotes.** Broadcaster, moderator, VIP, subscriber and the other global badges
  are rendered inline, names use the user's Twitch color, Twitch emotes and (optionally) BetterTTV
  and FrankerFaceZ emotes are rendered as inline images. Links are clickable.
- **Channel events.** Subscriptions, resubs, gifts, raids and other `USERNOTICE` events show up as
  highlighted lines; timeouts, bans and message deletions are applied to the tab the way Twitch
  applies them.
- **Mentions.** Messages that mention your Twitch login or your Minecraft name are highlighted.
- **HUD widget.** A "Twitch Chat" widget shows the newest messages while playing, without opening
  the chat. Width, message count and auto-hide are configurable in the widget editor.
- **Read without an account.** Reading works anonymously. Writing needs a Twitch login via the
  device code flow: the addon prints a short code, you enter it on `twitch.tv/activate`.
- **Channel fallback.** With no channel configured, the Twitch account linked on laby.net is used.

## Commands

| Command | Effect |
| --- | --- |
| `/twitch join <channel>` | Join a channel (also updates the setting) |
| `/twitch leave` | Disconnect |
| `/twitch connect` | Reconnect to the configured channel |
| `/twitch login` / `/twitch logout` | Start the Twitch login / drop the session |
| `/twitch status` | Connection and login state |
| `/twitch say <message>` or `/tw <message>` | Write into the channel from any tab |

## Twitch application (needed for writing)

Reading needs nothing. To let players log in, register a Twitch application at
<https://dev.twitch.tv/console/apps>:

- Client type **Public** (the device code flow does not use a client secret)
- Any OAuth redirect URL (required by the form, unused by the device flow)
- Category "Game Integration"

The id of that application is compiled in as `TwitchChatController.DEFAULT_CLIENT_ID` and can be
overridden in the addon's advanced settings. The requested scopes are `chat:read chat:edit`.

Tokens are stored in the addon config (`labymod-neo/configs/twitchchat/settings.json`) and
refreshed automatically when Twitch rejects them.

## Development

```bash
./gradlew build            # compile
./gradlew createReleaseJar # build/libs/twitch-chat-release.jar
./gradlew :game-runner:client_v26.1.2   # dev client with the addon
```

Java 21 is the source level; the Gradle build needs a JDK 21 or newer.

### Layout

```
core/src/main/java/net/labymod/addons/twitchchat/core
├── TwitchChatAddon.java          entry point, wiring
├── auth/TwitchAuth.java          device code flow, validate, refresh
├── chat/TwitchChatController.java  connection + session state, IRC event handling, output
├── chat/TwitchChatTab.java       the temporary "Twitch" chat tab and message deletion
├── chat/TwitchComponentBuilder.java  message -> Component (badges, emotes, links, mentions)
├── command/TwitchCommand.java    /twitch and /tw
├── configuration/                addon settings
├── emote/                        Twitch badges (Helix + built-in defaults), BTTV/FFZ emotes
├── hud/                          the Twitch Chat HUD widget
├── irc/                          TLS IRC client and IRCv3 line parser
└── model/, util/                 message model, colors, HTTP helper
```

The IRC connection is a plain TLS socket to `irc.chat.twitch.tv:6697` (no WebSocket, no extra
dependencies). All UI work is hopped onto the render thread.
