package net.labymod.addons.twitchchat.core.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Tiny blocking HTTP helper on top of {@link HttpURLConnection}; used for the Twitch OAuth and
 * Helix calls so the addon has no dependency on the exact shape of LabyMod's request API.
 */
public final class SimpleHttp {

  private static final String USER_AGENT = "LabyMod-TwitchChat/1.0";
  private static final int TIMEOUT = 10_000;

  private SimpleHttp() {
  }

  public record Response(int status, String body) {

    public boolean isOk() {
      return this.status >= 200 && this.status < 300;
    }

    public JsonObject json() {
      if (this.body == null || this.body.isEmpty()) {
        return new JsonObject();
      }
      JsonElement element = JsonParser.parseString(this.body);
      return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    public JsonElement element() {
      return this.body == null || this.body.isEmpty() ? new JsonObject() : JsonParser.parseString(this.body);
    }
  }

  public static Response get(String url, Map<String, String> headers) throws IOException {
    HttpURLConnection connection = open(url, "GET", headers);
    return read(connection);
  }

  public static Response postForm(String url, Map<String, String> form, Map<String, String> headers)
      throws IOException {
    HttpURLConnection connection = open(url, "POST", headers);
    connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
    byte[] payload = encodeForm(form).getBytes(StandardCharsets.UTF_8);
    connection.setFixedLengthStreamingMode(payload.length);
    connection.getOutputStream().write(payload);
    return read(connection);
  }

  public static String encodeForm(Map<String, String> form) {
    StringBuilder builder = new StringBuilder();
    for (Map.Entry<String, String> entry : form.entrySet()) {
      if (builder.length() > 0) {
        builder.append('&');
      }
      builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
    }
    return builder.toString();
  }

  private static HttpURLConnection open(String url, String method, Map<String, String> headers)
      throws IOException {
    HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
    connection.setRequestMethod(method);
    connection.setConnectTimeout(TIMEOUT);
    connection.setReadTimeout(TIMEOUT);
    connection.setRequestProperty("User-Agent", USER_AGENT);
    connection.setRequestProperty("Accept", "application/json");
    if (headers != null) {
      headers.forEach(connection::setRequestProperty);
    }
    return connection;
  }

  private static Response read(HttpURLConnection connection) throws IOException {
    int status = connection.getResponseCode();
    InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
    String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    connection.disconnect();
    return new Response(status, body);
  }
}
