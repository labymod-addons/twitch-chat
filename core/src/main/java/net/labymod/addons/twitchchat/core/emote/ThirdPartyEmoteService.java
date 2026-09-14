package net.labymod.addons.twitchchat.core.emote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import net.labymod.addons.twitchchat.core.util.SimpleHttp;
import net.labymod.addons.twitchchat.core.util.SimpleHttp.Response;
import net.labymod.api.util.logging.Logging;

/**
 * BetterTTV, FrankerFaceZ and 7TV emotes (global + per channel). Every provider is asked for a
 * still image: the client decodes PNG and static WebP but not animated WebP, and an animated GIF
 * would end up as its first frame anyway.
 */
public final class ThirdPartyEmoteService {

  private static final String BTTV_GLOBAL = "https://api.betterttv.net/3/cached/emotes/global";
  private static final String BTTV_CHANNEL = "https://api.betterttv.net/3/cached/users/twitch/%s";
  private static final String BTTV_CDN = "https://cdn.betterttv.net/emote/%s/1x.png";
  private static final String FFZ_GLOBAL = "https://api.frankerfacez.com/v1/set/global";
  private static final String FFZ_CHANNEL = "https://api.frankerfacez.com/v1/room/id/%s";
  private static final String SEVENTV_GLOBAL = "https://7tv.io/v3/emote-sets/global";
  private static final String SEVENTV_CHANNEL = "https://7tv.io/v3/users/twitch/%s";

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

  /**
   * Codes starting with the prefix, case insensitively. Channel emotes come before global ones,
   * each block sorted by code.
   *
   * @param limit the maximum number of results
   */
  public List<Suggestion> suggest(String prefix, String roomId, int limit) {
    if (prefix.isEmpty() || limit <= 0) {
      return List.of();
    }

    List<Suggestion> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    Map<String, String> channel = roomId == null ? null : this.channels.get(roomId);
    if (channel != null) {
      collect(channel, prefix, seen, result);
    }
    collect(this.global, prefix, seen, result);
    return result.size() > limit ? List.copyOf(result.subList(0, limit)) : List.copyOf(result);
  }

  private static void collect(
      Map<String, String> source,
      String prefix,
      Set<String> seen,
      List<Suggestion> target
  ) {
    List<Suggestion> matches = new ArrayList<>();
    for (Map.Entry<String, String> entry : source.entrySet()) {
      String code = entry.getKey();
      if (code.length() >= prefix.length()
          && code.regionMatches(true, 0, prefix, 0, prefix.length())
          && seen.add(code)) {
        matches.add(new Suggestion(code, entry.getValue()));
      }
    }
    matches.sort(Comparator.comparing(Suggestion::code, String.CASE_INSENSITIVE_ORDER));
    target.addAll(matches);
  }

  public void loadGlobal() {
    if (this.globalLoaded) {
      return;
    }
    this.globalLoaded = true;
    this.executor.execute(() -> {
      Map<String, String> loaded = new HashMap<>();
      int bttv = this.load(loaded, "BTTV", "globally",
          target -> this.readBttv(SimpleHttp.get(BTTV_GLOBAL, null).element(), target));
      int ffz = this.load(loaded, "FFZ", "globally",
          target -> this.readFfz(SimpleHttp.get(FFZ_GLOBAL, null).json(), target, true));
      int sevenTv = this.load(loaded, "7TV", "globally",
          target -> this.readSevenTv(SimpleHttp.get(SEVENTV_GLOBAL, null).json().get("emotes"), target));
      this.global.putAll(loaded);
      this.logger.info("Loaded {} global third-party emotes (BTTV {}, FFZ {}, 7TV {})",
          loaded.size(), bttv, ffz, sevenTv);
    });
  }

