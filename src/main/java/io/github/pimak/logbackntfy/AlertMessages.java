package io.github.pimak.logbackntfy;

/**
 * Single centralized point for every visible English string this module produces (body labels
 * and self-diagnostic status messages). Keeping all literal text here — rather than scattered
 * across the appender/publisher — is what makes a future locale/translation layer possible without
 * touching the public API.
 */
final class AlertMessages {

  private AlertMessages() {}

  static final String LABEL_MESSAGE = "Message: ";
  static final String LABEL_LOGGER = "Logger: ";
  static final String LABEL_CAUSE = "Caused by: ";
  static final String LABEL_TIMESTAMP = "Time: ";

  static final String STATUS_DISABLED_UNCONFIGURED =
      "ntfy alert appender not configured (url/topic unset) — inactive";

  static final String STATUS_DISABLED_PARTIAL_CONFIG =
      "url set but topic missing — appender disabled";

  static final String STATUS_TOKEN_AND_BASIC_BOTH_SET =
      "both token and username/password configured — token takes precedence";

  /**
   * Fixed message emitted from {@code start()} when {@code suppressionWindow} is unset,
   * zero, or negative — the appender falls back to the default window instead of throwing out of
   * {@code start()} with resources half-acquired. Credential-safe: fixed text only.
   */
  static final String STATUS_INVALID_SUPPRESSION_WINDOW =
      "suppressionWindow must be positive — falling back to default (3 minutes)";

  /**
   * Fixed, generic message for an unexpected {@code RuntimeException} inside {@code append()}.
   * Deliberately never concatenates {@code e.getMessage()} — the exception itself
   * is still passed to {@code addError(String, Throwable)} for the StatusManager to record, but the
   * *text* here never embeds anything that could carry a credential.
   */
  static final String PUBLISH_UNEXPECTED_ERROR = "ntfy publish threw unexpectedly";

  /**
   * Composes the "appender is active" status line. Never includes the token or any other credential
   * — only the topic and the url with any userinfo stripped: a {@code
   * https://user:pass@host} URL (a form ntfy documents for basic auth) must never echo its password
   * into StatusManager output.
   */
  static String statusActive(String url, String topic) {
    String safeUrl = url == null ? null : url.replaceFirst("//[^/@]+@", "//");
    return "ntfy alert appender ACTIVE (url=" + safeUrl + ", topic=" + topic + ")";
  }

  /**
   * Composes a publish-failure status line. Only the topic and (when present) the HTTP status code
   * are included — never the token, username/password, full authenticated URL, or a raw exception
   * message that could embed a credential.
   */
  static String publishFailed(String topic, Integer httpStatus) {
    if (httpStatus != null) {
      return "ntfy publish failed for topic '" + topic + "' (HTTP " + httpStatus + ")";
    }
    return "ntfy publish failed for topic '" + topic + "'";
  }

  /**
   * Composes the "excluded loggers" status line emitted once from {@code start()}.
   * Credential-safe — only interpolates operator-configured logger-name prefixes, never a
   * token/username/password.
   */
  static String statusExclusions(java.util.List<String> excludedPrefixes) {
    if (excludedPrefixes == null || excludedPrefixes.isEmpty()) {
      return "ntfy alert appender: no excluded loggers configured";
    }
    return "ntfy alert appender excluded loggers: " + String.join(", ", excludedPrefixes);
  }

  /**
   * Number of top loggers listed individually in a digest body before an overflow marker.
   */
  private static final int DIGEST_TOP_LOGGER_LIMIT = 5;

  /**
   * Composes the storm-digest title: {@code "<titleOrAppName> — <count> errors suppressed"}
   * (em dash U+2014). {@code titleOrAppName} may be {@code null} (treated as empty string, no
   * NPE). Credential-safe — only interpolates a caller-supplied label and an integer
   * count.
   */
  static String digestTitle(String titleOrAppName, int count) {
    String base = titleOrAppName == null ? "" : titleOrAppName;
    return base + " — " + count + " errors suppressed";
  }

  /**
   * Composes the storm-digest body: a summary line followed by up to {@value
   * #DIGEST_TOP_LOGGER_LIMIT} "<count>x <loggerName>" lines (sorted by count descending), plus a
   * "+N others" line when more than {@value #DIGEST_TOP_LOGGER_LIMIT} distinct loggers were
   * tallied. Does NOT apply {@link PayloadTruncator} — truncation happens at the publish site.
   * Credential-safe — only interpolates count, logger names, and a window description,
   * never token/username/password.
   */
  static String digestBody(
      int count, java.util.Map<String, Integer> perLoggerTally, String windowDescription) {
    StringBuilder sb = new StringBuilder();
    sb.append(count).append(" errors suppressed in the last ").append(windowDescription);

    java.util.List<java.util.Map.Entry<String, Integer>> sorted =
        perLoggerTally.entrySet().stream()
            .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
            .toList();

    int shown = Math.min(DIGEST_TOP_LOGGER_LIMIT, sorted.size());
    for (int i = 0; i < shown; i++) {
      java.util.Map.Entry<String, Integer> entry = sorted.get(i);
      sb.append('\n').append(entry.getValue()).append("x ").append(entry.getKey());
    }

    int remaining = sorted.size() - shown;
    if (remaining > 0) {
      sb.append('\n').append('+').append(remaining).append(" others");
    }
    return sb.toString();
  }
}
