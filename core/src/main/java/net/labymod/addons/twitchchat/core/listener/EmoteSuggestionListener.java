package net.labymod.addons.twitchchat.core.listener;

import net.labymod.addons.twitchchat.core.chat.EmoteSuggestions;
import net.labymod.api.client.gui.screen.key.Key;
import net.labymod.api.event.Phase;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.input.KeyEvent;
import net.labymod.api.event.client.input.KeyEvent.State;
import net.labymod.api.event.client.render.ScreenRenderEvent;

/**
 * Draws the emote suggestions over the open chat screen and takes the keys that drive them away
 * from the chat input while the list is up.
 */
public final class EmoteSuggestionListener {

  private final EmoteSuggestions suggestions;

  public EmoteSuggestionListener(EmoteSuggestions suggestions) {
    this.suggestions = suggestions;
  }

  @Subscribe
  public void onScreenRender(ScreenRenderEvent event) {
    if (event.phase() == Phase.POST) {
      this.suggestions.render(event.screenContext());
    }
  }

  @Subscribe
  public void onKey(KeyEvent event) {
    if (event.state() == State.UNPRESSED || !this.suggestions.isOpen()) {
      return;
    }

    Key key = event.key();
    if (key == Key.TAB) {
      this.suggestions.accept();
    } else if (key == Key.ARROW_DOWN) {
      this.suggestions.move(1);
    } else if (key == Key.ARROW_UP) {
      this.suggestions.move(-1);
    } else if (key == Key.ESCAPE) {
      // The first escape only closes the list, a second one closes the chat.
      this.suggestions.dismiss();
    } else {
      return;
    }
    event.setCancelled(true);
  }
}
