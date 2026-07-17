# Troubleshooting

`logback-ntfy` reports its own health exclusively through Logback's own `StatusManager` —
via the inherited `addInfo`/`addWarn`/`addError` methods — and **never** through an SLF4J
`Logger`. This is deliberate: if the appender ever logged its own diagnostics through
SLF4J, a persistent failure inside the appender could re-trigger the very root logger it is
attached to, creating an alerting feedback loop. StatusManager output is a completely separate
channel from your application's log output, so it can never do that.

## How to see the status lines

Add a status listener to your Logback configuration so these lines are visible. The simplest
option is Logback's built-in console listener:

```xml
<configuration>
  <statusListener class="ch.qos.logback.core.status.OnConsoleStatusListener"/>
  <!-- ... your appenders, including NtfyAlertAppender ... -->
</configuration>
```

Alternatively, dump the status log programmatically at any point (for example, after Logback
finishes configuring):

```java
((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory())
    .getStatusManager()
    .getCopyOfStatusList()
    .forEach(status -> System.out.println(status));
```

This is where every message documented below shows up.

## Status message reference

This page is a lookup table: message text, what it means, and what to do about it. For the
narrative behind *why* the appender behaves this way, see `alert-behavior.md`.

| Status message (exact wording) | Level | Meaning | Fix |
|---|---|---|---|
| `ntfy alert appender not configured (url/topic unset) — inactive` | `addInfo` | Neither `url` nor `topic` is set. The appender is silently inactive (`isStarted() == false`) — this is a normal, supported state, not an error. | If you intended to enable alerting, set both `url` and `topic`. If alerting is genuinely not wanted here, no action needed. |
| `url set but topic missing — appender disabled` | `addWarn` | Exactly one of `url`/`topic` is set — a likely typo or incomplete configuration. The appender remains inactive. | Set the missing one of `url`/`topic` (or unset the one you did set, if this appender should stay disabled). |
| `both token and username/password configured — token takes precedence` | `addWarn` | Both a `token` and a `username`/`password` pair are configured. The token wins for the `Authorization` header; startup still proceeds normally. | Not an error — remove the unused `username`/`password` (or `token`) to eliminate the overlap, or leave it if the overlap is intentional during a migration. See `authentication.md`. |
| `suppressionWindow must be positive — falling back to default (3 minutes)` | `addWarn` | `suppressionWindow` is unset, zero, or negative. The appender falls back to the 3-minute default rather than failing to start. | Set `suppressionWindow` to a positive `ch.qos.logback.core.util.Duration` value, or leave it unset to accept the 3-minute default silently. |
| `ntfy alert appender ACTIVE (url=<url>, topic=<topic>)` | `addInfo` | The appender started successfully and is live. `<url>` has any embedded userinfo (`user:pass@`) stripped; the token/username/password are never shown. | Informational — confirms the appender is running with the `url`/`topic` you expect. |
| `ntfy alert appender excluded loggers: <prefix1>, <prefix2>, ...` (or `ntfy alert appender: no excluded loggers configured`) | `addInfo` | Lists the logger-name prefixes configured via `setExcludedLoggers`, emitted once at startup alongside the `ACTIVE` line. | Informational — confirms which prefixes are actually excluded. See `filtering.md`. |
| `ntfy publish failed for topic '<topic>' (HTTP <status>)` (or without the HTTP status when the failure was not an HTTP response, e.g. a connection failure) | `addWarn` | A publish attempt (individual alert or digest) did not succeed. The failure is folded into the suppression count so it surfaces in the next digest rather than being silently lost. | Check the ntfy server/topic and the reported HTTP status: 401/403 usually means an auth problem (see `authentication.md`), 404 usually means the topic doesn't exist yet on that server, 429 means you're being rate-limited by ntfy itself, 5xx means a server-side problem. |
| `ntfy publish threw unexpectedly` | `addError` (with the causing exception attached) | An unexpected `RuntimeException` occurred during publish. The message text is always this fixed string — the real exception is attached separately for stack-trace inspection, but its message text is never concatenated into the status line, since it could embed a credential. | Inspect the attached exception's stack trace via your status listener/dump for the real cause (e.g. a network configuration issue, a bug in a custom `HttpClient` builder, etc.). |

## Common scenarios

**"I configured the appender but nothing happens."** Check for
`ntfy alert appender not configured (url/topic unset) — inactive` or
`url set but topic missing — appender disabled` in the status output — one of `url`/`topic` is
probably missing.

**"Alerts stopped during a burst of errors."** This is expected storm-resilience behavior
(rate limiting), not a failure — the suppressed count is folded into the next periodic digest
rather than lost. See `alert-behavior.md` for the full rate-limiting/digest design.

**"A specific logger keeps paging me for known-noisy errors."** See `filtering.md` for
`excludedLoggers` and the `NO_ALERT` marker.

## See also

- [`authentication.md`](authentication.md) — the auth modes and the token-wins precedence
  behind the `STATUS_TOKEN_AND_BASIC_BOTH_SET` warning.
- [`filtering.md`](filtering.md) — `excludedLoggers`, the `NO_ALERT` marker, and the
  always-on self-exclusion behind the exclusions status line.
