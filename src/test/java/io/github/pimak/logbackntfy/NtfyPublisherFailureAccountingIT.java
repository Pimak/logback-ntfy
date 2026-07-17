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

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.util.Duration;

/**
 * Proves a failed individual publish (simulated 500 here — 429/5xx/network are all handled
 * identically by {@link NtfyPublisher}, classified purely on {@code PublishResult.success()}) folds
 * into the suppression count instead of being lost, surfacing later in the aggregated digest.
 */
@WireMockTest
class NtfyPublisherFailureAccountingIT {

  private static final int MAX_ALERTS_PER_WINDOW = 3;

  private static ILoggingEvent errorEvent(LoggerContext context, String message) {
    // Simulated downstream-consumer logger name: NOT under io.github.pimak.logbackntfy, so it is
    // unaffected by the self-package exclusion (same idiom as NtfyAlertAppenderDoubleResetTest).
    Logger logger = context.getLogger("com.example.testapp.SimulatedConsumer");
    return new LoggingEvent(
        NtfyPublisherFailureAccountingIT.class.getName(), logger, Level.ERROR, message, null, null);
  }

  @Test
  void failedIndividualPublish_foldsIntoDigestCount(WireMockRuntimeInfo wm)
      throws InterruptedException {
    // Every individual publish attempt fails (500) — all within the allowance, so each one
    // attempts to publish and fails, folding into the suppression count.
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(500)));

    LoggerContext context = new LoggerContext();
    NtfyAlertAppender appender = new NtfyAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");
    appender.setMaxAlertsPerWindow(MAX_ALERTS_PER_WINDOW);
    appender.setSuppressionWindow(new Duration(300L));
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    for (int i = 0; i < MAX_ALERTS_PER_WINDOW; i++) {
      appender.doAppend(errorEvent(context, "boom " + i));
    }

    // All 3 individual attempts failed (500) — each folds into suppression. After the window
    // closes, exactly the count of failed sends should show up in the digest body: the digest
    // publish itself succeeds (WireMock only stubs /alerts to 500 for the individual sends
    // already made; the digest is also posted to /alerts and would also see 500, re-folding the
    // count, so the stub is switched to succeed after the individual attempts have been made).
    long attemptDeadline = System.currentTimeMillis() + 1000;
    while (System.currentTimeMillis() < attemptDeadline
        && com.github.tomakehurst.wiremock.client.WireMock.findAll(
                    postRequestedFor(urlEqualTo("/alerts")))
                .size()
            < MAX_ALERTS_PER_WINDOW) {
      Thread.sleep(25);
    }
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    long deadline = System.currentTimeMillis() + 2000;
    while (System.currentTimeMillis() < deadline
        && com.github.tomakehurst.wiremock.client.WireMock.findAll(
                postRequestedFor(urlEqualTo("/alerts")).withHeader("Priority", equalTo("urgent")))
            .isEmpty()) {
      Thread.sleep(25);
    }

    appender.stop();

    verify(
        1,
        postRequestedFor(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("urgent"))
            .withHeader("Tags", equalTo("fire"))
            .withRequestBody(containing(MAX_ALERTS_PER_WINDOW + " errors suppressed")));
  }
}
