package net.labymod.addons.twitchchat.core.irc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import net.labymod.api.util.io.LabyExecutors;
import net.labymod.api.util.logging.Logging;

/**
 * Minimal Twitch IRC client over TLS. Reads anonymously (a {@code justinfan} nick) when no
 * credentials are given and reconnects with exponential backoff until {@link #disconnect()} is
 * called. All callbacks are fired from the reader thread or the scheduler; consumers must hop to
 * the render thread themselves.
 */
public final class TwitchIrcClient {

  public interface Listener {

    void onConnected(boolean anonymous);

    void onDisconnected(Throwable reason, boolean willReconnect);

    void onLine(IrcLine line);

    void onAuthenticationFailed();
  }

  public record Credentials(String nick, String oauthToken) {

    public boolean isAnonymous() {
      return this.oauthToken == null || this.oauthToken.isEmpty();
    }
  }

  private static final String HOST = "irc.chat.twitch.tv";
  private static final int PORT = 6697;
  private static final long MAX_BACKOFF_SECONDS = 60;

  private final Logging logger;
  private final Listener listener;
  private final ScheduledExecutorService scheduler;

  private volatile Credentials credentials;
  private volatile String channel;
  private volatile boolean running;
  private volatile boolean welcomed;
  private volatile int generation;
  private int reconnectAttempts;

  private Socket socket;
  private BufferedWriter writer;

  public TwitchIrcClient(Logging logger, Listener listener) {
    this.logger = logger;
    this.listener = listener;
    this.scheduler = LabyExecutors.newSingleThreadScheduledExecutor("TwitchChat-IRC-%d");
  }

  public synchronized void connect(Credentials credentials, String channel) {
    this.credentials = credentials;
    this.channel = normalize(channel);
    this.running = true;
    this.reconnectAttempts = 0;
    this.closeSocket();
    int generation = ++this.generation;
    this.scheduler.execute(() -> this.open(generation));
  }

  public synchronized void disconnect() {
    this.running = false;
    this.generation++;
    this.closeSocket();
  }

  public boolean isRunning() {
    return this.running;
  }

  public boolean isConnected() {
    return this.running && this.welcomed;
  }

  public boolean isAnonymous() {
    Credentials credentials = this.credentials;
    return credentials == null || credentials.isAnonymous();
  }

  public String channel() {
    return this.channel;
  }

  /**
   * Switches to another channel on the open connection.
   */
  public synchronized void join(String channel) {
    String normalized = normalize(channel);
    String previous = this.channel;
    this.channel = normalized;
    if (!this.welcomed) {
      return;
    }
    if (previous != null && !previous.equals(normalized)) {
      this.sendRaw("PART #" + previous);
    }
    if (normalized != null) {
      this.sendRaw("JOIN #" + normalized);
    }
  }

  /**
   * @return false if the client is not connected or has no credentials to speak with
   */
  public boolean sendMessage(String text) {
    if (!this.welcomed || this.isAnonymous() || this.channel == null) {
      return false;
    }
    String sanitized = text.replace('\r', ' ').replace('\n', ' ');
    return this.sendRaw("PRIVMSG #" + this.channel + " :" + sanitized);
  }

  public void shutdown() {
    this.disconnect();
    this.scheduler.shutdownNow();
  }

