package net.labymod.addons.twitchchat.core.model;

/**
 * A badge as sent in the IRC {@code badges} tag, e.g. {@code subscriber/12}.
 */
public record TwitchBadge(String set, String version) {

  public String key() {
    return this.set + "/" + this.version;
  }
}
