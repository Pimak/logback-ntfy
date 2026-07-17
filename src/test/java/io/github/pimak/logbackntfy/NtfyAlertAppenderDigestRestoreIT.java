package io.github.pimak.logbackntfy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.util.Duration;

/**
 * Proves a failed digest publish carries the drained suppression count forward via {@link
 * AlertRateLimiter#restore} instead of silently dropping it — the next window's digest reports
 * the same suppressed total. Digest requests are told apart from individual alerts by their
 * {@code Priority: urgent} header, and a WireMock scenario fails only the FIRST digest POST (500)
 * so no stub swap can race the digest scheduler. If {@code restore()} were missing, the second
 * window would drain a zero count and never send a digest at all, failing the count assertion
 * below.
 */
@WireMockTest
class NtfyAlertAppenderDigestRestoreIT {

  private static final int MAX_ALERTS_PER_WINDOW = 3;
  private static final int TOTAL_EVENTS = 10;
  private static final int EXPECTED_SUPPRESSED = TOTAL_EVENTS - MAX_ALERTS_PER_WINDOW; // 7

  private static ILoggingEvent errorEvent(LoggerContext context, String message) {
    // Simulated downstream-consumer logger name: NOT under io.github.pimak.logbackntfy, so it is
    // unaffected by the self-package exclusion (same idiom as NtfyAlertAppenderDoubleResetTest).
    Logger logger = context.getLogger("com.example.testapp.SimulatedConsumer");
    return new LoggingEvent(
        NtfyAlertAppenderDigestRestoreIT.class.getName(), logger, Level.ERROR, message, null, null);
  }

  @Test
  void failedDigestPublish_carriesSuppressedCountIntoNextWindowsDigest(WireMockRuntimeInfo wm)
      throws InterruptedException {
    // Individual alerts (Priority: high) always succeed — this test isolates the DIGEST failure
    // path, unlike NtfyPublisherFailureAccountingIT which exercises failed individual sends.
    stubFor(
        post(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("high"))
            .willReturn(aResponse().withStatus(200)));
    // Digest scenario: the first digest POST fails (500 -> emitDigest() calls restore(snap)),
    // every subsequent digest POST succeeds.
    stubFor(
        post(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("urgent"))
            .inScenario("digest-restore")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("first-digest-failed"));
    stubFor(
        post(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("urgent"))
            .inScenario("digest-restore")
            .whenScenarioStateIs("first-digest-failed")
            .willReturn(aResponse().withStatus(200)));

    LoggerContext context = new LoggerContext();
    NtfyAlertAppender appender = new NtfyAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");
    appender.setMaxAlertsPerWindow(MAX_ALERTS_PER_WINDOW);
    // Window comfortably longer than the burst's synchronous HTTP round-trips (same rationale as
    // NtfyAlertAppenderDigestIT) so the allowance cannot refill mid-burst.
    appender.setSuppressionWindow(new Duration(2_000L));
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    for (int i = 0; i < TOTAL_EVENTS; i++) {
      appender.doAppend(errorEvent(context, "boom " + i));
    }

    // Await TWO digest attempts: the failed first-window one plus the carried-forward retry at
    // the next tick (deadline well above two 2s windows so a slow CI runner does not flake).
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline
        && WireMock.findAll(
                    postRequestedFor(urlEqualTo("/alerts"))
                        .withHeader("Priority", equalTo("urgent")))
                .size()
            < 2) {
      Thread.sleep(25);
    }

    appender.stop();

    // Both digest attempts carry the SAME suppressed total: the first drained it and failed, the
    // second exists only because restore() re-folded the snapshot (count + per-logger tally).
    verify(
        2,
        postRequestedFor(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("urgent"))
            .withHeader("Tags", equalTo("fire"))
            .withRequestBody(containing(EXPECTED_SUPPRESSED + " errors suppressed")));
    // The per-logger tally was restored too, not just the global count.
    verify(
        2,
        postRequestedFor(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("urgent"))
            .withRequestBody(containing("com.example.testapp.SimulatedConsumer")));
  }
}
