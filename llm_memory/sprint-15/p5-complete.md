# P5 — Shadow JAR CI Validation: Complete

**Date:** 2026-06-25  
**File changed:** `fabric-mod/build.gradle` only  
**Branch:** `feature/sprint-15-memory`

## What was done

1. Removed the `relocate 'com.sun', 'com.mcagent.shaded.com.sun'` line from the `shadowJar` block (MINOR-6 — JDK internals must not be relocated).
2. Added `validateShadowJar` Gradle task after `shadowJar`. Scans the assembled JAR with `java.util.zip.ZipFile` (no new dependencies). Checks 9 forbidden prefixes against entries not covered by 6 allowed prefixes. Fails with a truncated violation list and a remediation hint.
3. Wired `build.dependsOn validateShadowJar`.

## Test results

- **Positive path:** `./gradlew :fabric-mod:shadowJar :fabric-mod:validateShadowJar` — BUILD SUCCESSFUL, prints "Shadow JAR validation passed."
- **Negative path:** Temporarily added `implementation 'com.fasterxml.jackson.core:jackson-core:2.18.0'` and commented out the jackson relocation. Validator caught 1100 unshaded entries with a clear error message and remediation instruction. Reverted before committing.
- **Lifecycle wiring:** `./gradlew :fabric-mod:build --dry-run` confirms `validateShadowJar` appears in the task graph before `build`.

Note: `:fabric-mod:build` itself cannot complete cleanly in isolation due to a `core/` compilation error introduced by concurrent P11 work (`JobSupervisor.persistReflection` missing). That is out of scope for P5.
