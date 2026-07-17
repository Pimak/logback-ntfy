package io.github.pimak.logbackntfy;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Stateless, never-throws HTTP publisher for the ntfy wire format.
 *
 * <p>Zero framework dependencies — this class is plain-constructed by {@code NtfyAlertAppender},
 * not managed by any DI container. It never calls an SLF4J logger and never posts to a Logback
 * {@code StatusManager}; the appender owns all diagnostics and only consumes the returned
 * {@link PublishResult}.
 *
 * <p>No SSRF guard is included here — that concern is out of scope for a plain HTTP publisher and
 * is the consumer's responsibility if the target URL is derived from untrusted input.
 */
public class NtfyPublisher {

  private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private static final String GENERIC_INVALID_REQUEST_MESSAGE =
      "invalid request: malformed URL, topic, or header value";

  private final HttpClient httpClient;
  private final Duration requestTimeout;

  public NtfyPublisher(HttpClient httpClient) {
    this(httpClient, DEFAULT_REQUEST_TIMEOUT);
  }

  public NtfyPublisher(HttpClient httpClient, Duration requestTimeout) {
    this.httpClient = httpClient;
    this.requestTimeout = requestTimeout;
  }

  /**
   * Publishes {@code title}/{@code body} to {@code {url}/{topic}} with no {@code Priority}/{@code
   * Tags} headers. Delegates to {@link #publish(String, String, String, AuthMode, String, String,
   * String)} with {@code priority}/{@code tags} both {@code null}.
   *
   * @return a {@link PublishResult} describing the outcome; never throws
   */
  public PublishResult publish(
      String url, String topic, String title, AuthMode auth, String body) {
    return publish(url, topic, title, auth, body, null, null);
  }

  /**
   * Publishes {@code title}/{@code body} to {@code {url}/{topic}}, additionally forwarding {@code
   * priority} and {@code tags} as ntfy's {@code Priority} and {@code Tags} HTTP headers.
   *
   * <p>Both values are forwarded VERBATIM: no local validation, no whitelist, no default
   * substitution. A blank/null {@code priority} sends no {@code Priority} header; a blank/null
   * {@code tags} sends no {@code Tags} header.
   *
   * <p>The {@code Authorization} header (if any) comes entirely from the supplied {@link
   * AuthMode}: {@code auth.buildHeader()} returns the header value to send, or {@code
   * Optional.empty()} to send no {@code Authorization} header at all (a valid anonymous publish).
   *
   * @return a {@link PublishResult} describing the outcome; never throws
   */
  public PublishResult publish(
      String url,
      String topic,
      String title,
      AuthMode auth,
      String body,
      String priority,
      String tags) {
    try {
      String base = url.replaceAll("/+$", "");
      URI uri = URI.create(base + "/" + topic);

      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(uri)
              .timeout(requestTimeout)
              .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));

      auth.buildHeader().ifPresent(header -> builder.header("Authorization", header));

      if (!isBlank(title)) {
        builder.header("Title", asciiSafeTitle(title));
      }

      if (!isBlank(priority)) {
        builder.header("Priority", priority);
      }

      if (!isBlank(tags)) {
        builder.header("Tags", tags);
      }

      HttpResponse<String> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status >= 200 && status < 300) {
        return PublishResult.success(status);
      }
      return PublishResult.failure(status, "ntfy server returned HTTP " + status);
    } catch (HttpTimeoutException e) {
      return PublishResult.failure("timeout");
    } catch (ConnectException e) {
      return PublishResult.failure("connection refused");
    } catch (IllegalArgumentException e) {
      // Thrown by URI.create() (malformed URL/topic) or HttpRequest.Builder.header() (a
      // credential/value containing an illegal control character embeds that value verbatim in
      // its own message) — never surface e.getMessage() here, it may contain the plaintext
      // credential.
      return PublishResult.failure(GENERIC_INVALID_REQUEST_MESSAGE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return PublishResult.failure("interrupted");
    } catch (Exception e) {
      // Classify by exception type only — never e.getMessage(), which can embed the full URI,
      // proxy details, or other request internals.
      return PublishResult.failure("publish failed: " + e.getClass().getSimpleName());
    }
  }

  /**
   * Returns {@code title} verbatim when it is printable ASCII; otherwise wraps it in an RFC 2047
   * encoded-word ({@code =?UTF-8?B?...?=}, the form ntfy documents for non-ASCII titles). The JDK
   * {@code HttpClient} rejects header values containing chars &gt; 0xFF, so without this a
   * non-ASCII configured title/appName would make every publish fail before any request is sent.
   */
  private static String asciiSafeTitle(String title) {
    if (isAsciiPrintable(title)) {
      return title;
    }
    return "=?UTF-8?B?"
        + Base64.getEncoder().encodeToString(title.getBytes(StandardCharsets.UTF_8))
        + "?=";
  }

  private static boolean isAsciiPrintable(String s) {
    return s.chars().allMatch(c -> c >= 0x20 && c <= 0x7E);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
