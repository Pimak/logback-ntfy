# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-07-18
### Added
- Initial release: a zero-dependency, storm-resistant, loop-safe Logback appender
  (`NtfyAlertAppender`) that publishes `ERROR`-level log events as [ntfy](https://ntfy.sh)
  push notifications.
- Rate-limited alert storms with a count-never-lost suppressed-count digest, so a burst of
  errors collapses into a single follow-up notification instead of flooding the ntfy topic.
- Three authentication modes (`BearerToken`, `BasicAuth`, `None`) with a "token wins"
  precedence rule when both are configured.
- Logger exclusion filtering (`excludedLoggers`, prefix-matched) plus a built-in `NO_ALERT`
  marker and an always-on self-exclusion to prevent feedback loops.
- Structured, truncated (4096-byte) payloads carrying the root-cause exception, and
  `StatusManager`-only diagnostics so the appender itself never re-enters the logging
  pipeline it publishes from.

[Unreleased]: https://github.com/Pimak/logback-ntfy/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Pimak/logback-ntfy/releases/tag/v0.1.0
