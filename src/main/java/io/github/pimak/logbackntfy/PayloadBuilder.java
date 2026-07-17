package io.github.pimak.logbackntfy;

import java.time.Instant;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

/**
 * Pure transformation from an {@code ILoggingEvent} to a structured, truncated {@link AlertPayload}.
 * Stateless — no Logback lifecycle, no HTTP, no hostname detection. The title suffix and the
 * body's stack frames are always drawn from the ROOT cause of the event's exception chain, walked
 * via {@link IThrowableProxy#getCause()}, never from the surface exception and never by casting to
 * the concrete {@code ThrowableProxy} class.
 */
final class PayloadBuilder {

  private PayloadBuilder() {}

  /**
   * Assembles the alert payload for {@code event}. {@code configuredTitle} wins over {@code
   * appName} when non-blank; when neither is set the title is simply empty — this is a
   * valid, if unhelpful, configuration, not a third inactive-trigger. {@code
   * maxStackFrames} caps the number of root-cause frames included in the body before the result is
   * passed through {@link PayloadTruncator} so it never exceeds {@link
   * PayloadTruncator#NTFY_MAX_BYTES}.
   */
  public static AlertPayload build(
      ILoggingEvent event, String configuredTitle, String appName, int maxStackFrames) {
    IThrowableProxy rootCause = rootCauseOf(event);
    String title = buildTitle(configuredTitle, appName, rootCause);
    String body = buildBody(event, rootCause, maxStackFrames);
    return new AlertPayload(
        title, PayloadTruncator.truncate(body, PayloadTruncator.NTFY_MAX_BYTES));
  }

  /**
   * Walks {@code event.getThrowableProxy()}'s {@code getCause()} chain until the last non-null
   * proxy. Returns {@code null} when the event has no throwable at all (no exception -> title
   * alone).
   */
  private static IThrowableProxy rootCauseOf(ILoggingEvent event) {
    IThrowableProxy proxy = event.getThrowableProxy();
    if (proxy == null) {
      return null;
    }
    IThrowableProxy cursor = proxy;
    while (cursor.getCause() != null) {
      cursor = cursor.getCause();
    }
    return cursor;
  }

  private static String buildTitle(
      String configuredTitle, String appName, IThrowableProxy rootCause) {
    String base = !isBlank(configuredTitle) ? configuredTitle : appName;
    if (base == null) {
      base = "";
    }
    if (rootCause == null) {
      return base;
    }
    // ASCII " - " (U+002D) separator, never a non-ASCII dash: the JDK HttpClient rejects HTTP
    // header values containing chars > 0xFF, so a non-ASCII separator would abort every
    // exception-alert publish at the header-build boundary.
    return base + " - " + rootCause.getClassName();
  }

  /**
   * Body order: log message, logger name, full cause chain (one "Caused by" line per proxy,
   * surface to root), up to {@code maxStackFrames} entries of the ROOT cause's frames, then the
   * event timestamp. All labels come from {@link AlertMessages}.
   */
  private static String buildBody(
      ILoggingEvent event, IThrowableProxy rootCause, int maxStackFrames) {
    StringBuilder sb = new StringBuilder();
    sb.append(AlertMessages.LABEL_MESSAGE).append(event.getFormattedMessage()).append('\n');
    sb.append(AlertMessages.LABEL_LOGGER).append(event.getLoggerName()).append('\n');

    IThrowableProxy throwableProxy = event.getThrowableProxy();
    if (throwableProxy != null) {
      for (IThrowableProxy cursor = throwableProxy; cursor != null; cursor = cursor.getCause()) {
        sb.append(AlertMessages.LABEL_CAUSE)
            .append(cursor.getClassName())
            .append(": ")
            .append(cursor.getMessage())
            .append('\n');
      }
      StackTraceElementProxy[] frames =
          rootCause == null ? null : rootCause.getStackTraceElementProxyArray();
      int frameCount = frames == null ? 0 : frames.length;
      int limit = Math.min(Math.max(maxStackFrames, 0), frameCount);
      for (int i = 0; i < limit; i++) {
        sb.append("  at ").append(frames[i].getStackTraceElement()).append('\n');
      }
    }

    sb.append(AlertMessages.LABEL_TIMESTAMP).append(Instant.ofEpochMilli(event.getTimeStamp()));
    return sb.toString();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
