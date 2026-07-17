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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.util.Duration;

/**
 * Proves a burst above the allowance produces exactly one aggregated digest at window close with
 * {@code count == events - allowance}, that individual alerts within the allowance carry {@link
 * NtfyAlertAppender}'s error priority/tags, and that the digest itself carries the digest
 * priority/tags — never a second digest for the same suppressed batch.
 */
@WireMockTest
class NtfyAlertAppenderDigestIT {

  private static final int MAX_ALERTS_PER_WINDOW = 3;
  private static final int TOTAL_EVENTS = 10;
  private static final int EXPECTED_SUPPRESSED = TOTAL_EVENTS - MAX_ALERTS_PER_WINDOW; // 7

  private static ILoggingEvent errorEvent(LoggerContext context, String message) {
    // Simulated downstream-consumer logger name: NOT under io.github.pimak.logbackntfy, so it is
    // unaffected by the self-package exclusion (same idiom as NtfyAlertAppenderDoubleResetTest).
    Logger logger = context.getLogger("com.example.testapp.SimulatedConsumer");
    return new LoggingEvent(
        NtfyAlertAppenderDigestIT.class.getName(), logger, Level.ERROR, message, null, null);
  }

  @Test
  void burstAboveAllowance_producesExactlyOneDigestWithSuppressedCount(WireMockRuntimeInfo wm)
      throws InterruptedException {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    LoggerContext context = new LoggerContext();
    NtfyAlertAppender appender = new NtfyAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");
    appender.setMaxAlertsPerWindow(MAX_ALERTS_PER_WINDOW);
    // The window must be comfortably longer than the burst's synchronous HTTP round-trips
    // (cold-JVM WireMock/HttpClient first requests can cost hundreds of ms) — a shorter window
    // could roll over mid-burst, refill the allowance, and leak a 4th individual publish,
    // flaking both assertions below.
    appender.setSuppressionWindow(new Duration(2_000L));
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    for (int i = 0; i < TOTAL_EVENTS; i++) {
      appender.doAppend(errorEvent(context, "boom " + i));
    }

    // Await one window close: poll until the digest arrives (deadline well above the 2s
    // window so a slow CI runner does not flake).
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline
        && WireMock.findAll(postRequestedFor(urlEqualTo("/alerts"))).size()
            < MAX_ALERTS_PER_WINDOW + 1) {
      Thread.sleep(25);
    }

    appender.stop();

    verify(
        MAX_ALERTS_PER_WINDOW,
        postRequestedFor(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("high"))
            .withHeader("Tags", equalTo("rotating_light")));

    verify(
        1,
        postRequestedFor(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("urgent"))
            .withHeader("Tags", equalTo("fire"))
            .withRequestBody(containing(EXPECTED_SUPPRESSED + " errors suppressed")));
  }
}