  private void open(int generation) {
    if (!this.running || generation != this.generation) {
      return;
    }

    Credentials credentials = this.credentials;
    boolean anonymous = credentials == null || credentials.isAnonymous();
    String nick = anonymous
        ? "justinfan" + ThreadLocalRandom.current().nextInt(10_000, 99_999)
        : credentials.nick().toLowerCase(Locale.ROOT);
    String pass = anonymous ? "SCHMOOPIIE" : "oauth:" + credentials.oauthToken();

    Socket socket;
    BufferedReader reader;
    try {
      SSLSocket sslSocket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(HOST, PORT);
      SSLParameters parameters = sslSocket.getSSLParameters();
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      sslSocket.setSSLParameters(parameters);
      sslSocket.setSoTimeout((int) TimeUnit.MINUTES.toMillis(7));
      sslSocket.startHandshake();
      socket = sslSocket;
      reader = new BufferedReader(
          new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      synchronized (this) {
        if (!this.running || generation != this.generation) {
          socket.close();
          return;
        }
        this.socket = socket;
        this.writer = new BufferedWriter(
            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
      }
      this.sendRaw("CAP REQ :twitch.tv/tags twitch.tv/commands");
      this.sendRaw("PASS " + pass);
      this.sendRaw("NICK " + nick);
    } catch (IOException e) {
      this.scheduleReconnect(generation, e);
      return;
    }

    Thread thread = new Thread(() -> this.read(generation, reader, anonymous), "TwitchChat-IRC-Reader");
    thread.setDaemon(true);
    thread.start();
  }

  private void read(int generation, BufferedReader reader, boolean anonymous) {
    Throwable failure = null;
    try {
      String raw;
      while (this.running && generation == this.generation && (raw = reader.readLine()) != null) {
        if (raw.isEmpty()) {
          continue;
        }
        this.handle(raw, anonymous);
      }
    } catch (IOException e) {
      failure = e;
    } catch (Throwable t) {
      failure = t;
      this.logger.error("Unexpected error in the Twitch IRC reader", t);
    }

    if (generation != this.generation) {
      return;
    }
    this.welcomed = false;
    this.closeSocket();
    this.scheduleReconnect(generation, failure);
  }

  private void handle(String raw, boolean anonymous) {
    if (raw.startsWith("PING")) {
      this.sendRaw("PONG :tmi.twitch.tv");
      return;
    }

    IrcLine line = IrcLine.parse(raw);
    switch (line.command()) {
      case "001" -> {
        this.welcomed = true;
        this.reconnectAttempts = 0;
        String channel = this.channel;
        if (channel != null) {
          this.sendRaw("JOIN #" + channel);
        }
        this.listener.onConnected(anonymous);
      }
      case "NOTICE" -> {
        String text = line.trailing();
        if (text != null && line.channel() == null
            && (text.contains("authentication failed") || text.contains("Improperly formatted auth"))) {
          this.listener.onAuthenticationFailed();
          return;
        }
        this.listener.onLine(line);
      }
      case "RECONNECT" -> {
        this.logger.info("Twitch asked us to reconnect");
        this.closeSocket();
      }
      default -> this.listener.onLine(line);
    }
  }

  private void scheduleReconnect(int generation, Throwable reason) {
    if (!this.running || generation != this.generation) {
      return;
    }
    long delay = Math.min(MAX_BACKOFF_SECONDS, (long) Math.pow(2, Math.min(6, this.reconnectAttempts)));
    this.reconnectAttempts++;
    this.listener.onDisconnected(reason, true);
    this.scheduler.schedule(() -> this.open(generation), delay, TimeUnit.SECONDS);
  }

  private synchronized boolean sendRaw(String line) {
    BufferedWriter writer = this.writer;
    if (writer == null) {
      return false;
    }
    try {
      writer.write(line);
      writer.write("\r\n");
      writer.flush();
      return true;
    } catch (IOException e) {
      this.logger.warn("Failed to write to Twitch IRC: {}", e.getMessage());
      this.closeSocket();
      return false;
    }
  }

  private synchronized void closeSocket() {
    this.welcomed = false;
    Socket socket = this.socket;
    this.socket = null;
    this.writer = null;
    if (socket != null) {
      try {
        socket.close();
      } catch (IOException ignored) {
      }
    }
  }

  public static String normalize(String channel) {
    if (channel == null) {
      return null;
    }
    String trimmed = channel.trim().toLowerCase(Locale.ROOT);
    if (trimmed.startsWith("#")) {
      trimmed = trimmed.substring(1);
    }
    int slash = trimmed.lastIndexOf('/');
    if (slash >= 0) {
      trimmed = trimmed.substring(slash + 1);
    }
    return trimmed.isEmpty() ? null : trimmed;
  }
}
