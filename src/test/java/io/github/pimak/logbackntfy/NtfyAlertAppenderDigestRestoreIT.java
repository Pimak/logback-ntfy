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
 *
 * <p>This test previously flaked (~1-in-2 on a slow/loaded runner) because its wait loop only
 * checked WireMock's request journal — proof the second digest POST was RECEIVED, not proof the
 * appender's own {@code "ntfy-alert-digest"} thread had finished consuming the response.
 * Reproduced deterministically (via an artificial response delay): the status-code sequence was
 * always one 500 then two 200s, with the third 200 arriving only after {@code appender.stop()} —
 * confirming a JDK {@code HttpClient} interrupt race (not a WireMock {@code Scenario}
 * state-transition race, which would instead have produced two 500s). {@link
 * #httpClientStillInFlight} closes that window by waiting for the digest thread's own stack to
 * leave the HTTP client implementation before {@code stop()} runs.
 */
@WireMockTest
class NtfyAlertAppenderDigestRestoreIT {

  private static final int MAX_ALERTS_PER_WINDOW = 3;
  private static final int TOTAL_EVENTS = 10;
  private static final int EXPECTED_SUPPRESSED = TOTAL_EVENTS - MAX_ALERTS_PER_WINDOW; // 7
  private static final String DIGEST_THREAD_NAME = "ntfy-alert-digest";

  private static ILoggingEvent errorEvent(LoggerContext context, String message) {
    // Simulated downstream-consumer logger name: NOT under io.github.pimak.logbackntfy, so it is
    // unaffected by the self-package exclusion (same idiom as NtfyAlertAppenderDoubleResetTest).
    Logger logger = context.getLogger("com.example.testapp.SimulatedConsumer");
    return new LoggingEvent(
        NtfyAlertAppenderDigestRestoreIT.class.getName(), logger, Level.ERROR, message, null, null);
  }

  /**
   * Finds the appender's single named digest-scheduler thread, if currently alive. Package-name
   * lookup by thread name only — no reflection into appender internals.
   */
  private static Thread findThreadByName(String name) {
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if (name.equals(t.getName())) {
        return t;
      }
    }
    return null;
  }

  /**
   * True while {@code thread}'s current stack still shows an HTTP-client frame, i.e. it is still
   * inside (or blocked inside) {@code HttpClient.send()} for the digest POST — proof the response
   * has not yet been fully consumed, unlike WireMock's request journal which only proves the
   * request was received. Matches both the public {@code java.net.http} API package AND the JDK's
   * actual (internal) implementation package {@code jdk.internal.net.http} — the calling thread
   * blocks inside the latter via {@code CompletableFuture.get()}, so checking only the public
   * package misses the in-flight state entirely. A null/dead thread (already returned to the
   * pool's idle wait, or torn down) is never "in flight".
   */
  private static boolean httpClientStillInFlight(Thread thread) {
    if (thread == null || !thread.isAlive()) {
      return false;
    }
    for (StackTraceElement frame : thread.getStackTrace()) {
      if (frame.getClassName().contains(".net.http.")) {
        return true;
      }
    }
    return false;
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

    // The check above only proves WireMock RECEIVED the second digest POST — the digest
    // scheduler thread's synchronous httpClient.send() call for that same request may still be
    // in flight at this exact instant. Calling stop() here would race
    // digestScheduler.shutdownNow()'s interrupt against that in-progress send(): the interrupt
    // can land inside HttpClient even after the response already arrived, making
    // NtfyPublisher.publish() report a spurious "interrupted" failure, which emitDigest()
    // re-restore()s — triggering an unwanted THIRD digest flush from stop() itself. Wait until
    // the digest thread's stack has left the HTTP client implementation (response fully
    // consumed) before proceeding, closing the race window this test previously flaked on.
    long consumedDeadline = System.currentTimeMillis() + 5_000;
    Thread digestThread = findThreadByName(DIGEST_THREAD_NAME);
    while (System.currentTimeMillis() < consumedDeadline
        && httpClientStillInFlight(digestThread)) {
      Thread.sleep(5);
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
