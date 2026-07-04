# Profile-Guided Optimization (PGO) for pypowsybl

PGO lets the GraalVM native-image compiler optimize the Java layer using a profile
collected from a representative run, typically buying a further ~10-16% on the
dataframe read/serialize paths **and** a ~27% smaller native library (measured on
PEGASE 13k). It is **opt-in and off by default** — normal builds are unchanged.

Requires **Oracle GraalVM** (PGO is not in GraalVM CE). pypowsybl already builds its
wheels on Oracle GraalVM (`distribution: 'graalvm'` in
`.github/actions/setup-before-build/action.yml`), so no edition change is needed.

## Build a PGO wheel/library

```bash
export JAVA_HOME=/path/to/oracle-graalvm-21
scripts/build-pgo.sh                      # self-contained IEEE 300-bus profile
scripts/build-pgo.sh /path/to/pegase.mat  # profile against a larger case (recommended for releases)
```

To produce a wheel instead of an editable install:

```bash
PGO_BUILD_CMD="python setup.py bdist_wheel" scripts/build-pgo.sh
```

## How it works (two passes)

GraalVM only dumps a profile from an **executable**'s exit path — a `--shared`
library never dumps one (isolate teardown, `System.exit`, and signals were all
verified not to trigger it). So we profile an instrumented executable that drives
the same Java hot paths the C entry points invoke, then feed the profile to the real
`--shared` build. PGO matches profiles by method, so those methods are optimized in
the shared library; only the thin C-entry-point wrappers stay unmatched.

1. `mvn package` → `pypowsybl-java.jar` (fat jar).
2. Tracing agent run of `PgoWorkload` → reachability config (regenerated each run, so
   it never drifts).
3. `native-image --pgo-instrument` → instrumented **executable**.
4. Run it → `pypowsybl.iprof`.
5. Normal build with `PYPOWSYBL_PGO_PROFILE=<profile>` → `native-image --pgo=<profile>`
   on the real `--shared` library.

`PgoWorkload.java` serializes the main element tables (the `get_*` paths) many times
and runs a few AC load flows. Pass a network file to profile a larger, more
representative case.

## Wiring into CI (proposed rollout)

- **Phase 1:** opt-in local builds via `scripts/build-pgo.sh` and the
  `-DPYPOWSYBL_PGO_PROFILE` CMake option. CI unchanged.
- **Phase 2 (this):** `snapshot-ci.yml` generates a profile inline on the Linux job
  (right before `Build wheel`) and the wheel build consumes it via
  `PYPOWSYBL_PGO_PROFILE`. It runs inline rather than as a separate job because the
  snapshot job builds the whole dependency chain from source, so a separate job would
  duplicate all of it. `continue-on-error` + a file guard mean a profiling failure
  silently falls back to a normal build - it never breaks the nightly. The profile is
  also uploaded as an artifact. Only Linux gets PGO in snapshot, for validation.
- **Phase 3:** enable it for released wheels in `full-ci.yml` (there the native build
  is a separate `native_lib_build` job, so a dedicated profile job feeding the
  artifact fits cleanly) once snapshot has proven it stable, optionally per-platform.

A stale profile degrades gracefully toward the non-PGO baseline (best-effort method
matching), so refreshing it is a tuning task, never a build blocker.
