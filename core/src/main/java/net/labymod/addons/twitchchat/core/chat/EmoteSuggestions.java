package net.labymod.addons.twitchchat.core.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.labymod.addons.twitchchat.core.configuration.TwitchChatConfiguration;
import net.labymod.addons.twitchchat.core.emote.ThirdPartyEmoteService.Suggestion;
import net.labymod.api.Laby;
import net.labymod.api.client.chat.ChatExecutor;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.format.NamedTextColor;
import net.labymod.api.client.gfx.pipeline.renderer.text.TextRenderingOptions;
import net.labymod.api.client.gui.icon.Icon;
import net.labymod.api.client.gui.screen.ScreenContext;
import net.labymod.api.client.gui.screen.state.ScreenCanvas;
import net.labymod.api.client.gui.window.Window;
import net.labymod.api.client.render.font.RenderableComponent;

/**
 * Emote autocomplete for the chat input. It completes the word in front of the cursor with a
 * channel or global emote code, the way Twitch itself does: type a few characters, pick with the
 * arrow keys, insert with Tab.
 */
public final class EmoteSuggestions {

  private static final int MAX_ENTRIES = 8;
  private static final int MIN_PREFIX = 2;

  private static final float LINE_HEIGHT = 11.0F;
  private static final float PADDING = 2.0F;
  private static final float ICON_SIZE = 9.0F;
  private static final float ICON_GAP = 3.0F;
  private static final float LEFT = 2.0F;
  /**
   * The advanced chat pins its input box 2 units above the bottom edge and gives it a height of
   * 12 (activity/chat/input/chat-input.lss), so the box starts 14 units above the bottom.
   */
  private static final float INPUT_HEIGHT = 14.0F;
  private static final float INPUT_GAP = 2.0F;

  private static final int BACKGROUND = 0xD0000000;
  private static final int SELECTION = 0x809146FF;

  private final TwitchChatController controller;
  private final TwitchChatConfiguration config;

  private String input;
  private String linePrefix = "";
  private List<Entry> entries = List.of();
  private int selected;
  private boolean dismissed;

  public EmoteSuggestions(TwitchChatController controller, TwitchChatConfiguration config) {
    this.controller = controller;
    this.config = config;
  }

  public void render(ScreenContext context) {
    if (!this.update()) {
      return;
    }

    float labelWidth = 0;
    for (Entry entry : this.entries) {
      labelWidth = Math.max(labelWidth, entry.width());
    }
    float width = PADDING * 2 + ICON_SIZE + ICON_GAP + labelWidth;
    float height = PADDING * 2 + this.entries.size() * LINE_HEIGHT;

    Window window = Laby.labyAPI().minecraft().minecraftWindow();
    float top = window.getScaledHeight() - INPUT_HEIGHT - INPUT_GAP - height;

    ScreenCanvas canvas = context.canvas();
    canvas.submitRelativeRect(LEFT, top, width, height, BACKGROUND);

    float y = top + PADDING;
    for (int i = 0; i < this.entries.size(); i++) {
      Entry entry = this.entries.get(i);
      if (i == this.selected) {
        canvas.submitRelativeRect(LEFT + 1, y, width - 2, LINE_HEIGHT, SELECTION);
      }
      canvas.submitIcon(entry.icon(), LEFT + PADDING, y + 1, ICON_SIZE, ICON_SIZE);
      canvas.submitRenderableComponent(
          entry.label(),
          LEFT + PADDING + ICON_SIZE + ICON_GAP,
          y + 2,
          -1,
          TextRenderingOptions.SHADOW
      );
      y += LINE_HEIGHT;
    }
  }

  /**
   * @return true if there is something to draw
   */
  public boolean isOpen() {
    return this.update();
  }

  public void move(int delta) {
    if (this.entries.isEmpty()) {
      return;
    }
    int size = this.entries.size();
    this.selected = ((this.selected + delta) % size + size) % size;
  }

  public void accept() {
    if (this.entries.isEmpty()) {
      return;
    }
    String completed = this.linePrefix + this.entries.get(this.selected).code() + " ";
    this.clear();
    // Replaces the whole line and puts the cursor at the end, which is where the completed word
    // sits anyway.
    Laby.labyAPI().minecraft().chatExecutor().suggestCommand(completed);
  }

  public void dismiss() {
    this.dismissed = true;
    this.clear();
  }

  /**
   * Recomputes the matches whenever the chat input changed.
   *
   * @return true if suggestions should be shown for the current input
   */
  private boolean update() {
    String text = Laby.labyAPI().minecraft().chatExecutor().getChatInputMessage();
    if (text == null || !this.isEligible(text)) {
      this.input = null;
      this.dismissed = false;
      this.clear();
      return false;
    }

    if (!text.equals(this.input)) {
      this.input = text;
      // Any edit reopens the list that an earlier escape closed.
      this.dismissed = false;
      this.recompute(text);
    }
    return !this.dismissed && !this.entries.isEmpty();
  }

  private void recompute(String text) {
    int start = text.lastIndexOf(' ') + 1;
    String word = text.substring(start);
    if (word.length() < MIN_PREFIX || word.charAt(0) == '/' || word.charAt(0) == '@') {
      this.clear();
      return;
    }

    List<Suggestion> found = this.controller.emotes()
        .suggest(word, this.controller.roomId(), MAX_ENTRIES);
    if (found.isEmpty()) {
      this.clear();
      return;
    }

    List<Entry> built = new ArrayList<>(found.size());
    for (Suggestion suggestion : found) {
      Component label = Component.text(suggestion.code(), NamedTextColor.WHITE);
      built.add(new Entry(
          suggestion.code(),
          Icon.url(suggestion.url()),
          RenderableComponent.of(label),
          Laby.labyAPI().renderPipeline().componentRenderer().width(label)
      ));
    }

    this.linePrefix = text.substring(0, start);
    this.entries = built;
    this.selected = 0;
  }

  /**
   * Autocomplete only helps where the text actually goes to Twitch: the Twitch tab, or the
   * {@code /tw} shorthand from any other tab.
   */
  private boolean isEligible(String text) {
    if (!this.controller.isEnabled() || !this.config.emoteAutoComplete().get()
        || !this.config.thirdPartyEmotes().get()) {
      return false;
    }
    if (this.controller.tab().isActive()) {
      return true;
    }
    String lower = text.toLowerCase(Locale.ROOT);
    return lower.startsWith("/tw ") || lower.startsWith("/twitch say ");
  }

  private void clear() {
    this.entries = List.of();
    this.linePrefix = "";
    this.selected = 0;
  }

  private record Entry(String code, Icon icon, RenderableComponent label, float width) {

  }
}
