package io.github.pimak.logbackntfy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

/**
 * Wire-format and no-credential-leak regression coverage for {@link NtfyPublisher}, run against a
 * local {@code @WireMockTest} stub (no Docker, no external network).
 *
 * <p>Name ends in {@code IT} so Failsafe picks this up under {@code mvn verify}, not Surefire under
 * {@code mvn test} (the file makes a real loopback HTTP call, which is integration-test territory
 * even though it never leaves the machine).
 */
@WireMockTest
class NtfyPublisherWireMockIT {

  private final NtfyPublisher publisher = new NtfyPublisher(HttpClient.newHttpClient());

  @Test
  void publish_bearerAuth_sendsBearerHeader(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/mytopic")).willReturn(aResponse().withStatus(200)));

    publisher.publish(
        "http://localhost:" + wm.getHttpPort(),
        "mytopic",
        "Alert",
        AuthMode.bearer("tok123"),
        "Body");

    verify(postRequestedFor(anyUrl()).withHeader("Authorization", equalTo("Bearer tok123")));
  }

  @Test
  void publish_basicAuth_sendsBase64Header(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/mytopic")).willReturn(aResponse().withStatus(200)));

    publisher.publish(
        "http://localhost:" + wm.getHttpPort(),
        "mytopic",
        "Alert",
        AuthMode.basic("user", "pass"),
        "Body");

    verify(
        postRequestedFor(anyUrl())
            .withHeader(
                "Authorization",
                equalTo(
                    "Basic "
                        + Base64.getEncoder()
                            .encodeToString("user:pass".getBytes(StandardCharsets.UTF_8)))));
  }

  @Test
  void publish_anonymous_sendsNoAuthorizationHeader(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/mytopic")).willReturn(aResponse().withStatus(200)));

    publisher.publish(
        "http://localhost:" + wm.getHttpPort(), "mytopic", "Alert", AuthMode.none(), "Body");

    verify(postRequestedFor(anyUrl()).withoutHeader("Authorization"));
  }

  // Token-wins precedence now lives in AuthMode.fromCredentials — this asserts the factory
  // still produces a bearer mode (and that bearer mode still sends the expected header) when
  // both a token and a username/password pair are supplied.
  @Test
  void publish_tokenAndBasicBothSupplied_tokenWins(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/mytopic")).willReturn(aResponse().withStatus(200)));

    AuthMode auth = AuthMode.fromCredentials("tok123", "user", "pass");
    publisher.publish("http://localhost:" + wm.getHttpPort(), "mytopic", "Alert", auth, "Body");

    verify(postRequestedFor(anyUrl()).withHeader("Authorization", equalTo("Bearer tok123")));
  }

  @Test
  void publish_success_returnsSuccessResultWithStatus(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/mytopic")).willReturn(aResponse().withStatus(200)));

    PublishResult result =
        publisher.publish(
            "http://localhost:" + wm.getHttpPort(),
            "mytopic",
            "Alert",
            AuthMode.bearer("tok"),
            "Body");

    assertThat(result.success()).isTrue();
    assertThat(result.httpStatus()).isEqualTo(200);
  }

  @Test
  void publish_serverError_returnsFailureResultWithStatus(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/mytopic")).willReturn(aResponse().withStatus(500)));

    PublishResult result =
        publisher.publish(
            "http://localhost:" + wm.getHttpPort(),
            "mytopic",
            "Alert",
            AuthMode.bearer("tok"),
            "Body");

    assertThat(result.success()).isFalse();
    assertThat(result.httpStatus()).isEqualTo(500);
  }

  @Test
  void publish_bodyAndTitle_sentVerbatim(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/mytopic")).willReturn(aResponse().withStatus(200)));

    publisher.publish(
        "http://localhost:" + wm.getHttpPort(),
        "mytopic",
        "Alert Title",
        AuthMode.bearer("tok"),
        "The body text");

    verify(
        postRequestedFor(anyUrl())
            .withHeader("Title", equalTo("Alert Title"))
            .withRequestBody(equalTo("The body text")));
  }

  // Regression: a non-ASCII title (em dash, accents) must not be rejected by the JDK
  // HttpClient's header validation — it is RFC-2047-encoded and the request IS sent.
  @Test
  void publish_nonAsciiTitle_sentAsRfc2047EncodedWordAndRequestIsSent(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/mytopic")).willReturn(aResponse().withStatus(200)));
    String nonAsciiTitle = "Erreur critique — café";
    String expectedEncodedWord =
        "=?UTF-8?B?"
            + Base64.getEncoder().encodeToString(nonAsciiTitle.getBytes(StandardCharsets.UTF_8))
            + "?=";

    PublishResult result =
        publisher.publish(
            "http://localhost:" + wm.getHttpPort(),
            "mytopic",
            nonAsciiTitle,
            AuthMode.bearer("tok"),
            "Body");

    assertThat(result.success()).isTrue();
    verify(1, postRequestedFor(anyUrl()).withHeader("Title", equalTo(expectedEncodedWord)));
  }

  // Regression: a credential containing an illegal HTTP header character (e.g. a newline) makes
  // HttpRequest.Builder.header() throw an IllegalArgumentException whose message embeds the
  // offending value verbatim — the failure result must never surface that message, and no
  // request must be sent.
  @Test
  void publish_credentialWithIllegalHeaderCharacter_doesNotLeakCredentialInErrorMessage(
      WireMockRuntimeInfo wm) {
    String secretWithControlChar = "super-secret-token\nEvil-Header: injected";

    PublishResult result =
        publisher.publish(
            "http://localhost:" + wm.getHttpPort(),
            "mytopic",
            "Alert",
            AuthMode.bearer(secretWithControlChar),
            "Body");

    assertThat(result.success()).isFalse();
    assertThat(result.message()).doesNotContain(secretWithControlChar);
    assertThat(result.message()).doesNotContain("super-secret-token");
    // Anchor: the failure message is a fixed classification string, never raw exception text.
    assertThat(result.message()).startsWith("invalid request");
    verify(0, postRequestedFor(anyUrl()));
  }

  @Test
  void publish_priorityAndTags_sentAsHeaders(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/mytopic")).willReturn(aResponse().withStatus(200)));

    publisher.publish(
        "http://localhost:" + wm.getHttpPort(),
        "mytopic",
        "Alert",
        AuthMode.bearer("tok"),
        "Body",
        "urgent",
        "fire");

    verify(
        postRequestedFor(anyUrl())
            .withHeader("Priority", equalTo("urgent"))
            .withHeader("Tags", equalTo("fire")));
  }

  @Test
  void publish_differentPriorityAndTags_sentAsHeaders(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/mytopic")).willReturn(aResponse().withStatus(200)));

    publisher.publish(
        "http://localhost:" + wm.getHttpPort(),
        "mytopic",
        "Alert",
        AuthMode.bearer("tok"),
        "Body",
        "high",
        "rotating_light");

    verify(
        postRequestedFor(anyUrl())
            .withHeader("Priority", equalTo("high"))
            .withHeader("Tags", equalTo("rotating_light")));
  }

  @Test
  void publish_blankPriorityAndTags_sendsNeitherHeader(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/mytopic")).willReturn(aResponse().withStatus(200)));

    publisher.publish(
        "http://localhost:" + wm.getHttpPort(),
        "mytopic",
        "Alert",
        AuthMode.bearer("tok"),
        "Body",
        "",
        "");

    verify(postRequestedFor(anyUrl()).withoutHeader("Priority").withoutHeader("Tags"));
  }
}
