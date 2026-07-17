package io.github.pimak.logbackntfy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.status.Status;

/**
 * Pure-unit coverage of {@link
 * NtfyAlertAppender#hasNoAlertMarker(ch.qos.logback.classic.spi.ILoggingEvent)} and the public
 * {@link NtfyAlertAppender#NO_ALERT_MARKER_NAME} constant. No WireMock — the gate decision is
 * tested directly.
 */
class NoAlertMarkerGateTest {

  private static NtfyAlertAppender unstartedAppender(LoggerContext context) {
    NtfyAlertAppender appender = new NtfyAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:1");
    appender.setTopic("alerts");
    appender.start();
    return appender;
  }

  private static LoggingEvent eventWithMarkers(LoggerContext context, Marker... markers) {
    Logger logger = context.getLogger(NoAlertMarkerGateTest.class);
    LoggingEvent event =
        new LoggingEvent(Logger.class.getName(), logger, Level.ERROR, "boom", null, null);
    for (Marker marker : markers) {
      event.addMarker(marker);
    }
    return event;
  }

  @Test
  void constant_isStableAndMatchesMarkerName() {
    assertThat(NtfyAlertAppender.NO_ALERT_MARKER_NAME).isEqualTo("NO_ALERT");
  }

  @Test
  void hasNoAlertMarker_directNoAlertMarker_isGated() {
    LoggerContext context = new LoggerContext();
    NtfyAlertAppender appender = unstartedAppender(context);

    Marker noAlert = MarkerFactory.getMarker(NtfyAlertAppender.NO_ALERT_MARKER_NAME);
    LoggingEvent event = eventWithMarkers(context, noAlert);

    assertThat(appender.hasNoAlertMarker(event)).isTrue();
    appender.stop();
  }

  @Test
  void hasNoAlertMarker_compositeMarkerReferencingNoAlertAsChild_isGated() {
    LoggerContext context = new LoggerContext();
    NtfyAlertAppender appender = unstartedAppender(context);

    Marker child = MarkerFactory.getMarker(NtfyAlertAppender.NO_ALERT_MARKER_NAME);
    Marker composite = MarkerFactory.getMarker("PARENT_MARKER");
    composite.add(child);
    LoggingEvent event = eventWithMarkers(context, composite);

    assertThat(appender.hasNoAlertMarker(event)).isTrue();
    appender.stop();
  }

  @Test
  void hasNoAlertMarker_noMarkers_isNotGated() {
    LoggerContext context = new LoggerContext();
    NtfyAlertAppender appender = unstartedAppender(context);

    LoggingEvent event = eventWithMarkers(context);

    assertThat(appender.hasNoAlertMarker(event)).isFalse();
    appender.stop();
  }

  @Test
  void hasNoAlertMarker_unrelatedMarker_isNotGated() {
    LoggerContext context = new LoggerContext();
    NtfyAlertAppender appender = unstartedAppender(context);

    Marker unrelated = MarkerFactory.getMarker("SOME_OTHER_MARKER");
    LoggingEvent event = eventWithMarkers(context, unrelated);

    assertThat(appender.hasNoAlertMarker(event)).isFalse();
    appender.stop();
  }

  @Test
  void append_noAlertMarkedEvent_neverReachesPublisher() {
    LoggerContext context = new LoggerContext();
    context.start();
    NtfyAlertAppender appender = unstartedAppender(context);

    Marker noAlert = MarkerFactory.getMarker(NtfyAlertAppender.NO_ALERT_MARKER_NAME);
    LoggingEvent event = eventWithMarkers(context, noAlert);

    appender.doAppend(event);
    appender.stop();

    List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
    assertThat(statuses)
        .extracting(Status::getMessage)
        .noneMatch(m -> m.contains("publish") && !m.startsWith("ntfy alert appender"));
  }
}
