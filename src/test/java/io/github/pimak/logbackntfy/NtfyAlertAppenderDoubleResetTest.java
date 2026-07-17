package io.github.pimak.logbackntfy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
import ch.qos.logback.classic.spi.LoggingEvent;

/**
 * Simulates Spring Boot's documented double {@code LoggerContext} init (start/stop/start/stop)
 * and proves the appender's {@code ntfy-alert-http} daemon executor never survives a {@code
 * stop()}, and that a single {@code append()} between the two cycles produces exactly one
 * outbound HTTP call, never a duplicate.
 */
@WireMockTest
class NtfyAlertAppenderDoubleResetTest {

  // Scans both the appender's own executor threads AND the JDK HttpClient's internal
  // selector-manager thread: stop() must release both deterministically.
  private static boolean anyNtfyThreadAlive() {
    return Thread.getAllStackTraces().keySet().stream()
        .anyMatch(
            t ->
                t.isAlive()
                    && (t.getName().startsWith("ntfy-alert-http")
                        || (t.getName().contains("HttpClient-")
                            && t.getName().contains("SelectorManager"))));
  }

  private static void awaitNoNtfyThreads() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 2000;
    while (anyNtfyThreadAlive() && System.currentTimeMillis() < deadline) {
      Thread.sleep(25);
    }
  }

  @Test
  void twoStartStopCycles_leakNoThreadAndSendExactlyOneHttpCall(WireMockRuntimeInfo wm)
      throws InterruptedException {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyAlertAppender appender = new NtfyAlertAppender();
    appender.setContext(new LoggerContext());
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");

    // Cycle 1: simulates the first LoggerContext init.
    appender.start();
    assertThat(appender.isStarted()).isTrue();
    appender.stop();
    assertThat(appender.isStarted()).isFalse();

    awaitNoNtfyThreads();
    assertThat(anyNtfyThreadAlive())
        .as("no ntfy-alert-http thread should survive the first stop()")
        .isFalse();

    // Cycle 2: simulates Spring Boot's documented double-init reset.
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    // Simulated downstream-consumer logger name: NOT under io.github.pimak.logbackntfy, so it is
    // unaffected by the self-package exclusion — this test exercises lifecycle reset, not the
    // exclusion gate.
    LoggerContext eventContext = new LoggerContext();
    Logger logger = eventContext.getLogger("com.example.testapp.SimulatedConsumer");
    LoggingEvent event =
        new LoggingEvent(
            NtfyAlertAppenderDoubleResetTest.class.getName(),
            logger,
            Level.ERROR,
            "boom",
            null,
            null);
    appender.doAppend(event);

    appender.stop();
    assertThat(appender.isStarted()).isFalse();

    awaitNoNtfyThreads();
    assertThat(anyNtfyThreadAlive())
        .as("no ntfy-alert-http thread should survive the second stop()")
        .isFalse();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
  }

  // A second start() without an intervening stop() must be a no-op — without the re-entry guard
  // it would overwrite executor/httpClient/publisher, orphaning the first pool and its selector
  // thread with no way to ever shut them down.
  @Test
  void doubleStartWithoutStop_doesNotLeakThreadsOrDuplicatePublish(WireMockRuntimeInfo wm)
      throws InterruptedException {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyAlertAppender appender = new NtfyAlertAppender();
    appender.setContext(new LoggerContext());
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");

    appender.start();
    assertThat(appender.isStarted()).isTrue();

    // Publish once so the first executor/HttpClient actually spin up their threads — a leak
    // from the double start() below would otherwise be invisible to the thread scan. Uses a
    // simulated downstream-consumer logger name, not under io.github.pimak.logbackntfy, so it is
    // unaffected by the self-package exclusion.
    LoggerContext eventContext = new LoggerContext();
    Logger logger = eventContext.getLogger("com.example.testapp.SimulatedConsumer");
    LoggingEvent event =
        new LoggingEvent(
            NtfyAlertAppenderDoubleResetTest.class.getName(),
            logger,
            Level.ERROR,
            "boom",
            null,
            null);
    appender.doAppend(event);

    // Second start() with no stop() in between: must be a guarded no-op.
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    appender.stop();
    assertThat(appender.isStarted()).isFalse();

    awaitNoNtfyThreads();
    assertThat(anyNtfyThreadAlive())
        .as("a single stop() must release every thread even after a double start()")
        .isFalse();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
  }
}
