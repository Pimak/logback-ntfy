package io.github.pimak.logbackntfy;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Marker;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

/**
 * A {@code UnsynchronizedAppenderBase<ILoggingEvent>} configured exclusively via JavaBean setters:
 * silently inactive when unconfigured, loudly (but still inactive) warned when partially
 * configured, resource-clean across repeated {@code start()}/{@code stop()} cycles, and
 * self-diagnosing exclusively via the inherited {@code addInfo}/{@code addWarn}/{@code addError}
 * StatusManager methods — so no SLF4J feedback loop is possible and no credential ever appears in
 * diagnostic output.
 *
 * <p>Never reads the process environment and never imports an SLF4J {@code Logger}: environment
 * variable wiring and XML configuration are entirely the consumer's concern.
 */
public class NtfyAlertAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

  private static final ch.qos.logback.core.util.Duration DEFAULT_CONNECT_TIMEOUT =
      new ch.qos.logback.core.util.Duration(5_000L);
  private static final ch.qos.logback.core.util.Duration DEFAULT_REQUEST_TIMEOUT =
      new ch.qos.logback.core.util.Duration(10_000L);
  private static final int DEFAULT_MAX_STACK_FRAMES = 5;

  /** Default burst allowance before storm suppression kicks in. */
  private static final int DEFAULT_MAX_ALERTS_PER_WINDOW = 3;

  /** Default rolling suppression/digest window. */
  private static final ch.qos.logback.core.util.Duration DEFAULT_SUPPRESSION_WINDOW =
      new ch.qos.logback.core.util.Duration(180_000L);

  /**
   * This appender's own package is always excluded from alerting, independent of any
   * configured {@link #setExcludedLoggers(String)} value — a belt-and-suspenders anti-loop guard
   * that survives even a blank/misconfigured exclude-list.
   */
  private static final String SELF_PACKAGE_PREFIX = "io.github.pimak.logbackntfy";

  /**
   * SLF4J marker name a caller attaches to a single log call to opt it out of alerting
   * entirely (checked via {@link Marker#contains(String)}, so a composite marker that references
   * this name as a child also gates the event out). Public so callers have a stable handle instead
   * of a magic string.
   */
  public static final String NO_ALERT_MARKER_NAME = "NO_ALERT";

  private String url;
  private String topic;
  private String token;
  private String username;
  private String password;
  private String title;
  private String appName;
  private String excludedLoggers;
  private int maxStackFrames = DEFAULT_MAX_STACK_FRAMES;
  private ch.qos.logback.core.util.Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
  private ch.qos.logback.core.util.Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

  private int maxAlertsPerWindow = DEFAULT_MAX_ALERTS_PER_WINDOW;
  private ch.qos.logback.core.util.Duration suppressionWindow = DEFAULT_SUPPRESSION_WINDOW;
  private String errorPriority = "high";
  private String digestPriority = "urgent";
  private String errorTags = "rotating_light";
  private String digestTags = "fire";

  // volatile: parsed once in start() (config thread), read on every append() call (application
  // threads) — same publication pattern as `publisher` below.
  private volatile List<String> excludedLoggerPrefixes = Collections.emptyList();

  private ExecutorService executor;
  private HttpClient httpClient;

  // volatile: append() runs on application threads while start()/stop() mutate this field on the
  // config thread (UnsynchronizedAppenderBase concurrency model). The volatile write in start()
  // (last assignment) publishes url/topic/httpClient too; append() snapshots it into a local
  // before use.
  private volatile NtfyPublisher publisher;

  // volatile: same publication pattern as `publisher` — built once in start() (config thread)
  // from the configured token/username/password, read by append()/emitDigest() (application
  // threads), and nulled in stop() alongside publisher.
  private volatile AuthMode authMode;

  // volatile: same append()-vs-stop() concurrency model as `publisher` — a digest-timer
  // tick or an append() call may race a concurrent stop() nulling this field.
  private volatile AlertRateLimiter rateLimiter;

  private ScheduledExecutorService digestScheduler;

  public void setUrl(String url) {
    this.url = url;
  }

  public void setTopic(String topic) {
    this.topic = topic;
  }

  public void setToken(String token) {
    this.token = token;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public void setAppName(String appName) {
    this.appName = appName;
  }

  public void setMaxStackFrames(int maxStackFrames) {
    this.maxStackFrames = maxStackFrames;
  }

  public void setConnectTimeout(ch.qos.logback.core.util.Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public void setRequestTimeout(ch.qos.logback.core.util.Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }

  /** Burst allowance per {@link #suppressionWindow}; {@code <= 0} disables suppression. */
  public void setMaxAlertsPerWindow(int maxAlertsPerWindow) {
    this.maxAlertsPerWindow = maxAlertsPerWindow;
  }

  /** Rolling window used for both the burst allowance and the digest timer. */
  public void setSuppressionWindow(ch.qos.logback.core.util.Duration suppressionWindow) {
    this.suppressionWindow = suppressionWindow;
  }

  /** ntfy {@code Priority} header value for individual (non-suppressed) alerts. */
  public void setErrorPriority(String errorPriority) {
    this.errorPriority = errorPriority;
  }

  /** ntfy {@code Priority} header value for storm digests. */
  public void setDigestPriority(String digestPriority) {
    this.digestPriority = digestPriority;
  }

  /** ntfy {@code Tags} header value for individual (non-suppressed) alerts. */
  public void setErrorTags(String errorTags) {
    this.errorTags = errorTags;
  }

  /** ntfy {@code Tags} header value for storm digests. */
  public void setDigestTags(String digestTags) {
    this.digestTags = digestTags;
  }

  /**
   * A single comma-separated value (not repeated XML elements) of logger-name prefixes
   * to exclude from alerting. Parsed once in {@link #start()} into {@link #excludedLoggerPrefixes}.
   */
  public void setExcludedLoggers(String excludedLoggers) {
    this.excludedLoggers = excludedLoggers;
  }

  /**
   * url+topic both blank -&gt; silently inactive ({@code addInfo}, {@code
   * isStarted()==false}). Exactly one of url/topic blank -&gt; loud {@code addWarn}, still inactive
   * (partial config, likely a typo). Both token and username+password configured -&gt;
   * one-time {@code addWarn}, but activation still proceeds (auth is optional and never
   * blocks activation). Already started -&gt; no-op, so a second {@code start()} without an
   * intervening {@code stop()} can never overwrite and orphan the first executor/HttpClient.
   * Active: build one {@link HttpClient} plus its daemon-thread executor and construct the
   * {@link NtfyPublisher}.
   *
   * <p>Delivery is synchronous by design: {@code HttpClient.send()} blocks the calling (logging)
   * thread for up to connectTimeout + requestTimeout per event. The executor only services the
   * client's internal async tasks — it does NOT offload delivery. Non-blocking delivery is the
   * consumer's concern via an {@code AsyncAppender} wrapper.
   */
  @Override
  public void start() {
    if (isStarted()) {
      return;
    }
    boolean urlSet = !isBlank(url);
    boolean topicSet = !isBlank(topic);
    if (!urlSet && !topicSet) {
      addInfo(AlertMessages.STATUS_DISABLED_UNCONFIGURED);
      return;
    }
    if (urlSet != topicSet) {
      addWarn(AlertMessages.STATUS_DISABLED_PARTIAL_CONFIG);
      return;
    }

    boolean hasToken = !isBlank(token);
    boolean hasBasic = !isBlank(username) && !isBlank(password);
    if (hasToken && hasBasic) {
      addWarn(AlertMessages.STATUS_TOKEN_AND_BASIC_BOTH_SET);
    }
    this.authMode = AuthMode.fromCredentials(token, username, password);

    // Validate schedule parameters BEFORE acquiring any resource. A zero/negative (or
    // nulled-via-setter) window would otherwise throw IllegalArgumentException out of
    // scheduleWithFixedDelay AFTER the executor/HttpClient were built but BEFORE super.start(),
    // leaving a half-initialized appender holding live threads. Deliberate choice: fall back to
    // the default window (loudly) rather than refuse activation — a bad window value should not
    // silence alerting entirely.
    long windowMillis =
        suppressionWindow == null ? 0L : toJavaDuration(suppressionWindow).toMillis();
    if (windowMillis <= 0) {
      windowMillis = DEFAULT_SUPPRESSION_WINDOW.getMilliseconds();
      addWarn(AlertMessages.STATUS_INVALID_SUPPRESSION_WINDOW);
    }

    this.executor =
        Executors.newFixedThreadPool(
            2,
            r -> {
              Thread t = new Thread(r, "ntfy-alert-http");
              t.setDaemon(true);
              return t;
            });
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(toJavaDuration(connectTimeout))
            .executor(executor)
            .build();
    this.publisher = new NtfyPublisher(httpClient, toJavaDuration(requestTimeout));

    this.excludedLoggerPrefixes = parseExcludedLoggers(excludedLoggers);

    this.rateLimiter = new AlertRateLimiter(maxAlertsPerWindow, windowMillis);
    this.digestScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "ntfy-alert-digest");
              t.setDaemon(true);
              return t;
            });
    // scheduleWithFixedDelay (not scheduleAtFixedRate): a slow publish never causes ticks to
    // stack up back-to-back once the digest publish itself is slow. The body is guarded here:
    // scheduleWithFixedDelay permanently cancels the periodic task if any execution
    // throws — an unguarded RuntimeException would silently kill the digest timer forever.
    // Fixed generic message, never e.getMessage() — the exception could embed a credential.
    digestScheduler.scheduleWithFixedDelay(
        () -> {
          try {
            emitDigest();
          } catch (RuntimeException e) {
            addError(AlertMessages.PUBLISH_UNEXPECTED_ERROR, e);
          }
        },
        windowMillis,
        windowMillis,
        TimeUnit.MILLISECONDS);

    addInfo(AlertMessages.statusActive(url, topic)); // never the token
    addInfo(AlertMessages.statusExclusions(excludedLoggerPrefixes)); // exactly once
    super.start();
  }

  private static List<String> parseExcludedLoggers(String configured) {
    if (isBlank(configured)) {
      return Collections.emptyList();
    }
    List<String> prefixes = new ArrayList<>();
    for (String part : configured.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        prefixes.add(trimmed);
      }
    }
    return Collections.unmodifiableList(prefixes);
  }

  /**
   * Releases every resource {@link #start()} acquired. Safe to call when never started (no
   * NPE) and safe to call twice in a row (Spring Boot's documented double {@code LoggerContext}
   * init) — every field is guarded and nulled out.
   */
  @Override
  public void stop() {
    // Ordering matters here: (1) cancel the digest timer FIRST so no new tick can race the
    // flush below; (2) synchronously flush any pending digest while the HTTP resources are
    // STILL LIVE; (3) only then release executor/httpClient/publisher/rateLimiter. Flushing
    // after releasing the HTTP executor would leave the flush publish with no transport.
    if (digestScheduler != null) {
      digestScheduler.shutdownNow();
      awaitTerminationQuietly(digestScheduler);
    }
    digestScheduler = null;

    AlertRateLimiter rl = rateLimiter;
    if (rl != null && rl.hasPending()) {
      emitDigest();
    }

    if (executor != null) {
      executor.shutdownNow();
      // shutdownNow() only requests interruption; without awaiting termination the worker
      // threads (named "ntfy-alert-http") can still be observably alive for a short window
      // after stop() returns, which a global thread-leak scan (e.g. across repeated
      // start()/stop() cycles) can catch as a false leak. Bounded so a stuck thread
      // never hangs stop() itself.
      awaitTerminationQuietly(executor);
    }
    executor = null;
    if (httpClient != null) {
      // Java 21+: releases the client's internal HttpClient-N-SelectorManager thread
      // deterministically without blocking on in-flight requests — GC-driven reclaim is not
      // enough for repeated start/stop cycles.
      httpClient.shutdownNow();
    }
    httpClient = null;
    publisher = null;
    authMode = null;
    rateLimiter = null;
    super.stop();
  }

  /**
   * Assembles the alert payload via {@link PayloadBuilder}, delegates to {@link
   * NtfyPublisher#publish}, and reports any failure exclusively through the inherited StatusManager
   * methods ({@link #addWarn(String)}/{@link #addError(String, Throwable)}) — never an SLF4J
   * logger, never the token. {@code doAppend()} (inherited) already no-ops when {@code
   * isStarted()==false}, so the {@code publisher == null} guard below is defensive, not
   * load-bearing.
   */
  @Override
  protected void append(ILoggingEvent event) {
    // Exclusion and marker gates run BEFORE the publisher snapshot — gated events never
    // publish and are never counted.
    if (isExcluded(event.getLoggerName()) || hasNoAlertMarker(event)) {
      return;
    }
    // Snapshot the volatile fields once: a concurrent stop() nulling `publisher`/`rateLimiter`
    // between a null-check and use would otherwise NPE and be misreported as an ERROR-level
    // "unexpected" failure during a benign shutdown race.
    NtfyPublisher p = publisher;
    if (p == null) {
      return;
    }
    AlertRateLimiter rl = rateLimiter;
    if (rl == null) {
      return;
    }
    AuthMode auth = authMode;
    if (auth == null) {
      return;
    }
    // The rate-limiter gate runs AFTER the exclusion/marker gates above, so it only
    // ever sees non-excluded events. Over-allowance events are counted, never individually
    // published — they surface later as part of the aggregated digest.
    if (!rl.tryAcquire()) {
      rl.recordSuppressed(event.getLoggerName());
      return;
    }
    try {
      AlertPayload payload = PayloadBuilder.build(event, title, appName, maxStackFrames);
      PublishResult result =
          p.publish(
              url,
              topic,
              payload.title(),
              auth,
              payload.body(),
              errorPriority,
              errorTags);
      if (!result.success()) {
        addWarn(AlertMessages.publishFailed(topic, result.httpStatus()));
        // A failed individual publish folds into the suppression count instead of being
        // lost — it will surface in the next digest.
        rl.recordSuppressed(event.getLoggerName());
      }
    } catch (RuntimeException e) {
      // Fixed generic message — never e.getMessage() here, it could embed a credential.
      addError(AlertMessages.PUBLISH_UNEXPECTED_ERROR, e);
    }
  }

  /**
   * The single shared digest-publish code path, invoked both by the {@link
   * #digestScheduler} tick and by {@link #stop()}'s synchronous flush. Snapshots {@link
   * #rateLimiter}/{@link #publisher} defensively (a tick racing {@code stop()} must no-op).
   * Drains the accumulated suppression tally; a zero count means nothing to report, so no
   * digest is sent. If the digest publish itself fails, the drained count is re-folded
   * back into {@link #rateLimiter} (per logger) via {@link AlertRateLimiter#recordSuppressed}
   * rather than silently dropped, so it survives into the next window/flush.
   */
  private void emitDigest() {
    AlertRateLimiter rl = rateLimiter;
    NtfyPublisher p = publisher;
    AuthMode auth = authMode;
    if (rl == null || p == null || auth == null) {
      return;
    }
    AlertRateLimiter.DigestSnapshot snap = rl.drainAndReset();
    if (snap.count() == 0) {
      return;
    }
    String digestTitleText =
        AlertMessages.digestTitle(title != null ? title : appName, snap.count());
    String body =
        AlertMessages.digestBody(
            snap.count(), snap.perLoggerTally(), describeWindow(suppressionWindow));
    String truncatedBody = PayloadTruncator.truncate(body, PayloadTruncator.NTFY_MAX_BYTES);
    PublishResult r =
        p.publish(
            url,
            topic,
            digestTitleText,
            auth,
            truncatedBody,
            digestPriority,
            digestTags);
    if (!r.success()) {
      // Mirror the append() path — a persistently failing digest (auth revoked, topic
      // ACL change, sustained 429) must be visible in the appender's own diagnostics.
      // Composer interpolates only topic + HTTP status, never a credential.
      addWarn(AlertMessages.publishFailed(topic, r.httpStatus()));
      // Carry the lost count forward instead of dropping it — one atomic bulk
      // merge restores the global count from the snapshot's own count() (single source of truth)
      // and the per-logger breakdown, so the next window's digest stays accurate.
      rl.restore(snap);
    }
  }

  /** Human-readable window description for the digest body (e.g. {@code "3 minutes"}). */
  private static String describeWindow(ch.qos.logback.core.util.Duration duration) {
    long ms = duration.getMilliseconds();
    long minutes = ms / 60_000L;
    if (minutes >= 1) {
      return minutes + (minutes == 1 ? " minute" : " minutes");
    }
    long seconds = Math.max(ms / 1_000L, 1L);
    return seconds + (seconds == 1 ? " second" : " seconds");
  }

  /**
   * True when {@code loggerName} is the built-in self-package (always) or matches one of the
   * configured {@link #excludedLoggerPrefixes} at a logger-hierarchy boundary (exact match or
   * {@code prefix + "."} — a bare {@code startsWith} would wrongly match a sibling package like
   * {@code org.apache.kafkaconnect} against the prefix {@code org.apache.kafka}).
   */
  boolean isExcluded(String loggerName) {
    if (matchesPrefix(loggerName, SELF_PACKAGE_PREFIX)) {
      return true;
    }
    for (String prefix : excludedLoggerPrefixes) {
      if (matchesPrefix(loggerName, prefix)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesPrefix(String loggerName, String prefix) {
    return loggerName.equals(prefix) || loggerName.startsWith(prefix + ".");
  }

  /**
   * True when {@code event} carries a marker named {@link #NO_ALERT_MARKER_NAME},
   * either directly or as a child of a composite marker ({@link Marker#contains(String)} checks the
   * marker's own name plus every referenced child marker recursively).
   */
  boolean hasNoAlertMarker(ILoggingEvent event) {
    List<Marker> markers = event.getMarkerList();
    if (markers == null) {
      return false;
    }
    for (Marker marker : markers) {
      if (marker != null && marker.contains(NO_ALERT_MARKER_NAME)) {
        return true;
      }
    }
    return false;
  }

  private static Duration toJavaDuration(ch.qos.logback.core.util.Duration duration) {
    return Duration.ofMillis(duration.getMilliseconds());
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /**
   * {@code shutdownNow()} only requests interruption of worker threads — it does not wait
   * for them to actually exit. Bounding the wait to 500ms keeps {@code stop()} from ever hanging on
   * a stuck thread while still making teardown deterministic for the common case (interrupted
   * daemon threads normally exit in low single-digit milliseconds).
   */
  private static void awaitTerminationQuietly(ExecutorService service) {
    try {
      service.awaitTermination(500, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
