# Compatibility

This page documents the JDK versions, Logback versions, and ntfy server API surface this
library is tested against, along with the reasoned range of versions expected to work beyond
what CI directly exercises.

## Java

| JDK | Status |
|-----|--------|
| 21  | Tested — CI matrix leg, every push/PR |
| 25  | Tested — CI matrix leg, every push/PR |
| 22-24 | Expected to work (not CI-tested) — the compiler `<release>` floor is 21 |

Minimum supported JDK: **21** (`<release>21</release>` in `pom.xml`; required for
`HttpClient.shutdownNow()`, used in `NtfyAlertAppender.stop()`).

## Logback

| Version | Status |
|---------|--------|
| 1.5.38  | Tested — the exact version pinned in this repo's own `pom.xml` and CI |
| 1.5.x (other) | Expected to work — no version-specific API surface used beyond what's stable across the 1.5 line |
| 1.2.x / 1.3.x | Not tested, not supported — this library targets `logback-classic`'s modern JavaBean/Joran APIs |

## ntfy server

Verified against [ntfy.sh](https://ntfy.sh) (the public hosted instance) and a self-hosted
ntfy instance. The library targets ntfy's plain publish-with-headers HTTP API (`POST` to the
topic URL, `Title`/`Priority`/`Tags` as headers, body as the message text) — any ntfy server
version implementing that stable API surface should work.

## See also

See [configuration.md](configuration.md) for the full setter reference, and
[troubleshooting.md](troubleshooting.md) for what to do if a specific JDK/Logback/ntfy
combination misbehaves.
