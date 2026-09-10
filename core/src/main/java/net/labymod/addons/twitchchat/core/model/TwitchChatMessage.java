package net.labymod.addons.twitchchat.core.model;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.labymod.api.client.component.Component;

/**
 * A single line that ends up in the Twitch chat tab. Either a regular chat message, an
 * {@code /me} action, a channel event (sub, raid, ...) or an addon status message.
 */
public final class TwitchChatMessage {

  public enum Kind {
    CHAT,
    ACTION,
    EVENT,
    SYSTEM
  }

  private final String id;
  private final Kind kind;
  private final String channel;
  private final String userId;
  private final String login;
  private final String displayName;
  private final int color;
  private final List<TwitchBadge> badges;
  private final List<TwitchEmoteRange> emotes;
  private final String text;
  private final Component component;
  private final String eventText;
  private final long timestamp;
  private final boolean self;

  private TwitchChatMessage(Builder builder) {
    this.id = builder.id == null ? UUID.randomUUID().toString() : builder.id;
    this.kind = builder.kind;
    this.channel = builder.channel;
    this.userId = builder.userId;
    this.login = builder.login;
    this.displayName = builder.displayName == null ? builder.login : builder.displayName;
    this.color = builder.color;
    this.badges = builder.badges == null ? Collections.emptyList() : List.copyOf(builder.badges);
    this.emotes = builder.emotes == null ? Collections.emptyList() : List.copyOf(builder.emotes);
    this.text = builder.text == null ? "" : builder.text;
    this.component = builder.component;
    this.eventText = builder.eventText;
    this.timestamp = builder.timestamp <= 0 ? System.currentTimeMillis() : builder.timestamp;
    this.self = builder.self;
  }

  public static Builder builder(Kind kind) {
    return new Builder(kind);
  }

  public static TwitchChatMessage system(String text) {
    return builder(Kind.SYSTEM).text(text).build();
  }

  /**
   * A status line whose text is already a component, so translations resolve when the line is
   * rendered rather than when it is created.
   */
  public static TwitchChatMessage system(Component component) {
    return builder(Kind.SYSTEM).component(component).build();
  }

  public String id() {
    return this.id;
  }

  public Kind kind() {
    return this.kind;
  }

  public String channel() {
    return this.channel;
  }

  public String userId() {
    return this.userId;
  }

  public String login() {
    return this.login;
  }

  public String displayName() {
    return this.displayName;
  }

  /**
   * @return the RGB color Twitch assigned to the user or {@code -1} if the user never picked one
   */
  public int color() {
    return this.color;
  }

  public List<TwitchBadge> badges() {
    return this.badges;
  }

  public List<TwitchEmoteRange> emotes() {
    return this.emotes;
  }

  public String text() {
    return this.text;
  }

  /**
   * @return the prebuilt body of the line, or {@code null} when it is plain {@link #text()}
   */
  public Component component() {
    return this.component;
  }

  public String eventText() {
    return this.eventText;
  }

  public long timestamp() {
    return this.timestamp;
  }

  public boolean isSelf() {
    return this.self;
  }

  public boolean hasUser() {
    return this.login != null && !this.login.isEmpty();
  }

  public static final class Builder {

    private final Kind kind;
    private String id;
    private String channel;
    private String userId;
    private String login;
    private String displayName;
    private int color = -1;
    private List<TwitchBadge> badges;
    private List<TwitchEmoteRange> emotes;
    private String text;
    private Component component;
    private String eventText;
    private long timestamp;
    private boolean self;

    private Builder(Kind kind) {
      this.kind = kind;
    }

    public Builder id(String id) {
      this.id = id;
      return this;
    }

    public Builder channel(String channel) {
      this.channel = channel;
      return this;
    }

    public Builder userId(String userId) {
      this.userId = userId;
      return this;
    }

    public Builder login(String login) {
      this.login = login;
      return this;
    }

    public Builder displayName(String displayName) {
      this.displayName = displayName;
      return this;
    }

    public Builder color(int color) {
      this.color = color;
      return this;
    }

    public Builder badges(List<TwitchBadge> badges) {
      this.badges = badges;
      return this;
    }

    public Builder emotes(List<TwitchEmoteRange> emotes) {
      this.emotes = emotes;
      return this;
    }

    public Builder text(String text) {
      this.text = text;
      return this;
    }

    public Builder component(Component component) {
      this.component = component;
      return this;
    }

    public Builder eventText(String eventText) {
      this.eventText = eventText;
      return this;
    }

    public Builder timestamp(long timestamp) {
      this.timestamp = timestamp;
      return this;
    }

    public Builder self(boolean self) {
      this.self = self;
      return this;
    }

    public TwitchChatMessage build() {
      return new TwitchChatMessage(this);
    }
  }
}
