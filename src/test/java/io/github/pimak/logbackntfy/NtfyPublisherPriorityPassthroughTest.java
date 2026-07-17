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

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

/**
 * Regression: {@link NtfyPublisher} does not validate {@code priority}/{@code tags} values. An
 * arbitrary, non-standard priority string must be forwarded verbatim to the wire and must not be
 * rejected locally — priority/tags are operator config, not untrusted external input.
 */
@WireMockTest
class NtfyPublisherPriorityPassthroughTest {

  private final NtfyPublisher publisher = new NtfyPublisher(HttpClient.newHttpClient());

  @Test
  void publish_arbitraryPriorityValue_forwardedVerbatimNoLocalRejection(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/mytopic")).willReturn(aResponse().withStatus(200)));

    PublishResult result =
        publisher.publish(
            "http://localhost:" + wm.getHttpPort(),
            "mytopic",
            "Alert",
            AuthMode.bearer("tok"),
            "Body",
            "banana",
            null);

    assertThat(result.success()).isTrue();
    verify(postRequestedFor(anyUrl()).withHeader("Priority", equalTo("banana")));
  }
}
