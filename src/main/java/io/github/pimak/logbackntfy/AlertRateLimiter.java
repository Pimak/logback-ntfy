package io.github.pimak.logbackntfy;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token-bucket storm rate limiter. Allows up to {@code maxAlertsPerWindow} individual
 * alerts per rolling window; every additional event in the same window is suppressed and tallied
 * (globally and per logger) instead of published, so a storm of ERROR events never floods ntfy.
 *
 * <p>Binding invariant: {@link #drainAndReset()} is the ONLY method that zeroes the
 * suppression counters (global count and per-logger tally). {@link #tryAcquire()}'s own
 * window-elapsed rollover resets ONLY the burst-allowance counter ({@code sentThisWindow}) — it
 * never touches the suppression tally. This guarantees a suppressed count is never silently lost
 * across a burst-allowance rollover; it survives until a scheduled digest timer or {@code stop()}
 * flush calls {@link #drainAndReset()}.
 *
 * <p>Concurrency: the suppression state (global count + per-logger tally) is guarded by a
 * single {@code lock} so {@link #recordSuppressed(String)} (application threads) can never race
 * {@link #drainAndReset()} or {@link #restore(DigestSnapshot)} (digest-scheduler / stop() thread) —
 * a drained snapshot's count and tally always derive from the same atomic view, so they can never
 * diverge. The suppression path only runs once the burst allowance is exhausted, so a plain lock is
 * cheap. The hot path — {@link #tryAcquire()} — remains lock-free on its own atomics.
 */
final class AlertRateLimiter {

  private final int maxAlertsPerWindow;
  private final long windowMillis;

  private final AtomicInteger sentThisWindow = new AtomicInteger(0);
  private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

  /** Guards {@link #suppressedThisWindow} and {@link #perLoggerSuppressed}. */
  private final Object lock = new Object();

  private int suppressedThisWindow = 0;
  private final Map<String, Integer> perLoggerSuppressed = new HashMap<>();

  AlertRateLimiter(int maxAlertsPerWindow, long windowMillis) {
    this.maxAlertsPerWindow = maxAlertsPerWindow;
    this.windowMillis = windowMillis;
  }

  /**
   * Returns {@code true} if this event may be published, {@code false} if it must be suppressed
   * (caller should then call {@link #recordSuppressed(String)}). {@code maxAlertsPerWindow <=
   * 0} disables suppression entirely — always returns {@code true}.
   */
  boolean tryAcquire() {
    if (maxAlertsPerWindow <= 0) {
      return true;
    }
    rolloverIfWindowElapsed();
    return sentThisWindow.incrementAndGet() <= maxAlertsPerWindow;
  }

  /**
   * Resets ONLY the burst-allowance counter ({@code sentThisWindow}) when the window has elapsed.
   * Never touches the suppression counters — those are drained exclusively by {@link
   * #drainAndReset()}.
   */
  private void rolloverIfWindowElapsed() {
    long now = System.currentTimeMillis();
    long start = windowStart.get();
    if (now - start > windowMillis && windowStart.compareAndSet(start, now)) {
      sentThisWindow.set(0);
    }
  }

  /** Increments the global suppressed count and the per-logger tally for {@code loggerName}. */
  void recordSuppressed(String loggerName) {
    synchronized (lock) {
      suppressedThisWindow++;
      perLoggerSuppressed.merge(loggerName, 1, Integer::sum);
    }
  }

  /**
   * Returns {@code true} if any suppressed events are pending a digest (used by {@code stop()}
   * flush).
   */
  boolean hasPending() {
    synchronized (lock) {
      return suppressedThisWindow > 0;
    }
  }

  /**
   * Atomically captures the accumulated suppression count and per-logger tally, then zeroes both
   * suppression counters AND resets the burst-allowance counter so the next window starts with a
   * full allowance. This is the only method that zeroes the suppression counters. The count and
   * tally are captured under the same lock as {@link #recordSuppressed(String)}, so the snapshot's
   * global count always equals its tally sum.
   */
  DigestSnapshot drainAndReset() {
    synchronized (lock) {
      DigestSnapshot snap =
          new DigestSnapshot(suppressedThisWindow, new HashMap<>(perLoggerSuppressed));
      suppressedThisWindow = 0;
      perLoggerSuppressed.clear();
      sentThisWindow.set(0);
      windowStart.set(System.currentTimeMillis());
      return snap;
    }
  }

  /**
   * Folds a previously drained snapshot back into the accumulated suppression
   * state after a failed digest publish, in one atomic bulk merge — the global count is restored
   * from the snapshot's own {@code count()} (single source of truth), never re-derived from the
   * tally sum, so a re-fold can never lose or duplicate counts.
   */
  void restore(DigestSnapshot snapshot) {
    synchronized (lock) {
      suppressedThisWindow += snapshot.count();
      for (Map.Entry<String, Integer> entry : snapshot.perLoggerTally().entrySet()) {
        perLoggerSuppressed.merge(entry.getKey(), entry.getValue(), Integer::sum);
      }
    }
  }

  /**
   * Immutable snapshot of accumulated suppression state at the moment of {@link #drainAndReset()}.
   */
  record DigestSnapshot(int count, Map<String, Integer> perLoggerTally) {}
}
