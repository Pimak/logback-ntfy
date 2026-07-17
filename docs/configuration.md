# Configuration Reference

`logback-ntfy` is configured **exclusively through JavaBean setters** on `NtfyAlertAppender`.
The library never reads `System.getenv()` or any other process-environment
source directly — if you want environment-variable driven configuration (e.g.
`NTFY_ALERT_URL`), wire it in your own `logback.xml`/`logback-spring.xml` using Logback's
standard `${VAR_NAME}` property substitution and pass the resolved value into the setter's
XML element. This keeps the appender itself dependency-free and testable in isolation.

Every setter below corresponds to one XML element inside the `<appender>` block, using the
standard Logback JavaBean convention: `setFoo(...)` maps to `<foo>...</foo>`.

## Setter Reference

| XML element | Setter | Type | Default | Meaning |
|---|---|---|---|---|
| `<url>` | `setUrl(String)` | `String` | *(none — required)* | Base URL of the ntfy server (e.g. `https://ntfy.example.com`). |
| `<topic>` | `setTopic(String)` | `String` | *(none — required)* | ntfy topic to publish alerts to. |
| `<token>` | `setToken(String)` | `String` | *(none)* | Bearer token used for authentication. Takes precedence over `username`/`password` if both are set. |
| `<username>` | `setUsername(String)` | `String` | *(none)* | Username for HTTP Basic authentication. Ignored if `token` is set. |
| `<password>` | `setPassword(String)` | `String` | *(none)* | Password for HTTP Basic authentication. Ignored if `token` is set. |
| `<title>` | `setTitle(String)` | `String` | *(none)* | Title prefix for notifications. When the event carries an exception, the root-cause exception class name is appended: `<title> - java.lang.FooException`. If unset, falls back to `appName`; if both are unset the title is empty (no `Title` header is sent) and the ntfy server shows its own server-side default, the topic name. |
| `<appName>` | `setAppName(String)` | `String` | *(none)* | Application name used as the title fallback when `title` is not set (the same root-cause suffix rule applies to it). It does not appear in the notification body. |
| `<maxStackFrames>` | `setMaxStackFrames(int)` | `int` | `5` | Maximum number of stack trace frames included per alert body before the trace is cut off. |
| `<connectTimeout>` | `setConnectTimeout(Duration)` | `ch.qos.logback.core.util.Duration` | `5000` ms (5 seconds) | Timeout for establishing the HTTP connection to the ntfy server. |
| `<requestTimeout>` | `setRequestTimeout(Duration)` | `ch.qos.logback.core.util.Duration` | `10000` ms (10 seconds) | Timeout for the full HTTP publish request/response round trip. |
| `<maxAlertsPerWindow>` | `setMaxAlertsPerWindow(int)` | `int` | `3` | Number of individual alerts allowed per `suppressionWindow` before storm rate-limiting kicks in. See [alert-behavior.md](alert-behavior.md). |
| `<suppressionWindow>` | `setSuppressionWindow(Duration)` | `ch.qos.logback.core.util.Duration` | `180000` ms (3 minutes) | Rolling window used both for the burst allowance and for the periodic digest timer. See [alert-behavior.md](alert-behavior.md). |
| `<errorPriority>` | `setErrorPriority(String)` | `String` | `"high"` | ntfy `Priority` header value used for individual (non-suppressed) error alerts. |
| `<digestPriority>` | `setDigestPriority(String)` | `String` | `"urgent"` | ntfy `Priority` header value used for aggregated storm digests. |
| `<errorTags>` | `setErrorTags(String)` | `String` | `"rotating_light"` | ntfy `Tags` header value (comma-separated) used for individual error alerts. |
| `<digestTags>` | `setDigestTags(String)` | `String` | `"fire"` | ntfy `Tags` header value (comma-separated) used for aggregated storm digests. |
| `<excludedLoggers>` | `setExcludedLoggers(String)` | `String` | *(none)* | Single comma-separated value of logger-name prefixes to exclude from alerting entirely. See [filtering.md](filtering.md). |

`url` and `topic` are the only two settings without which the appender stays inactive
(silently, if both are unset; loudly via a Logback status warning if only one is set — see
[troubleshooting.md](troubleshooting.md)). Every other setter has a safe, source-verified
default and can be omitted.

## Duration Syntax

`connectTimeout` and `suppressionWindow` are typed as `ch.qos.logback.core.util.Duration`, the
standard Logback duration type. In XML configuration this accepts Logback's own duration
syntax — a bare number of milliseconds, or a number followed by a unit word, e.g.:

```xml
<connectTimeout>5 seconds</connectTimeout>
<suppressionWindow>3 minutes</suppressionWindow>
```

Accepted unit words are the ones supported by `ch.qos.logback.core.util.Duration` itself
(`millisecond(s)`, `second(s)`, `minute(s)`, `hour(s)`, `day(s)`). A bare integer is
interpreted as milliseconds.

## Authentication

See [authentication.md](authentication.md) for the full precedence rules between `token` and
`username`/`password`, and for the `NONE` (unauthenticated) mode.

## Filtering

See [filtering.md](filtering.md) for how `excludedLoggers` combines with the appender's
always-on self-exclusion, and for the per-event `NO_ALERT` marker opt-out.

## Alert Behavior

See [alert-behavior.md](alert-behavior.md) for how `maxAlertsPerWindow`, `suppressionWindow`,
`errorPriority`/`digestPriority`, and `errorTags`/`digestTags` combine at runtime to produce
storm-resilient, triage-friendly notifications.

## Example

A minimal, generic configuration:

```xml
<appender name="NTFY_ALERT_RAW" class="io.github.pimak.logbackntfy.NtfyAlertAppender">
  <url>https://ntfy.example.com</url>
  <topic>my-app-alerts</topic>
  <token>tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx</token>
  <appName>my-app</appName>
  <maxAlertsPerWindow>3</maxAlertsPerWindow>
  <suppressionWindow>3 minutes</suppressionWindow>
</appender>
```
