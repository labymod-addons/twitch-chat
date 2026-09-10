package net.labymod.addons.twitchchat.core.listener;

import net.labymod.addons.twitchchat.core.chat.TwitchChatController;
import net.labymod.api.event.Phase;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.lifecycle.GameTickEvent;

public final class TickListener {

  private static final int INTERVAL = 20;

  private final TwitchChatController controller;
  private int ticks;

  public TickListener(TwitchChatController controller) {
    this.controller = controller;
  }

  @Subscribe
  public void onTick(GameTickEvent event) {
    if (event.phase() != Phase.PRE) {
      return;
    }
    if (++this.ticks < INTERVAL) {
      return;
    }
    this.ticks = 0;
    this.controller.tick();
  }
}
