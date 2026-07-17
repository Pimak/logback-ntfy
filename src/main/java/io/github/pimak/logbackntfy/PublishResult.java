package io.github.pimak.logbackntfy;

/**
 * Outcome of a single {@link NtfyPublisher#publish} call.
 *
 * <p>Local, dependency-free result type — deliberately framework-agnostic, with no dependency on
 * any external notification-result type. {@code message} is always a fixed, safe-to-surface
 * string; it must never carry a plaintext credential.
 *
 * @param success whether the ntfy server accepted the request (HTTP 2xx)
 * @param httpStatus the HTTP status code, or {@code null} if the request never reached the server
 *     (timeout, connection refused, malformed request, interrupted)
 * @param message {@code null} on success; a fixed, credential-safe description on failure
 */
public record PublishResult(boolean success, Integer httpStatus, String message) {

  public static PublishResult success(int httpStatus) {
    return new PublishResult(true, httpStatus, null);
  }

  public static PublishResult failure(String message) {
    return new PublishResult(false, null, message);
  }

  public static PublishResult failure(int httpStatus, String message) {
    return new PublishResult(false, httpStatus, message);
  }
}
