package io.github.pimak.logbackntfy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;

/**
 * Verifies {@link PayloadBuilder} assembles a root-cause-based title and a structured body from a
 * genuine {@code ILoggingEvent}/{@code IThrowableProxy} chain (never a mocked/canned proxy).
 */
class PayloadBuilderTest {

  private static final Logger LOGGER = (Logger) LoggerFactory.getLogger(PayloadBuilderTest.class);

  @Test
  void build_wrappedException_titleEndsWithRootCauseClassName() {
    IllegalStateException root = new IllegalStateException("root cause");
    RuntimeException surface = new RuntimeException("surface", root);
    LoggingEvent event =
        new LoggingEvent(Logger.class.getName(), LOGGER, Level.ERROR, "boom", surface, null);

    AlertPayload payload = PayloadBuilder.build(event, "MyApp", "fallback-app", 5);

    assertThat(payload.title()).contains(" - ");
    assertThat(payload.title()).endsWith("IllegalStateException");
    // Pins the exact ASCII separator (U+002D): a non-ASCII dash in the title would be rejected by
    // the JDK HttpClient as an invalid header value and kill every exception alert.
    assertThat(payload.title()).isEqualTo("MyApp - java.lang.IllegalStateException");
  }

  @Test
  void build_noException_titleHasNoSuffixAndBodyHasNoCauseLine() {
    LoggingEvent event =
        new LoggingEvent(Logger.class.getName(), LOGGER, Level.ERROR, "boom", null, null);

    AlertPayload payload = PayloadBuilder.build(event, "MyApp", "fallback-app", 5);

    assertThat(payload.title()).isEqualTo("MyApp");
    assertThat(payload.title()).doesNotContain(" - ");
    assertThat(payload.body()).doesNotContain("Caused by");
  }

  @Test
  void build_blankConfiguredTitle_fallsBackToAppName() {
    LoggingEvent event =
        new LoggingEvent(Logger.class.getName(), LOGGER, Level.ERROR, "boom", null, null);

    AlertPayload payload = PayloadBuilder.build(event, "", "fallback-app", 5);

    assertThat(payload.title()).isEqualTo("fallback-app");
  }

  @Test
  void build_bodyContainsMessageLoggerCauseChainAndTimestamp() {
    IllegalStateException root = new IllegalStateException("root cause");
    RuntimeException surface = new RuntimeException("surface", root);
    LoggingEvent event =
        new LoggingEvent(
            Logger.class.getName(), LOGGER, Level.ERROR, "boom happened", surface, null);

    AlertPayload payload = PayloadBuilder.build(event, "MyApp", "fallback-app", 5);

    assertThat(payload.body()).contains("boom happened");
    assertThat(payload.body()).contains(LOGGER.getName());
    assertThat(payload.body()).contains("Caused by: java.lang.RuntimeException");
    assertThat(payload.body()).contains("Caused by: java.lang.IllegalStateException");
    assertThat(payload.body()).contains("Time: ");
  }

  @Test
  void build_maxStackFramesCapsFrameLines() {
    IllegalStateException root = new IllegalStateException("root cause");
    RuntimeException surface = new RuntimeException("surface", root);
    LoggingEvent event =
        new LoggingEvent(Logger.class.getName(), LOGGER, Level.ERROR, "boom", surface, null);

    AlertPayload payload = PayloadBuilder.build(event, "MyApp", "fallback-app", 2);

    long frameLines = payload.body().lines().filter(l -> l.trim().startsWith("at ")).count();
    assertThat(frameLines).isLessThanOrEqualTo(2);
  }

  @Test
  void build_bodyUsesAlertMessagesLabelConstants() {
    LoggingEvent event =
        new LoggingEvent(Logger.class.getName(), LOGGER, Level.ERROR, "boom", null, null);

    AlertPayload payload = PayloadBuilder.build(event, "MyApp", "fallback-app", 5);

    assertThat(payload.body()).contains(AlertMessages.LABEL_MESSAGE + "boom");
    assertThat(payload.body()).contains(AlertMessages.LABEL_LOGGER + LOGGER.getName());
    assertThat(payload.body()).contains(AlertMessages.LABEL_TIMESTAMP);
  }

  @Test
  void build_titleFallsBackToConfiguredTitleOverAppNameWhenBothSet() {
    LoggingEvent event =
        new LoggingEvent(Logger.class.getName(), LOGGER, Level.ERROR, "boom", null, null);

    AlertPayload payload = PayloadBuilder.build(event, "ConfiguredTitle", "fallback-app", 5);

    assertThat(payload.title()).isEqualTo("ConfiguredTitle");
  }

  /**
   * Integration case: a deliberately oversized body (500 synthetic root-cause frames, well beyond
   * the 4096-byte budget) must come out truncated under the byte limit, with the earlier body
   * sections (message, logger) surviving while the later (trailing) frame lines are dropped
   * first.
   */
  @Test
  void build_oversizedBody_truncatedUnderByteBudgetWithEarlySectionsSurviving() {
    StackTraceElement[] frames = new StackTraceElement[500];
    for (int i = 0; i < frames.length; i++) {
      frames[i] =
          new StackTraceElement("com.example.Class" + i, "method" + i, "Class" + i + ".java", i);
    }
    IllegalStateException root = new IllegalStateException("deep root cause");
    root.setStackTrace(frames);
    RuntimeException surface = new RuntimeException("surface", root);
    LoggingEvent event =
        new LoggingEvent(
            Logger.class.getName(), LOGGER, Level.ERROR, "boom happened", surface, null);

    AlertPayload payload = PayloadBuilder.build(event, "MyApp", "fallback-app", frames.length);

    assertThat(payload.body().getBytes(StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(PayloadTruncator.NTFY_MAX_BYTES);
    assertThat(payload.body()).contains("boom happened");
    assertThat(payload.body()).contains(AlertMessages.LABEL_LOGGER + LOGGER.getName());
    assertThat(payload.body()).doesNotContain("method499");
  }
}
