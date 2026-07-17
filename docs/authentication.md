# Authentication

`logback-ntfy` supports three first-class authentication modes for the outbound ntfy publish
request, modeled by `AuthMode`, a sealed type with three variants: `BearerToken`, `BasicAuth`,
and `None`. Which variant is active is derived automatically from which setters you configure —
there is no separate "auth mode" setter to flip.

## The three modes

### `BearerToken`

Set `token`. The appender sends the request with an `Authorization: Bearer <token>` header. This
is the recommended mode for a private ntfy topic protected by an access token.

```xml
<appender name="NTFY_ALERT" class="io.github.pimak.logbackntfy.NtfyAlertAppender">
  <url>https://ntfy.example.com</url>
  <topic>my-app-alerts</topic>
  <token>tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx</token>
</appender>
```

### `BasicAuth`

Set both `username` and `password`. The appender sends the request with HTTP Basic
authentication. Use this mode when the ntfy server (or a reverse proxy in front of it) is
gated by a username/password pair rather than a token.

```xml
<appender name="NTFY_ALERT" class="io.github.pimak.logbackntfy.NtfyAlertAppender">
  <url>https://ntfy.example.com</url>
  <topic>my-app-alerts</topic>
  <username>alerts-bot</username>
  <password>a-strong-password</password>
</appender>
```

Both `username` and `password` must be non-blank. **If only one of the two is set, the pair is
ignored and the appender silently falls back to `None` mode**: every publish goes out with no
`Authorization` header at all, and there is no startup warning for this half-configured state
(the only overlap warning is the token-plus-basic case below). Against a protected server the
observable symptom is `ntfy publish failed for topic '<topic>' (HTTP 401)` (or `403`) status
lines — see [`troubleshooting.md`](troubleshooting.md). Against a permissive server the
publishes may even succeed, unauthenticated, while you believe auth is in effect. If Basic Auth
appears not to apply, first check that both values are actually set and non-blank (a
`${VAR_NAME}` property that resolves to an empty string counts as blank).

### `None`

Set neither `token` nor `username`/`password`. The appender publishes with no `Authorization`
header at all. `None` is a valid, first-class configuration — not a misconfiguration — and is
the correct mode for a public `ntfy.sh` topic (anyone who knows the topic name can subscribe or
publish) or a self-hosted server whose publish endpoint is deliberately left open.

```xml
<appender name="NTFY_ALERT" class="io.github.pimak.logbackntfy.NtfyAlertAppender">
  <url>https://ntfy.sh</url>
  <topic>my-app-alerts</topic>
</appender>
```

## Precedence: token wins over Basic Auth

If you configure **both** a `token` and a `username`/`password` pair, the appender does not
fail and does not refuse to start. Instead:

- `BearerToken` takes precedence — every publish request uses the token, and the configured
  `username`/`password` are silently ignored for the purpose of the `Authorization` header.
- The appender emits a **one-time** warning through Logback's own `StatusManager` when it starts:
  `both token and username/password configured — token takes precedence`.
- Startup still proceeds and the appender still activates normally. Authentication configuration
  is never a reason to block activation.

This lets you leave stale `username`/`password` values in a configuration file (for example
while migrating from Basic Auth to a token) without breaking anything — the token simply wins,
and the one-time warning tells you the overlap exists so you can clean it up.

See `troubleshooting.md` for the exact wording of this and every other status message the
appender can emit.

## The token is never surfaced in diagnostics

The appender's self-diagnostics (its `ACTIVE` status line, its publish-failure warnings, its
error messages) never include the `token`, `username`, or `password` value. The only credential-
adjacent information that can appear in a status line is the `url` and `topic` you configured —
and even then, if you supply a URL with embedded userinfo (`https://user:pass@host/...`), the
appender strips that userinfo before logging the URL. No diagnostic output — StatusManager line,
exception message, or otherwise — ever echoes a secret.

## See also

- [`configuration.md`](configuration.md) — full setter reference, including `url`, `topic`,
  `token`, `username`, and `password`.
- [`troubleshooting.md`](troubleshooting.md) — the exact StatusManager message wording for the
  token-wins warning and every other diagnostic line the appender can produce.
