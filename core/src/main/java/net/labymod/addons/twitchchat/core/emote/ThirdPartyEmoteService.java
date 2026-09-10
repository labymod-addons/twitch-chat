package net.labymod.addons.twitchchat.core.emote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import net.labymod.addons.twitchchat.core.util.SimpleHttp;
import net.labymod.addons.twitchchat.core.util.SimpleHttp.Response;
import net.labymod.api.util.logging.Logging;

/**
 * BetterTTV and FrankerFaceZ emotes (global + per channel). Both serve plain PNGs that the
 * client's texture loader understands; animated BTTV emotes are skipped on purpose.
 */
public final class ThirdPartyEmoteService {

  private static final String BTTV_GLOBAL = "https://api.betterttv.net/3/cached/emotes/global";
  private static final String BTTV_CHANNEL = "https://api.betterttv.net/3/cached/users/twitch/%s";
  private static final String BTTV_CDN = "https://cdn.betterttv.net/emote/%s/1x.png";
  private static final String FFZ_GLOBAL = "https://api.frankerfacez.com/v1/set/global";
  private static final String FFZ_CHANNEL = "https://api.frankerfacez.com/v1/room/id/%s";

  private final Logging logger;
  private final ExecutorService executor;
  private final Map<String, String> global = new ConcurrentHashMap<>();
  private final Map<String, Map<String, String>> channels = new ConcurrentHashMap<>();
  private volatile boolean globalLoaded;

  public ThirdPartyEmoteService(Logging logger, ExecutorService executor) {
    this.logger = logger;
    this.executor = executor;
  }

  public boolean hasAny() {
    return !this.global.isEmpty() || !this.channels.isEmpty();
  }

  /**
   * @return the image URL for the emote code or {@code null}
   */
  public String url(String code, String roomId) {
    if (roomId != null) {
      Map<String, String> channel = this.channels.get(roomId);
      if (channel != null) {
        String url = channel.get(code);
        if (url != null) {
          return url;
        }
      }
    }
    return this.global.get(code);
  }

  public void loadGlobal() {
    if (this.globalLoaded) {
      return;
    }
    this.globalLoaded = true;
    this.executor.execute(() -> {
      Map<String, String> loaded = new HashMap<>();
      try {
        this.readBttv(SimpleHttp.get(BTTV_GLOBAL, null).element(), loaded);
      } catch (Exception e) {
        this.logger.warn("Could not load global BTTV emotes: {}", e.getMessage());
      }
      try {
        this.readFfz(SimpleHttp.get(FFZ_GLOBAL, null).json(), loaded, true);
      } catch (Exception e) {
        this.logger.warn("Could not load global FFZ emotes: {}", e.getMessage());
      }
      this.global.putAll(loaded);
      this.logger.info("Loaded {} global third-party emotes", loaded.size());
    });
  }

  public void loadChannel(String roomId) {
    if (roomId == null || this.channels.containsKey(roomId)) {
      return;
    }
    this.channels.put(roomId, new ConcurrentHashMap<>());
    this.executor.execute(() -> {
      Map<String, String> loaded = new HashMap<>();
      try {
        Response response = SimpleHttp.get(String.format(BTTV_CHANNEL, roomId), null);
        if (response.isOk()) {
          JsonObject json = response.json();
          this.readBttv(json.get("channelEmotes"), loaded);
          this.readBttv(json.get("sharedEmotes"), loaded);
        }
      } catch (Exception e) {
        this.logger.warn("Could not load BTTV emotes for room {}: {}", roomId, e.getMessage());
      }
      try {
        Response response = SimpleHttp.get(String.format(FFZ_CHANNEL, roomId), null);
        if (response.isOk()) {
          this.readFfz(response.json(), loaded, false);
        }
      } catch (Exception e) {
        this.logger.warn("Could not load FFZ emotes for room {}: {}", roomId, e.getMessage());
      }
      this.channels.get(roomId).putAll(loaded);
      this.logger.info("Loaded {} third-party emotes for room {}", loaded.size(), roomId);
    });
  }

  private void readBttv(JsonElement element, Map<String, String> target) {
    if (!(element instanceof JsonArray array)) {
      return;
    }
    for (JsonElement entry : array) {
      JsonObject emote = entry.getAsJsonObject();
      if (emote.has("animated") && emote.get("animated").getAsBoolean()) {
        continue;
      }
      String type = emote.has("imageType") ? emote.get("imageType").getAsString() : "png";
      if (!"png".equalsIgnoreCase(type)) {
        continue;
      }
      target.put(emote.get("code").getAsString(), String.format(BTTV_CDN, emote.get("id").getAsString()));
    }
  }

  private void readFfz(JsonObject json, Map<String, String> target, boolean onlyDefaultSets) {
    JsonElement setsElement = json.get("sets");
    if (setsElement == null || !setsElement.isJsonObject()) {
      return;
    }
    JsonObject sets = setsElement.getAsJsonObject();
    JsonArray defaults = onlyDefaultSets && json.get("default_sets") instanceof JsonArray array
        ? array : null;
    for (Map.Entry<String, JsonElement> setEntry : sets.entrySet()) {
      if (defaults != null && !defaults.contains(new com.google.gson.JsonPrimitive(
          Integer.parseInt(setEntry.getKey())))) {
        continue;
      }
      JsonElement emoticons = setEntry.getValue().getAsJsonObject().get("emoticons");
      if (!(emoticons instanceof JsonArray array)) {
        continue;
      }
      for (JsonElement entry : array) {
        JsonObject emote = entry.getAsJsonObject();
        JsonElement urls = emote.get("urls");
        if (urls == null || !urls.isJsonObject() || !urls.getAsJsonObject().has("1")) {
          continue;
        }
        String url = urls.getAsJsonObject().get("1").getAsString();
        if (url.startsWith("//")) {
          url = "https:" + url;
        }
        target.put(emote.get("name").getAsString(), url);
      }
    }
  }
}
