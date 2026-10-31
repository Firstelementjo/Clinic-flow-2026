# Runbook â€” local Gradle and Testcontainers failures on this workstation

Recorded 2026-10-13. Written because the failure looks like a broken build and is not;
it cost roughly two hours to diagnose and will recur on a fresh shell or a new machine.

## Symptom

`gradlew` fails immediately, every time, regardless of the task:

```
FAILURE: Build failed with an exception.
* What went wrong:
Could not connect to the Gradle daemon.
```

`gradlew --version` still works, because it needs no daemon. The daemon's own log is
**not** diagnostic â€” it reaches `Daemon server started` and
`Completed writing the daemon greeting`, then stops. The daemon is in fact alive and
idle; the launcher simply never reaches it.

## Cause

An IPv6 socket problem on this host. The JVM binds or connects on IPv6 (link-local
addresses from the "WAN Miniport" virtual adapters), and `BindException: Cannot assign
requested address: getsockopt` follows. The same exception breaks Testcontainers'
JDBC connection to its own Postgres container, which is how the two symptoms were
finally tied together:

```
Caused by: org.postgresql.util.PSQLException: The connection attempt failed.
Caused by: java.net.BindException: Cannot assign requested address: getsockopt
```

Earlier "Docker environment failed" test failures were this too â€” the container was
fine; the connection to it was not.

## Fix

Force the JVM onto IPv4. The daemon is forked, so the flag must reach it, not just the
launcher:

```bat
set GRADLE_OPTS=-Djava.net.preferIPv4Stack=true
gradlew.bat <task>
```

This is now set permanently in `build.gradle.kts` (forked test workers) and in
`gradle.properties` (the daemon), so an ordinary `gradlew` invocation works. Verified
2026-10-13: `BUILD SUCCESSFUL` with the flag, `Could not connect` without it, same
machine, same minute.

**Both** placements are required. `gradle.properties` alone fixes the daemon but not the
workers; `GRADLE_OPTS` alone fixes the client but not the workers. See the table below.

## What did NOT help

Recorded so nobody spends time on them again:

| Attempt | Result |
|---|---|
| `gradlew --stop`, deleting `daemon/*/registry.bin` | no change |
| Killing orphaned `java.exe` processes | no change |
| `--no-parallel`, `-Xmx1200m`, direct `-Dorg.gradle.jvmargs` | no change |
| `-Dorg.gradle.native=false`, clearing `~/.gradle/native` | no change |
| `-Djava.net.preferIPv4Stack=true` passed as a Gradle `-D` flag | **no change** â€” the flag must be in `GRADLE_OPTS`/`JAVA_OPTS` so the forked daemon inherits it |
| Wrapping in `cmd.exe /c` vs invoking from PowerShell | no change (not a pipe problem) |

## Also relevant on this host

- Docker context is `desktop-linux` (`npipe:////./pipe/dockerDesktopLinuxEngine`).
  Testcontainers defaults to `\\.\pipe\docker_engine`. Gradle resolves this correctly;
  a hand-assembled flat classpath does not, and a stale Testcontainers version on that
  classpath yields `BadRequestException (Status 400)` rather than a connection error.
- Free RAM is ~3 GB of 16 GB. This is **not** the cause of the daemon failure, but it
  is worth knowing before blaming memory. The local containers total ~1.4 GB.
## Concurrent Testcontainers startup poisons Docker discovery

A second, separate failure that looks like a code regression and is not.

**Symptom:** one module fails with

```
ConnectorAndSearchOperationsIntegrationTest > initializationError FAILED
java.lang.IllegalStateException: Previous attempts to find a Docker environment failed. Will not retry.
```

and every test in that module reports `initializationError` — none of them ran.

**Cause:** `org.gradle.parallel=true` (gradle.properties) starts every module's Testcontainers at
roughly the same moment. One module's first discovery probe fails — resource contention, not a
missing daemon — and Testcontainers then refuses to retry for the rest of that JVM. The failure is
*cached*, so all subsequent modules in the same worker skip container startup entirely.

**This has now failed three separate full-matrix runs**, hitting a different module each time
(`workflows-sla-routing`, then a dozen modules at once, then `operations-observability`). It is
therefore a property of how the matrix is invoked on this host, not of any module.

**Fix:** serialise container startup by limiting workers:

```bat
set GRADLE_OPTS=-Djava.net.preferIPv4Stack=true
gradlew.bat --no-daemon --max-workers=2 test
```

**Confirming a module is actually fine** (rather than assuming a retry will fix it):

```bat
gradlew.bat --no-daemon :backend:<module>:test
```

A module that passes alone and fails in the matrix is this flake. A module that fails alone is a
real failure and must be fixed, not re-run.

**Do not treat a green re-run as evidence.** A retry that passes proves only that the environment
recovered. The evidence is one full matrix green in a single pass with workers limited.
