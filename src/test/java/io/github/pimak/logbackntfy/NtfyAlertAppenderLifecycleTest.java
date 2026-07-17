package io.github.pimak.logbackntfy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import ch.qos.logback.core.ContextBase;
import ch.qos.logback.core.status.Status;

/**
 * Proves the silent-inactive vs loud-partial-config distinction and the token-wins-with-warning
 * activation path. No HTTP call is ever made in these tests — {@code append()} is never invoked,
 * only {@code start()}/{@code stop()}.
 */
class NtfyAlertAppenderLifecycleTest {

  private static NtfyAlertAppender newAppender() {
    NtfyAlertAppender appender = new NtfyAlertAppender();
    appender.setContext(new ContextBase());
    return appender;
  }

  @Test
  void start_urlAndTopicBothUnset_staysInactiveWithInfoStatusOnly() {
    NtfyAlertAppender appender = newAppender();

    appender.start();

    assertThat(appender.isStarted()).isFalse();
    List<Status> statuses = appender.getStatusManager().getCopyOfStatusList();
    assertThat(statuses).hasSize(1);
    assertThat(statuses.get(0).getLevel()).isEqualTo(Status.INFO);
    assertThat(statuses.get(0).getMessage()).isEqualTo(AlertMessages.STATUS_DISABLED_UNCONFIGURED);
  }

  @Test
  void start_onlyUrlSet_staysInactiveWithPartialConfigWarn() {
    NtfyAlertAppender appender = newAppender();
    appender.setUrl("http://localhost:9999");

    appender.start();

    assertThat(appender.isStarted()).isFalse();
    List<Status> statuses = appender.getStatusManager().getCopyOfStatusList();
    assertThat(statuses).hasSize(1);
    assertThat(statuses.get(0).getLevel()).isEqualTo(Status.WARN);
    assertThat(statuses.get(0).getMessage())
        .isEqualTo(AlertMessages.STATUS_DISABLED_PARTIAL_CONFIG);
  }

  @Test
  void start_onlyTopicSet_staysInactiveWithPartialConfigWarn() {
    NtfyAlertAppender appender = newAppender();
    appender.setTopic("alerts");

    appender.start();

    assertThat(appender.isStarted()).isFalse();
    List<Status> statuses = appender.getStatusManager().getCopyOfStatusList();
    assertThat(statuses).hasSize(1);
    assertThat(statuses.get(0).getLevel()).isEqualTo(Status.WARN);
    assertThat(statuses.get(0).getMessage())
        .isEqualTo(AlertMessages.STATUS_DISABLED_PARTIAL_CONFIG);
  }

  @Test
  void start_urlAndTopicSet_activatesWithInfoStatusNeverContainingToken() {
    NtfyAlertAppender appender = newAppender();
    appender.setUrl("http://localhost:9999");
    appender.setTopic("alerts");
    appender.setToken("SECRET-TOKEN-XYZ");

    appender.start();

    assertThat(appender.isStarted()).isTrue();
    List<Status> statuses = appender.getStatusManager().getCopyOfStatusList();
    assertThat(statuses)
        .extracting(Status::getMessage)
        .noneMatch(m -> m.contains("SECRET-TOKEN-XYZ"));
    assertThat(statuses)
        .extracting(Status::getMessage)
        .anyMatch(m -> m.equals(AlertMessages.statusActive("http://localhost:9999", "alerts")));

    appender.stop();
  }

  @Test
  void start_tokenAndBasicAuthBothSet_activatesWithOneTimeWarnButStillStarts() {
    NtfyAlertAppender appender = newAppender();
    appender.setUrl("http://localhost:9999");
    appender.setTopic("alerts");
    appender.setToken("tok");
    appender.setUsername("user");
    appender.setPassword("pass");

    appender.start();

    assertThat(appender.isStarted()).isTrue();
    List<Status> statuses = appender.getStatusManager().getCopyOfStatusList();
    assertThat(statuses)
        .filteredOn(s -> s.getMessage().equals(AlertMessages.STATUS_TOKEN_AND_BASIC_BOTH_SET))
        .hasSize(1);

    appender.stop();
  }

  @Test
  void stop_neverStarted_isSafeNoException() {
    NtfyAlertAppender appender = newAppender();

    appender.stop();

    assertThat(appender.isStarted()).isFalse();
  }

  @Test
  void timeoutSetters_acceptLogbackDurationType() {
    NtfyAlertAppender appender = newAppender();

    appender.setConnectTimeout(new ch.qos.logback.core.util.Duration(1234));
    appender.setRequestTimeout(ch.qos.logback.core.util.Duration.buildBySeconds(3));
    appender.setUrl("http://localhost:9999");
    appender.setTopic("alerts");
    appender.start();

    assertThat(appender.isStarted()).isTrue();
    appender.stop();
  }
}
