---
name: Run mvnw install on updated modules before testing dependent modules
description: When a module dependency is modified, install it first; otherwise test compilation of dependent modules will use the stale JAR from the local Maven repo
type: feedback
---

`./mvnw test -pl opendaimon-telegram` uses the installed JAR of `opendaimon-common` from `~/.m2`. If `opendaimon-common` was just modified, run `./mvnw install -pl opendaimon-common -DskipTests` first, otherwise test compilation in `opendaimon-telegram` will see stale symbols.

**Why:** Maven test classpath resolution for single-module builds uses installed artifacts, not reactor targets.

**How to apply:** After editing any shared module (`opendaimon-common`, `opendaimon-bulkhead`, etc.), always `mvnw install -pl <module> -DskipTests` before running tests in a dependent module without `-am`.
