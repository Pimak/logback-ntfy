package io.github.pimak.logbackntfy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;

/**
 * End-to-end regression: an ERROR event carrying a REAL throwable must actually be delivered to
 * ntfy — the HTTP POST must reach the server, not die at the header-build boundary.
 *
 * <p>This closes a coverage gap: the exception-suffixed title used to contain a U+2014 em dash,
 * which the JDK {@code HttpClient} rejects as an invalid header value; the resulting {@code
 * IllegalArgumentException} was swallowed as a generic failure and NO request was ever sent. No
 * prior test drove a throwable-carrying event through the appender to the wire. Reverting the
 * ASCII-separator fix in {@link PayloadBuilder} makes these tests fail — that is the regression
 * guarantee.
 *
 * <p>Name ends in {@code IT} so Failsafe picks this up under {@code mvn verify} (real loopback HTTP
 * call — integration-test territory).
 */
@WireMockTest
class NtfyAlertAppenderExceptionDeliveryIT {

  private static NtfyAlertAppender startedAppender(int wireMockPort) {
    NtfyAlertAppender appender = new NtfyAlertAppender();
    appender.setContext(new LoggerContext());
    appender.setUrl("http://localhost:" + wireMockPort);
    appender.setTopic("alerts");
    return appender;
  }

  private static LoggingEvent exceptionEvent() {
    // Simulated downstream-consumer logger name: NOT under io.github.pimak.logbackntfy, so it is
    // unaffected by the self-package exclusion — this IT exercises the real end-to-end delivery
    // path, not the exclusion gate.
    LoggerContext eventContext = new LoggerContext();
    Logger logger = eventContext.getLogger("com.example.testapp.SimulatedConsumer");
    IllegalStateException root = new IllegalStateException("root cause");
    RuntimeException surface = new RuntimeException("surface", root);
    return new LoggingEvent(
        NtfyAlertAppenderExceptionDeliveryIT.class.getName(),
        logger,
        Level.ERROR,
        "boom",
        surface,
        null);
  }

  @Test
  void append_eventWithRealException_postReachesStubWithNonBlankTitle(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyAlertAppender appender = startedAppender(wm.getHttpPort());
    appender.setTitle("MyApp");
    appender.start();

    appender.doAppend(exceptionEvent());
    appender.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    // The exception-suffixed title survived the HttpClient's header validation and was sent —
    // this is the exact path the em dash used to kill.
    assertThat(requests.get(0).getHeader("Title"))
        .isEqualTo("MyApp - java.lang.IllegalStateException");
  }

  @Test
  void append_nonAsciiAppNameWithRealException_stillDelivers(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyAlertAppender appender = startedAppender(wm.getHttpPort());
    appender.setAppName("Café");
    appender.start();

    appender.doAppend(exceptionEvent());
    appender.stop();

    // A non-ASCII configured appName must not kill delivery either: the publisher RFC-2047
    // encodes the Title header instead of letting HttpClient reject it.
    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests.get(0).getHeader("Title")).startsWith("=?UTF-8?B?").endsWith("?=");
  }
}
