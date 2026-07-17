# Filtering

`logback-ntfy` gives you three independent ways to control which logging events actually
generate a notification. Two are configurable, one is always on.

> **The appender performs no level filtering itself.** Every event that reaches
> `NtfyAlertAppender` is alerted, regardless of level — INFO and WARN included. Level
> restriction is your Logback configuration's responsibility: attach a `ThresholdFilter` set to
> `ERROR` (as in the README quickstart) or scope the appender-ref appropriately. Without one,
> attaching the appender to `root` publishes a notification for every single event, immediately
> exhausting the rate limiter and producing noise digests. The bare examples on this page omit
> the filter for brevity only.

## 1. `excludedLoggers` — configurable logger-name exclusion

`setExcludedLoggers(String)` accepts a single, comma-separated string of logger-name prefixes.
Any event whose logger name equals one of the prefixes, or is a descendant of one (matched at a
package-hierarchy boundary — `org.apache.kafka` excludes `org.apache.kafka.clients` but not a
sibling like `org.apache.kafkaconnect`), is dropped before it is ever published or counted.

```xml
<appender name="NTFY_ALERT" class="io.github.pimak.logbackntfy.NtfyAlertAppender">
  <url>https://ntfy.example.com</url>
  <topic>my-app-alerts</topic>
  <excludedLoggers>org.apache.kafka, com.zaxxer.hikari</excludedLoggers>
</appender>
```

Excluded events disappear entirely: they are not published individually, and they are not
counted toward the storm-suppression rate limiter or folded into a digest. Exclusion means
"this is expected noise, never tell me about it" — not "tell me about it later, in bulk."

On startup, the appender announces its exclusion configuration exactly once via
`addInfo` (alongside its `ACTIVE` status line), so you can confirm from the Logback status
output which prefixes are actually in effect. See [`troubleshooting.md`](troubleshooting.md) for
the exact wording.

## 2. The `NO_ALERT` marker — per-event opt-out

Sometimes you want to suppress alerting for a single log statement rather than an entire logger.
`NtfyAlertAppender` exposes a public constant, `NO_ALERT_MARKER_NAME` (value `"NO_ALERT"`), that
names an SLF4J marker you can attach to any individual `error(...)` call:

```java
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import io.github.pimak.logbackntfy.NtfyAlertAppender;

private static final Marker NO_ALERT =
    MarkerFactory.getMarker(NtfyAlertAppender.NO_ALERT_MARKER_NAME);

log.error(NO_ALERT, "Expected, already-handled failure — do not page anyone");
```

An event carrying this marker (directly, or as a child of a composite marker referencing it) is
gated out the same way an excluded-logger event is: never published, never counted, never folded
into a digest. Use this for genuinely expected, already-handled error-level log lines — for
example, an error you deliberately log for local diagnostics but that does not warrant paging
anyone.

## 3. Always-on self-exclusion — the anti-loop gate

The appender's own package, `io.github.pimak.logbackntfy`, is excluded from alerting
unconditionally — independent of whatever you pass to `setExcludedLoggers`, and independent of
any XML-level logger filter you might also configure. This is a belt-and-suspenders guard against
the appender ever generating a feedback loop by alerting on a failure inside itself (for example,
a publish failure it reports via `addWarn`/`addError`, which — since diagnostics go through
Logback's StatusManager, not SLF4J — could never reach this appender anyway, but the built-in
exclusion holds even if that invariant is ever violated by future code).

You do not configure this; it cannot be turned off. It survives a blank or misconfigured
`excludedLoggers` value.

## Why an exclude-list, not an allowlist, for loggers

`excludedLoggers` is deliberately an exclude-list (deny-list) rather than an allowlist
(include-list) — this was considered and explicitly decided against for v1.

For an alerting appender, an allowlist has an inverted failure mode: an error from a logger you
never thought to add to the allowlist would be silently dropped — and that unanticipated error is
exactly the event you most need to be paged about. An exclude-list fails safe instead: everything
alerts by default, and you only opt specific, already-understood noise sources out by name. The
asymmetry matters because the cost of "I get paged for something I already knew was noisy" is
low (fix by adding one more exclude entry), while the cost of "I never learn about a brand-new
failure because it wasn't on my allowlist" is high (silent blind spot).

If you need to scope alerting to a specific subtree instead of excluding a subtree, you already
have that lever without an allowlist: attach the appender to a specific logger (rather than to
`root`) in your Logback configuration, and only that logger's subtree feeds the appender at all.

## See also

- [`configuration.md`](configuration.md) — the full setter reference, including
  `excludedLoggers`.
