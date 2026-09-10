package net.labymod.addons.twitchchat.core.emote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import net.labymod.addons.twitchchat.core.model.TwitchBadge;
import net.labymod.addons.twitchchat.core.util.SimpleHttp;
import net.labymod.addons.twitchchat.core.util.SimpleHttp.Response;
import net.labymod.api.util.logging.Logging;

/**
 * Resolves badge tags to image URLs. Ships with the well-known global badges so anonymous
 * viewers see moderator/VIP/subscriber marks, and loads the exact global and channel badge sets
 * from Helix as soon as a user token is available.
 */
public final class TwitchBadgeService {

  private static final String BADGE_CDN = "https://static-cdn.jtvnw.net/badges/v1/%s/1";
  private static final String GLOBAL_URL = "https://api.twitch.tv/helix/chat/badges/global";
  private static final String CHANNEL_URL = "https://api.twitch.tv/helix/chat/badges?broadcaster_id=%s";

  private final Logging logger;
  private final ExecutorService executor;
  private final Map<String, String> globalBadges = new ConcurrentHashMap<>();
  private final Map<String, Map<String, String>> channelBadges = new ConcurrentHashMap<>();
  private volatile boolean globalLoadedFromHelix;

  public TwitchBadgeService(Logging logger, ExecutorService executor) {
    this.logger = logger;
    this.executor = executor;
    this.registerDefaults();
  }

  private void registerDefaults() {
    this.put("broadcaster/1", "5527c58c-fb7d-422d-b71b-f309dcb85cc1");
    this.put("moderator/1", "3267646d-33f0-4b17-b3df-f923a41db1d0");
    this.put("vip/1", "b817aba4-fad8-49e2-b88a-7cc744dfa6ec");
    this.put("subscriber/0", "5d9f2208-5dd8-11e7-8513-2ff4adfae661");
    this.put("founder/0", "511b78a9-ab37-472f-9569-457753bbe7d3");
    this.put("partner/1", "d12a2e27-16f6-41d0-ab77-b780518f00a3");
    this.put("admin/1", "9ef7e029-4cdf-4d4d-a0d5-e2b3fb2583fe");
    this.put("global_mod/1", "9384c43e-4ce7-4e94-b2a1-b93656896eba");
    this.put("turbo/1", "bd444ec6-8f34-4bf9-91f4-af1e3428d80f");
    this.put("premium/1", "bbbe0db0-a598-423e-86d0-f9fb98ca1933");
    this.put("artist-badge/1", "4300a897-03dc-4e83-8c0e-c332fee7057f");
    this.put("game-developer/1", "85856a4a-eb7d-4e26-a43e-d204a977ade4");
    this.put("no_audio/1", "aef2cd08-f29b-45a1-8c12-d44d7fd5e6f0");
    this.put("no_video/1", "199a0dba-58f3-494e-a7fc-1fa0a1001fb8");
    this.put("bits/1", "73b5c3fb-24f9-4a82-a852-2f475b59411c");
    this.put("bits/100", "09d93036-e7ce-431c-9a9e-7044297133f2");
    this.put("bits/1000", "0d85a29e-79ad-4c63-a285-3acd2c66f2ba");
    this.put("sub-gifter/1", "a5ef6c17-2e5b-4d8f-9b80-2779fd722414");
    this.put("hype-train/1", "fae4086c-3190-44d4-83c8-8ef0cbe1a515");
    this.put("ambassador/1", "2cbc339f-34f4-488a-ae51-efdf74f4e323");
  }

  private void put(String key, String uuid) {
    this.globalBadges.put(key, String.format(BADGE_CDN, uuid));
  }

  /**
   * @return the image URL for the badge or {@code null} if it is unknown
   */
  public String url(TwitchBadge badge, String roomId) {
    String key = badge.key();
    if (roomId != null) {
      Map<String, String> channel = this.channelBadges.get(roomId);
      if (channel != null) {
        String url = channel.get(key);
        if (url != null) {
          return url;
        }
      }
    }

    String url = this.globalBadges.get(key);
    if (url != null) {
      return url;
    }

    // Unknown version of a known set (e.g. subscriber/24 without channel data): show the
    // base version so the viewer still sees the mark.
    return switch (badge.set()) {
      case "subscriber" -> this.globalBadges.get("subscriber/0");
      case "founder" -> this.globalBadges.get("founder/0");
      case "bits" -> this.globalBadges.get("bits/1");
      case "sub-gifter" -> this.globalBadges.get("sub-gifter/1");
      default -> null;
    };
  }

  public void loadGlobal(String clientId, String token) {
    if (this.globalLoadedFromHelix || clientId == null || token == null) {
      return;
    }
    this.executor.execute(() -> {
      try {
        Map<String, String> loaded = this.fetch(GLOBAL_URL, clientId, token);
        this.globalBadges.putAll(loaded);
        this.globalLoadedFromHelix = true;
        this.logger.info("Loaded {} global Twitch badges", loaded.size());
      } catch (IOException e) {
        this.logger.warn("Could not load global Twitch badges: {}", e.getMessage());
      }
    });
  }

  public void loadChannel(String roomId, String clientId, String token) {
    if (roomId == null || clientId == null || token == null
        || this.channelBadges.containsKey(roomId)) {
      return;
    }
    this.executor.execute(() -> {
      try {
        Map<String, String> loaded = this.fetch(String.format(CHANNEL_URL, roomId), clientId, token);
        this.channelBadges.put(roomId, loaded);
        this.logger.info("Loaded {} channel badges for room {}", loaded.size(), roomId);
      } catch (IOException e) {
        this.logger.warn("Could not load channel badges for {}: {}", roomId, e.getMessage());
      }
    });
  }

  private Map<String, String> fetch(String url, String clientId, String token) throws IOException {
    Response response = SimpleHttp.get(url, Map.of(
        "Client-Id", clientId,
        "Authorization", "Bearer " + token
    ));
    if (!response.isOk()) {
      throw new IOException("HTTP " + response.status());
    }
    Map<String, String> result = new ConcurrentHashMap<>();
    JsonElement data = response.json().get("data");
    if (!(data instanceof JsonArray sets)) {
      return result;
    }
    for (JsonElement setElement : sets) {
      JsonObject set = setElement.getAsJsonObject();
      String setId = set.get("set_id").getAsString();
      JsonElement versions = set.get("versions");
      if (!(versions instanceof JsonArray versionArray)) {
        continue;
      }
      for (JsonElement versionElement : versionArray) {
        JsonObject version = versionElement.getAsJsonObject();
        String id = version.get("id").getAsString();
        JsonElement image = version.get("image_url_1x");
        if (image != null && !image.isJsonNull()) {
          result.put(setId + "/" + id, image.getAsString());
        }
      }
    }
    return result;
  }
}
