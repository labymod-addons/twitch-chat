package net.labymod.addons.twitchchat.core.auth;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.labymod.addons.twitchchat.core.util.SimpleHttp;
import net.labymod.addons.twitchchat.core.util.SimpleHttp.Response;

/**
 * Twitch OAuth via the device code grant. The flow needs no client secret and no redirect URL,
 * so it works from inside the game: the player opens twitch.tv/activate, types the short code,
 * and the addon polls until Twitch hands out the tokens.
 */
public final class TwitchAuth {

  public static final String SCOPES = "chat:read chat:edit";

  private static final String DEVICE_URL = "https://id.twitch.tv/oauth2/device";
  private static final String TOKEN_URL = "https://id.twitch.tv/oauth2/token";
  private static final String VALIDATE_URL = "https://id.twitch.tv/oauth2/validate";
  private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";

  private TwitchAuth() {
  }

  public record DeviceCode(String deviceCode, String userCode, String verificationUri,
      int intervalSeconds, long expiresAt) {

    public boolean isExpired() {
      return System.currentTimeMillis() > this.expiresAt;
    }
  }

  public record TokenSet(String accessToken, String refreshToken, long expiresAt) {

  }

  public record Validation(String login, String userId, String clientId, long expiresInSeconds) {

  }

  public static final class AuthException extends IOException {

    private final String code;

    public AuthException(String code, String message) {
      super(message);
      this.code = code;
    }

    public String code() {
      return this.code;
    }
  }

  public static DeviceCode requestDeviceCode(String clientId) throws IOException {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("client_id", clientId);
    form.put("scopes", SCOPES);
    Response response = SimpleHttp.postForm(DEVICE_URL, form, null);
    JsonObject json = response.json();
    if (!response.isOk() || !json.has("device_code")) {
      throw new AuthException("device_code", message(json, response));
    }
    int interval = json.has("interval") ? json.get("interval").getAsInt() : 5;
    long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 1800;
    return new DeviceCode(
        json.get("device_code").getAsString(),
        json.get("user_code").getAsString(),
        json.get("verification_uri").getAsString(),
        Math.max(1, interval),
        System.currentTimeMillis() + expiresIn * 1000
    );
  }

  /**
   * @return the tokens once the user finished the flow, or {@code null} while Twitch still
   * reports {@code authorization_pending}
   * @throws AuthException with code {@code slow_down}, {@code expired_token} or
   *                       {@code access_denied}
   */
  public static TokenSet pollDeviceToken(String clientId, DeviceCode code) throws IOException {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("client_id", clientId);
    form.put("scopes", SCOPES);
    form.put("device_code", code.deviceCode());
    form.put("grant_type", DEVICE_GRANT);
    Response response = SimpleHttp.postForm(TOKEN_URL, form, null);
    JsonObject json = response.json();
    if (response.isOk() && json.has("access_token")) {
      return tokens(json);
    }
    String message = message(json, response);
    if (message.contains("authorization_pending")) {
      return null;
    }
    String errorCode = message.contains("slow_down") ? "slow_down"
        : message.contains("expired") ? "expired_token"
            : message.contains("denied") ? "access_denied" : "error";
    throw new AuthException(errorCode, message);
  }

  /**
   * @return the validation result or {@code null} if Twitch rejected the token
   */
  public static Validation validate(String accessToken) throws IOException {
    Response response = SimpleHttp.get(VALIDATE_URL, Map.of("Authorization", "OAuth " + accessToken));
    if (response.status() == 401) {
      return null;
    }
    JsonObject json = response.json();
    if (!response.isOk() || !json.has("login")) {
      throw new AuthException("validate", message(json, response));
    }
    return new Validation(
        json.get("login").getAsString(),
        json.get("user_id").getAsString(),
        json.has("client_id") ? json.get("client_id").getAsString() : "",
        json.has("expires_in") ? json.get("expires_in").getAsLong() : 0
    );
  }

  /**
   * @return refreshed tokens or {@code null} if the refresh token is no longer valid
   */
  public static TokenSet refresh(String clientId, String refreshToken) throws IOException {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("client_id", clientId);
    form.put("grant_type", "refresh_token");
    form.put("refresh_token", refreshToken);
    Response response = SimpleHttp.postForm(TOKEN_URL, form, null);
    JsonObject json = response.json();
    if (response.status() == 400 || response.status() == 401) {
      return null;
    }
    if (!response.isOk() || !json.has("access_token")) {
      throw new AuthException("refresh", message(json, response));
    }
    return tokens(json);
  }

  private static TokenSet tokens(JsonObject json) {
    long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 3600;
    return new TokenSet(
        json.get("access_token").getAsString(),
        json.has("refresh_token") ? json.get("refresh_token").getAsString() : null,
        System.currentTimeMillis() + expiresIn * 1000
    );
  }

  private static String message(JsonObject json, Response response) {
    if (json.has("message")) {
      return json.get("message").getAsString();
    }
    if (json.has("error")) {
      return json.get("error").getAsString();
    }
    return "HTTP " + response.status();
  }
}
