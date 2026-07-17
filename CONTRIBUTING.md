# Contributing

## Build

```bash
./mvnw verify
```

This runs unit tests, integration tests (WireMock-based), the dependency-allowlist
enforcer check, and the dead-code static-analysis guard (PMD) — all gates CI runs too.

## Dependency policy

`logback-ntfy` is zero-dependency beyond Logback + the JDK. Any new dependency (direct
or transitive) must be added to the `maven-enforcer-plugin` allowlist in `pom.xml`
(`enforce-dependency-allowlist` execution) — see the comment block above that rule for
the exact process. A PR that adds a dependency without updating the allowlist will fail
`mvn verify` locally and in CI.

## Pull requests

- Keep changes focused; one logical change per PR.
- Add or update tests for behavior changes.
- `./mvnw verify` must pass before requesting review.