  public void loadChannel(String roomId) {
    if (roomId == null || this.channels.containsKey(roomId)) {
      return;
    }
    this.channels.put(roomId, new ConcurrentHashMap<>());
    this.executor.execute(() -> {
      Map<String, String> loaded = new HashMap<>();
      int bttv = this.load(loaded, "BTTV", "for room " + roomId, target -> {
        Response response = SimpleHttp.get(String.format(BTTV_CHANNEL, roomId), null);
        if (response.isOk()) {
          JsonObject json = response.json();
          this.readBttv(json.get("channelEmotes"), target);
          this.readBttv(json.get("sharedEmotes"), target);
        }
      });
      int ffz = this.load(loaded, "FFZ", "for room " + roomId, target -> {
        Response response = SimpleHttp.get(String.format(FFZ_CHANNEL, roomId), null);
        if (response.isOk()) {
          this.readFfz(response.json(), target, false);
        }
      });
      int sevenTv = this.load(loaded, "7TV", "for room " + roomId, target -> {
        Response response = SimpleHttp.get(String.format(SEVENTV_CHANNEL, roomId), null);
        if (response.isOk() && response.json().get("emote_set") instanceof JsonObject set) {
          this.readSevenTv(set.get("emotes"), target);
        }
      });
      this.channels.get(roomId).putAll(loaded);
      this.logger.info("Loaded {} third-party emotes for room {} (BTTV {}, FFZ {}, 7TV {})",
          loaded.size(), roomId, bttv, ffz, sevenTv);
    });
  }

  /**
   * Runs one provider and reports how many codes it contributed, so a channel without emotes can
   * be told apart from a provider that failed.
   */
  private int load(Map<String, String> target, String provider, String context, Loader loader) {
    int before = target.size();
    try {
      loader.load(target);
    } catch (Exception e) {
      this.logger.warn("Could not load {} emotes {}: {}", provider, context, e.getMessage());
    }
    return target.size() - before;
  }

  private void readBttv(JsonElement element, Map<String, String> target) {
    if (!(element instanceof JsonArray array)) {
      return;
    }
    for (JsonElement entry : array) {
      JsonObject emote = entry.getAsJsonObject();
      // BTTV transcodes every emote to a static PNG under the .png extension, animated ones
      // included, so there is nothing to filter out here.
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

  private void readSevenTv(JsonElement element, Map<String, String> target) {
    if (!(element instanceof JsonArray array)) {
      return;
    }
    for (JsonElement entry : array) {
      JsonObject emote = entry.getAsJsonObject();
      if (!(emote.get("data") instanceof JsonObject data)) {
        continue;
      }
      String url = this.sevenTvUrl(data);
      if (url != null) {
        // The name on the set entry is the alias the channel uses, which is what chat sends.
        target.put(emote.get("name").getAsString(), url);
      }
    }
  }

  /**
   * Picks the 1x still image of a 7TV emote. {@code static_name} is the file itself for a static
   * emote and the extracted first frame for an animated one; AVIF is skipped because ImageIO
   * cannot decode it.
   */
  private String sevenTvUrl(JsonObject data) {
    if (!(data.get("host") instanceof JsonObject host)
        || !(host.get("files") instanceof JsonArray files)
        || host.get("url") == null) {
      return null;
    }
    String base = host.get("url").getAsString();
    if (base.startsWith("//")) {
      base = "https:" + base;
    }

    String file = null;
    int rank = -1;
    for (JsonElement entry : files) {
      JsonObject candidate = entry.getAsJsonObject();
      JsonElement name = candidate.has("static_name")
          ? candidate.get("static_name")
          : candidate.get("name");
      if (name == null || !name.getAsString().startsWith("1x")) {
        continue;
      }
      int candidateRank = formatRank(candidate.get("format"));
      if (candidateRank > rank) {
        rank = candidateRank;
        file = name.getAsString();
      }
    }
    return file == null ? null : base + "/" + file;
  }

  private static int formatRank(JsonElement format) {
    if (format == null) {
      return -1;
    }
    return switch (format.getAsString().toUpperCase(Locale.ROOT)) {
      case "PNG" -> 2;
      case "WEBP" -> 1;
      case "GIF" -> 0;
      default -> -1;
    };
  }

  public record Suggestion(String code, String url) {

  }

  @FunctionalInterface
  private interface Loader {

    void load(Map<String, String> target) throws Exception;
  }
}
