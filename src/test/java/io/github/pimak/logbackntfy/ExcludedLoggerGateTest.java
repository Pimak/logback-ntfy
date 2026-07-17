package io.github.pimak.logbackntfy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.status.Status;

/**
 * Pure-unit coverage of {@link NtfyAlertAppender#isExcluded(String)} and the {@code start()}
 * exclusion status line. No WireMock — the gate decision is tested directly, never by asserting
 * HTTP traffic (or its absence).
 */
class ExcludedLoggerGateTest {

  private static LoggingEvent eventFor(LoggerContext context, String loggerName) {
    Logger logger = context.getLogger(loggerName);
    return new LoggingEvent(Logger.class.getName(), logger, Level.ERROR, "boom", null, null);
  }

  private static NtfyAlertAppender unstartedAppender(LoggerContext context) {
    NtfyAlertAppender appender = new NtfyAlertAppender();
    appender.setContext(context);
    return appender;
  }

  @Test
  void isExcluded_childOfConfiguredPrefix_isExcluded() {
    LoggerContext context = new LoggerContext();
    NtfyAlertAppender appender = unstartedAppender(context);
    appender.setUrl("http://localhost:1");
    appender.setTopic("alerts");
    appender.setExcludedLoggers("org.apache.kafka, com.zaxxer.hikari");
    appender.start();

    assertThat(appender.isExcluded("org.apache.kafka.clients.NetworkClient")).isTrue();
    appender.stop();
  }

  @Test
  void isExcluded_exactMatchOfConfiguredPrefix_isExcluded() {
    LoggerContext context = new LoggerContext();
    NtfyAlertAppender appender = unstartedAppender(context);
    appender.setUrl("http://localhost:1");
    appender.setTopic("alerts");
    appender.setExcludedLoggers("org.apache.kafka");
    appender.start();

    assertThat(appender.isExcluded("org.apache.kafka")).isTrue();
    appender.stop();
  }

  @Test
  void isExcluded_siblingPackageAcrossHierarchyBoundary_isNotExcluded() {
    LoggerContext context = new LoggerContext();
    NtfyAlertAppender appender = unstartedAppender(context);
    appender.setUrl("http://localhost:1");
    appender.setTopic("alerts");
    appender.setExcludedLoggers("org.apache.kafka");
    appender.start();

    // "org.apache.kafkaconnect" must NOT match the "org.apache.kafka" prefix — a bare
    // startsWith() would wrongly treat it as a child.
    assertThat(appender.isExcluded("org.apache.kafkaconnect.Foo")).isFalse();
    appender.stop();
  }

  @Test
  void isExcluded_selfPackage_isExcludedEvenWithEmptyConfiguredList() {
    LoggerContext context = new LoggerContext();
    NtfyAlertAppender appender = unstartedAppender(context);
    appender.setUrl("http://localhost:1");
    appender.setTopic("alerts");
    appender.start();

    assertThat(appender.isExcluded("io.github.pimak.logbackntfy.NtfyPublisher")).isTrue();
    appender.stop();
  }

  @Test
  void isExcluded_unrelatedLogger_isNotExcluded() {
    LoggerContext context = new LoggerContext();
    NtfyAlertAppender appender = unstartedAppender(context);
    appender.setUrl("http://localhost:1");
    appender.setTopic("alerts");
    appender.setExcludedLoggers("org.apache.kafka");
    appender.start();

    assertThat(appender.isExcluded("com.example.MyService")).isFalse();
    appender.stop();
  }

  @Test
  void append_excludedLoggerEvent_neverReachesPublisher() {
    LoggerContext context = new LoggerContext();
    context.start();
    NtfyAlertAppender appender = unstartedAppender(context);
    appender.setUrl("http://localhost:1"); // unreachable port, would blow up if publish attempted
    appender.setTopic("alerts");
    appender.setExcludedLoggers("org.apache.kafka");
    appender.start();

    appender.doAppend(eventFor(context, "org.apache.kafka.clients.NetworkClient"));
    appender.stop();

    // No publish attempted -> no publishFailed/PUBLISH_UNEXPECTED_ERROR status recorded beyond
    // the two informational lines from start().
    List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
    assertThat(statuses)
        .extracting(Status::getMessage)
        .noneMatch(m -> m.contains("publish") && !m.startsWith("ntfy alert appender"));
  }

  @Test
  void start_withConfiguredExcludeList_emitsExactlyOneStatusExclusionsLine() {
    LoggerContext context = new LoggerContext();
    NtfyAlertAppender appender = unstartedAppender(context);
    appender.setUrl("http://localhost:1");
    appender.setTopic("alerts");
    appender.setExcludedLoggers("org.apache.kafka, com.zaxxer.hikari");
    appender.start();

    List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
    long exclusionLines =
        statuses.stream()
            .map(Status::getMessage)
            .filter(m -> m.startsWith("ntfy alert appender excluded loggers:"))
            .count();
    assertThat(exclusionLines).isEqualTo(1);
    assertThat(statuses)
        .extracting(Status::getMessage)
        .anyMatch(m -> m.contains("org.apache.kafka") && m.contains("com.zaxxer.hikari"));
    appender.stop();
  }

  @Test
  void start_withNoExcludeListConfigured_emitsNoExcludedLoggersLine() {
    LoggerContext context = new LoggerContext();
    NtfyAlertAppender appender = unstartedAppender(context);
    appender.setUrl("http://localhost:1");
    appender.setTopic("alerts");
    appender.start();

    List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
    assertThat(statuses)
        .extracting(Status::getMessage)
        .anyMatch(m -> m.equals("ntfy alert appender: no excluded loggers configured"));
    appender.stop();
  }
}
