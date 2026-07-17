# logback-ntfy

A zero-dependency Logback appender that publishes ERROR-level log events as [ntfy](https://ntfy.sh)
push notifications. It is storm-resistant (rate-limited with a suppressed-count digest), loop-safe
(never logs its own diagnostics through SLF4J), and — unlike appenders wired through your
application framework's own notification stack — it works even before a Spring
`ApplicationContext` (or any other DI container) exists, which is exactly the startup window where
early fatal errors are otherwise invisible.

## Install

Maven coordinate: `io.github.pimak:logback-ntfy:0.1.0-SNAPSHOT`

```xml
<dependency>
  <groupId>io.github.pimak</groupId>
  <artifactId>logback-ntfy</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The artifact depends on nothing beyond `logback-classic`/`logback-core` (`provided` scope — you
already have them if you use Logback) and the JDK. A dependency allowlist enforced at build time
guarantees this promise holds for every future release.

## Quickstart

The example below is the recommended, safe-by-default production setup: the raw appender is
wrapped in an `AsyncAppender` (`neverBlock=true`, so a slow/unreachable ntfy server never blocks
your application threads) filtered to `ERROR` only, plus a `DefaultShutdownHook` so the last
fatal error before shutdown still gets flushed and published.

```xml
<appender name="NTFY_ALERT_RAW" class="io.github.pimak.logbackntfy.NtfyAlertAppender">
  <url>https://ntfy.example.com</url>
  <topic>my-app-alerts</topic>
  <token>tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx</token>
</appender>

<appender name="NTFY_ALERT" class="ch.qos.logback.classic.AsyncAppender">
  <appender-ref ref="NTFY_ALERT_RAW"/>
  <queueSize>256</queueSize>
  <neverBlock>true</neverBlock>
  <includeCallerData>false</includeCallerData>
  <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
    <level>ERROR</level>
  </filter>
</appender>

<shutdownHook class="ch.qos.logback.core.hook.DefaultShutdownHook"/>

<root level="INFO">
  <appender-ref ref="NTFY_ALERT"/>
</root>
```

Replace `url`, `topic`, and `token` with your own ntfy server, topic, and access token (or drop
`token` entirely to publish to a public/permissive topic with no auth). The full setter API —
rate-limiting, digest priorities/tags, timeouts, logger exclusions — is documented in
[docs/configuration.md](docs/configuration.md).

## Filtering: exclude-list, not allowlist

`logback-ntfy` lets you exclude specific loggers (`excludedLoggers`, prefix-matched) from ever
generating an alert, but it deliberately has no "only alert for these loggers" allowlist mode. For
an alerting appender, an allowlist has the wrong failure mode: a novel error from a logger nobody
thought to list would be silently dropped — exactly the event you most need to see. The
exclude-list is fail-safe instead: everything alerts by default except noise you've explicitly
declared expected. If you want alerts scoped to a subtree, attach the appender to that specific
logger rather than to `root`. See [docs/filtering.md](docs/filtering.md) for the full mechanism,
including the built-in `NO_ALERT` marker and the always-on self-exclusion.

## Documentation

| Page | Covers |
|------|--------|
| [docs/configuration.md](docs/configuration.md) | Full setter reference (every `set*` method, its XML element, type, and default) |
| [docs/alert-behavior.md](docs/alert-behavior.md) | Why the appender behaves the way it does: immediate single-error alerts, storm suppression, digest-on-window-close, digest-on-shutdown |
| [docs/authentication.md](docs/authentication.md) | The three auth modes (`BearerToken`, `BasicAuth`, `None`) and the "token wins" precedence rule |
| [docs/filtering.md](docs/filtering.md) | `excludedLoggers`, the `NO_ALERT` marker, and the always-on self-exclusion |
| [docs/troubleshooting.md](docs/troubleshooting.md) | Lookup table of every Logback `StatusManager` status line the appender emits and what to do about it |

## License

[Apache-2.0](LICENSE)
