# Alert Behavior

This page explains *why* `NtfyAlertAppender` behaves the way it does when your application
starts logging ERRORs — what gets published immediately, what gets suppressed, and how
suppressed alerts are never silently lost. For the tunable setters referenced below, see
[configuration.md](configuration.md). For a lookup table of the diagnostic status lines the
appender emits, see [troubleshooting.md](troubleshooting.md).

## An isolated error is published immediately

The very first ERROR seen in a suppression window is published right away, as an individual
ntfy notification, at `errorPriority` (default `"high"`) with `errorTags` (default
`"rotating_light"`, rendered by ntfy as a 🚨 emoji). There is no batching delay for a lone
error — you want to know about it as soon as it happens.

## Storm rate-limiting

If errors keep arriving, the appender does not publish one notification per error
indefinitely — that would flood both ntfy and whoever receives the push notifications during
an actual incident, which is exactly when you can least afford to be flooded. Instead, a
token-bucket-style limiter allows at most `maxAlertsPerWindow` individual alerts (default
**3**) per `suppressionWindow` (default **180000 ms / 3 minutes**). The first few errors in a
window get through as full individual notifications with real error content; any error beyond
the allowance in that window is counted rather than published.

Rate limiting is **always on** by default — a consumer that only configures `url` and
`topic` is still protected against a runaway error loop out of the box. Setting
`maxAlertsPerWindow` to `0` (or negative) disables the limiter and reverts to
publish-everything behavior.

The limiter's scope is global to the appender instance: there is a single shared
counter, not one counter per logger. When a new window opens, the allowance fully refills
— a sustained storm still lets a few fresh individual alerts through every window,
so you keep seeing real error content as the incident evolves, rather than only ever seeing
digests.

## The aggregated digest

When a suppression window closes, if one or more errors were suppressed during that window,
exactly **one** aggregated digest notification is emitted: a single "N errors suppressed"
notification, published at `digestPriority` (default `"urgent"`) with `digestTags` (default
`"fire"`, rendered by ntfy as a 🔥 emoji). The digest title follows the pattern
`<title|appName> — N errors suppressed`, and the body includes a per-logger tally (e.g. `9×
com.example.MyService`) so you can see which component is the source of the storm without
opening a log file.

If nothing was suppressed in a window, no digest is sent — silence during a quiet window is
expected, not a bug.

The suppressed count is never silently dropped. Two mechanisms guarantee this:

- **Failed publishes count too.** If an individual publish attempt fails (HTTP error,
  network down, ntfy rate-limiting the appender itself with a 429), that failure is folded
  into the suppression counter rather than discarded — the next digest reports it honestly.
- **A failed digest publish re-folds its count back in.** If the digest publish itself fails,
  the drained count is restored into the limiter (per-logger breakdown included) instead of
  being lost, so it survives into the next window's digest.
- **Shutdown flushes the digest synchronously.** When the appender is stopped (JVM
  shutdown, Logback context reset), if there is a non-zero pending suppression count, a
  best-effort synchronous digest flush is attempted before resources are released, bounded by
  the existing HTTP timeouts (`connectTimeout`/`requestTimeout`) so shutdown is never blocked
  indefinitely.

## Priority and tags for visual triage

`errorPriority`/`digestPriority` and `errorTags`/`digestTags` are independently configurable,
letting an individual error and a storm digest look visually distinct in the ntfy client at a
glance: a lone error arrives as `high`/🚨 while a storm digest arrives as `urgent`/🔥, so the
person receiving the notification can immediately tell "one error happened" from "this
service is having a bad time" without opening the notification. Both are pass-through values
(no local validation is applied to whatever priority/tag string you configure), so future ntfy
priority or tag values work automatically without a library update.

## Content truncation

ntfy enforces a hard 4096-byte body limit. Before any alert (individual or digest) is
published, its body is truncated to fit within that budget, sacrificing whole trailing lines
first (typically the tail of a stack trace) so the message, logger name, and cause chain stay
intact as long as possible. Truncation is measured in UTF-8 bytes, not string length, so
multi-byte characters are never split mid-character and the published body never exceeds the
byte budget.

## See also

- [configuration.md](configuration.md) — the exact setters (`maxAlertsPerWindow`,
  `suppressionWindow`, `errorPriority`, `digestPriority`, `errorTags`, `digestTags`) that
  control this behavior.
- [troubleshooting.md](troubleshooting.md) — what each Logback status line the appender emits
  means and how to act on it.
