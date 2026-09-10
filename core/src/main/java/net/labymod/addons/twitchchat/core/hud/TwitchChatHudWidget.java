package net.labymod.addons.twitchchat.core.hud;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.labymod.addons.twitchchat.core.chat.TwitchChatController;
import net.labymod.addons.twitchchat.core.chat.TwitchChatController.HistoryEntry;
import net.labymod.addons.twitchchat.core.chat.TwitchComponentBuilder;
import net.labymod.addons.twitchchat.core.model.TwitchChatMessage;
import net.labymod.addons.twitchchat.core.model.TwitchChatMessage.Kind;
import net.labymod.api.Laby;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.format.NamedTextColor;
import net.labymod.api.client.gfx.pipeline.renderer.text.TextRenderingOptions;
import net.labymod.api.client.gui.hud.hudwidget.background.BackgroundHudWidget;
import net.labymod.api.client.gui.hud.position.HudSize;
import net.labymod.api.client.gui.icon.Icon;
import net.labymod.api.client.gui.screen.ScreenContext;
import net.labymod.api.client.gui.screen.state.ScreenCanvas;
import net.labymod.api.client.render.font.RenderableComponent;
import net.labymod.api.client.resources.ResourceLocation;
import net.labymod.api.util.bounds.area.RectangleAreaPosition;

/**
 * Overlay that shows the newest Twitch messages while playing, without opening the chat.
 */
public final class TwitchChatHudWidget extends BackgroundHudWidget<TwitchChatHudWidgetConfig> {

  private static final float LINE_HEIGHT = 10.0F;

  private final TwitchChatController controller;
  private final Map<Component, CachedLines> cache = new IdentityHashMap<>();
  private List<Component> preview;

  public TwitchChatHudWidget(TwitchChatController controller) {
    super("twitch_chat", TwitchChatHudWidgetConfig.class);
    this.controller = controller;
    this.setIcon(Icon.texture(ResourceLocation.create("twitchchat", "textures/icon.png")));
  }

  @Override
  public void initializePreConfigured(TwitchChatHudWidgetConfig config) {
    super.initializePreConfigured(config);
    config.setEnabled(true);
    config.setX(2);
    config.setY(2);
    config.setAreaIdentifier(RectangleAreaPosition.MIDDLE_RIGHT);
    config.setParentToTailOfChainIn(RectangleAreaPosition.MIDDLE_RIGHT);
  }

  @Override
  public boolean isVisibleInGame() {
    return this.controller.isEnabled()
        && (this.controller.isConnected() || !this.controller.history().isEmpty());
  }

  @Override
  public void render(RenderPhase phase, ScreenContext context, boolean isEditorContext,
      HudSize size) {
    float width = this.config.width().get();
    List<RenderableComponent> lines = this.collectLines(isEditorContext, width);
    float height = Math.max(LINE_HEIGHT, lines.size() * LINE_HEIGHT);

    if (!phase.canRender()) {
      size.set(width, height);
      return;
    }

    super.render(phase, context, isEditorContext, size);
    ScreenCanvas canvas = context.canvas();
    float y = 0;
    for (RenderableComponent line : lines) {
      canvas.submitRenderableComponent(line, 0, y, -1, TextRenderingOptions.SHADOW);
      y += LINE_HEIGHT;
    }
  }

  @SuppressWarnings("deprecation")
  private List<RenderableComponent> collectLines(boolean isEditorContext, float width) {
    List<Component> components = isEditorContext ? this.preview() : this.recent();
    List<RenderableComponent> lines = new ArrayList<>();
    for (Component component : components) {
      CachedLines cached = this.cache.get(component);
      if (cached == null || cached.width != width) {
        List<Component> split = Laby.labyAPI().renderPipeline().componentRenderer()
            .split(component, width);
        List<RenderableComponent> renderable = new ArrayList<>(split.size());
        for (Component line : split) {
          renderable.add(RenderableComponent.of(line));
        }
        cached = new CachedLines(width, renderable);
        this.cache.put(component, cached);
      }
      lines.addAll(cached.lines);
    }

    // Drop cache entries of messages that left the history.
    if (this.cache.size() > TwitchChatController.HISTORY_SIZE * 2) {
      this.cache.keySet().retainAll(components);
    }
    return lines;
  }

  private List<Component> recent() {
    int max = this.config.maxMessages().get();
    long hideAfter = this.config.hideAfter().get() * 1000L;
    long now = System.currentTimeMillis();
    List<HistoryEntry> history = this.controller.history();
    List<Component> result = new ArrayList<>(max);
    for (int i = history.size() - 1; i >= 0 && result.size() < max; i--) {
      HistoryEntry entry = history.get(i);
      if (hideAfter > 0 && now - entry.message().timestamp() > hideAfter) {
        break;
      }
      result.add(0, entry.component());
    }
    return result;
  }

  private List<Component> preview() {
    if (this.preview == null) {
      this.preview = List.of(
          this.sample("Streamer", 0x9146FF, "Welcome to the stream!"),
          this.sample("Viewer42", 0x1E90FF, "GG that clutch was insane"),
          this.sample("ModSquad", 0x00FF7F, "remember to follow :)")
      );
    }
    return this.preview;
  }

  private Component sample(String name, int color, String text) {
    return Component.text(name, net.labymod.api.client.component.format.TextColor.color(color))
        .append(Component.text(": ", NamedTextColor.GRAY))
        .append(Component.text(text, NamedTextColor.WHITE));
  }

  private record CachedLines(float width, List<RenderableComponent> lines) {

  }
}
