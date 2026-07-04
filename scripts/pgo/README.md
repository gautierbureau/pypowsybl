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

- **Phase 1 (this):** opt-in local builds via `scripts/build-pgo.sh` and the
  `-DPYPOWSYBL_PGO_PROFILE` CMake option. CI unchanged.
- **Phase 2:** add a `pgo_profile` job to `snapshot-ci.yml` that uploads
  `pypowsybl.iprof` as an artifact; the native build job consumes it via
  `PYPOWSYBL_PGO_PROFILE`. Nightly snapshot wheels get PGO + real-world validation.
- **Phase 3:** enable the same in `full-ci.yml` for released wheels once snapshot has
  proven it stable.

A stale profile degrades gracefully toward the non-PGO baseline (best-effort method
matching), so refreshing it is a tuning task, never a build blocker.
