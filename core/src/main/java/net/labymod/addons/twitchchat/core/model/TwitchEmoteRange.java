package net.labymod.addons.twitchchat.core.model;

/**
 * Position of a Twitch emote inside a message. Start and end are inclusive code point indices,
 * exactly as Twitch sends them in the {@code emotes} tag.
 */
public record TwitchEmoteRange(String id, int start, int end) {

  public static final String CDN = "https://static-cdn.jtvnw.net/emoticons/v2/%s/default/dark/1.0";

  public String url() {
    return String.format(CDN, this.id);
  }
}
