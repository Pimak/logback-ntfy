package io.github.pimak.logbackntfy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.util.Duration;

/**
 * Proves {@code append()}'s failure path reports exclusively through the inherited StatusManager
 * methods (never an SLF4J logger, so zero recursive {@code ILoggingEvent}s are ever generated)
 * and never leaks the configured token into any status entry, even on a simulated 401/403 ntfy
 * response.
 */
@WireMockTest
class NtfyAlertAppenderStatusManagerTest {

  private static final String SECRET_TOKEN = "SECRET-TOKEN-123";

  private static NtfyAlertAppender startedAppender(LoggerContext context, int wireMockPort) {
    NtfyAlertAppender appender = new NtfyAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:" + wireMockPort);
    appender.setTopic("alerts");
    appender.setToken(SECRET_TOKEN);
    appender.start();
    return appender;
  }

  private static ILoggingEvent errorEvent(LoggerContext context, String message) {
    // Simulated downstream-consumer logger name: NOT under io.github.pimak.logbackntfy, so the
    // self-package exclusion does not gate the event out before it reaches the publish path
    // these tests exist to exercise.
    Logger logger = context.getLogger("com.example.testapp.SimulatedConsumer");
    return new LoggingEvent(
        NtfyAlertAppenderStatusManagerTest.class.getName(),
        logger,
        Level.ERROR,
        message,
        null,
        null);
  }

  @Test
  void append_serverReturns401_reportsFailureViaStatusManagerNeverLeakingToken(
      WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(401)));

    LoggerContext context = new LoggerContext();
    NtfyAlertAppender appender = startedAppender(context, wm.getHttpPort());

    appender.doAppend(errorEvent(context, "database connection failed"));
    appender.stop();

    List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
    assertThat(statuses).isNotEmpty();
    assertThat(statuses).extracting(Status::getMessage).noneMatch(m -> m.contains(SECRET_TOKEN));
  }

  @Test
  void append_serverReturns403_reportsFailureViaStatusManagerNeverLeakingToken(
      WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(403)));

    LoggerContext context = new LoggerContext();
    NtfyAlertAppender appender = startedAppender(context, wm.getHttpPort());

    appender.doAppend(errorEvent(context, "database connection failed"));
    appender.stop();

    List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
    assertThat(statuses).isNotEmpty();
    assertThat(statuses).extracting(Status::getMessage).noneMatch(m -> m.contains(SECRET_TOKEN));
  }

  @Test
  void append_publishFailure_generatesNoRecursiveLoggingEvent(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(500)));

    LoggerContext context = new LoggerContext();
    context.start();

    ListAppender<ILoggingEvent> capture = new ListAppender<>();
    capture.setContext(context);
    capture.start();

    NtfyAlertAppender appender = new NtfyAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");
    appender.setToken(SECRET_TOKEN);
    appender.start();

    Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
    rootLogger.setLevel(Level.ERROR);
    // Both appenders are attached to the same logger, dispatched by a single real SLF4J call: if
    // append()'s failure path ever called an SLF4J logger (the recursion trap this test guards
    // against), that second call would also route through this same logger and land in
    // `capture` too.
    rootLogger.addAppender(capture);
    rootLogger.addAppender(appender);

    rootLogger.error("boom");

    appender.stop();

    assertThat(capture.list).hasSize(1);

    List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
    assertThat(statuses).isNotEmpty();
    assertThat(statuses).extracting(Status::getMessage).noneMatch(m -> m.contains(SECRET_TOKEN));
  }

  /**
   * Regression coverage: a burst above the allowance triggers the digest-publish path (and its
   * exclusion/status diagnostics) in addition to the individual-alert path already covered
   * above; neither may ever leak the configured token into a StatusManager message.
   */
  @Test
  void startBurstStop_noStatusManagerMessageEverContainsTheToken(WireMockRuntimeInfo wm)
      throws InterruptedException {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    LoggerContext context = new LoggerContext();
    NtfyAlertAppender appender = new NtfyAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");
    appender.setToken(SECRET_TOKEN);
    appender.setMaxAlertsPerWindow(2);
    appender.setSuppressionWindow(new Duration(300L));
    appender.start();

    for (int i = 0; i < 5; i++) {
      appender.doAppend(errorEvent(context, "burst " + i));
    }

    // Give the digest timer a chance to fire before stop()'s own flush runs.
    Thread.sleep(400);

    appender.stop();

    List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
    assertThat(statuses).isNotEmpty();
    assertThat(statuses).extracting(Status::getMessage).noneMatch(m -> m.contains(SECRET_TOKEN));
  }
}
