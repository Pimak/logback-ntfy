package io.github.pimak.logbackntfy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Auth strategy for an outbound ntfy publish request, modeled as a sealed hierarchy so each mode
 * carries only the fields it needs and knows how to build its own {@code Authorization} header.
 *
 * <p>{@link None} is a valid, first-class configuration: an appender with no {@code token} and no
 * {@code username}/{@code password} configured publishes without an {@code Authorization} header,
 * which is a legitimate way to reach public ntfy.sh topics or a permissive self-hosted server.
 */
public sealed abstract class AuthMode permits AuthMode.BearerToken, AuthMode.BasicAuth, AuthMode.None {

  /**
   * Builds the {@code Authorization} header value for this auth mode, if any.
   *
   * @return {@code Optional.of(headerValue)} when this mode sends an {@code Authorization}
   *     header, or {@code Optional.empty()} when no header should be sent at all.
   */
  public abstract Optional<String> buildHeader();

  /** Creates a {@link BearerToken} auth mode carrying the given token. */
  public static AuthMode bearer(String token) {
    return new BearerToken(token);
  }

  /** Creates a {@link BasicAuth} auth mode carrying the given username/password pair. */
  public static AuthMode basic(String username, String password) {
    return new BasicAuth(username, password);
  }

  /** Returns the shared {@link None} instance (no {@code Authorization} header is sent). */
  public static AuthMode none() {
    return None.INSTANCE;
  }

  /**
   * Derives the applicable {@link AuthMode} from raw configured credentials, encoding the same
   * precedence as the appender's configuration surface: a non-blank {@code token} always wins,
   * even if {@code username}/{@code password} are also set; otherwise a non-blank {@code
   * username} AND {@code password} together select {@link BasicAuth}; otherwise {@link None}.
   *
   * <p>This factory is silent — it never emits a diagnostic for the token-plus-basic overlap
   * case. That one-time warning is the caller's responsibility (fired once, at appender startup).
   *
   * @return a {@link BearerToken}, {@link BasicAuth}, or the shared {@link None} instance
   */
  public static AuthMode fromCredentials(String token, String username, String password) {
    if (!isBlank(token)) {
      return bearer(token);
    }
    if (!isBlank(username) && !isBlank(password)) {
      return basic(username, password);
    }
    return none();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /** Sends {@code Authorization: Bearer <token>}. */
  public static final class BearerToken extends AuthMode {
    private final String token;

    private BearerToken(String token) {
      this.token = token;
    }

    @Override
    public Optional<String> buildHeader() {
      return Optional.of("Bearer " + token);
    }
  }

  /** Sends {@code Authorization: Basic <base64(username:password)>}. */
  public static final class BasicAuth extends AuthMode {
    private final String username;
    private final String password;

    private BasicAuth(String username, String password) {
      this.username = username;
      this.password = password;
    }

    @Override
    public Optional<String> buildHeader() {
      String raw = username + ":" + password;
      return Optional.of(
          "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
    }
  }

  /** Sends no {@code Authorization} header at all — a valid anonymous publish. */
  public static final class None extends AuthMode {
    private static final None INSTANCE = new None();

    private None() {}

    @Override
    public Optional<String> buildHeader() {
      return Optional.empty();
    }
  }
}
