# Session handoff — sensitivity-analysis perf + CGMES import memory scoping

This document is a self-contained handoff so another session can continue
the work without re-deriving context. It covers: the environment set up in
this session, the work that landed, the open upstream PRs, the CGMES memory
findings, and concrete next steps.

Everything below lives on the pypowsybl branch
`claude/sensitivity-analysis-optimization-uvp5ka` unless stated otherwise.

---

## 0. TL;DR of what happened

1. **Sensitivity analysis optimizations** (pypowsybl Java/C++/Python) — landed
   and verified end-to-end (built native-image, ran full test suites).
2. **Two upstream patches** (powsybl-core + powsybl-open-loadflow) — applied to
   the user's forks and opened as PRs *on the forks* (not upstream).
3. **CGMES import memory scoping** — a diagnostic investigation (no code change
   yet). Measured on SmallGrid and PEGASE-13k. Findings + ranked optimization
   list written up; a reproduction harness is committed. **This is the open
   thread most likely to be picked up next.**

---

## 1. Environment (must re-establish in a fresh session)

The container is ephemeral. A new session starts with only the pypowsybl repo
cloned. To reproduce anything Java/native, you must:

### Repos
- `/home/user/pypowsybl` — main repo, branch
  `claude/sensitivity-analysis-optimization-uvp5ka` (pushed to
  `gautierbureau/pypowsybl`). Session GitHub scope covers `gautierbureau/*`.
- Forks added via `add_repo` + cloned to `/workspace` (only `gautierbureau/*`
  can be added — upstream `powsybl/*` cannot be added to session scope):
  - `/workspace/powsybl-open-loadflow` (fork `gautierbureau/powsybl-open-loadflow`)
  - `/workspace/powsybl-core` (fork `gautierbureau/powsybl-core`)
  - Clones are shallow (`--depth 1`). Give clones a generous timeout (~10 min).

### Toolchain
- **GraalVM is required to build the pypowsybl native-image** (stock JDK 21 has
  no `native-image`). Installed this session at
  `/opt/graalvm/graalvm-jdk-21.0.11+9.1` via:
  ```
  curl -sSL -o /opt/graalvm/graalvm.tar.gz \
    https://download.oracle.com/graalvm/21/latest/graalvm-jdk-21_linux-x64_bin.tar.gz
  tar xzf ... -C /opt/graalvm
  ```
  Use it with `export JAVA_HOME=/opt/graalvm/graalvm-jdk-21.0.11+9.1 && export PATH=$JAVA_HOME/bin:$PATH`.
- `pybind11` for the C++ bindings build: `pip install pybind11`, then pass
  `-Dpybind11_DIR=$(python3 -c "import pybind11; print(pybind11.get_cmake_dir())")`.
- Python test deps that had to be installed: `pandas numpy pyoptinterface
  tabulate cffi matplotlib pytest-asyncio scipy` (+ `pip install -e .`).
  Note: `pip install -e .` must run with the GraalVM `JAVA_HOME` set.

### Build commands (verified working this session)
- Java only: `cd /home/user/pypowsybl/java && ./mvnw -pl pypowsybl compile`
- Full native build (Java → native-image → C++ → pybind11):
  ```
  cd /home/user/pypowsybl/cpp && mkdir -p build && cd build
  cmake -DBUILD_PYTHON_BINDINGS=ON \
        -Dpybind11_DIR=$(python3 -c "import pybind11; print(pybind11.get_cmake_dir())") ..
  make _pypowsybl        # ~5 min for native-image the first time
  ```
- native-image alone: `make native-image` (produces
  `cpp/build/java/pypowsybl-java.h` + `.so`).

---

## 2. Work that LANDED (pypowsybl branch, all pushed)

Branch `claude/sensitivity-analysis-optimization-uvp5ka`, commits oldest→newest:

| Commit | What |
|---|---|
| `3f3b4de` | Extract `MatrixFactorReader` from the inline factor-reader lambda in `SensitivityAnalysisContext`; hoist per-row `variableType`/`variableSet` resolution out of the columns loop (was R×C `network.getIdentifiable`, now R). Also drop the intermediate `double[]` in `createDoubleMatrix` and use `malloc` not `calloc`. |
| `469665b` | Replace `TreeMap<Integer,MatrixInfo>` in the result writer's hot path (`writeSensitivityValue`, called F×(K+1) times) with a sorted `int[]` + `MatrixInfo[]`; single-matrix case is a direct index, multi-matrix uses `Arrays.binarySearch`. Zero autoboxing. Folded two total-count loops into one. |
| `9d75145`,`25741d7` | Save the two upstream patches under `upstream-patches/`, organized per-repo. |
| `ba5d926` | **Leak fix**: `get_sensitivity_matrix`/`get_reference_matrix` never freed their native buffers. Added `freeSensitivityMatrix` `@CEntryPoint`; changed C++ getters to return `std::shared_ptr<matrix>` with a deleter; registered pybind11 `Matrix` with `std::shared_ptr<matrix>` holder. |
| `160d662` | Bulk-copy result matrices via native-backed `DoubleBuffer.put(double[],int,int)` (single memcpy) instead of per-cell writes; vectorize `process_ptdf` in numpy instead of a per-row `df.iloc` loop. |
| `3f539ac` | `upstream-patches/README.md` — design notes for all the above + the peak-memory-during-`run` analysis (4 ranked options). |
| `5248d76`,`c65d525` | CGMES memory scoping doc (see §4). |

**Verification done:** full native build succeeds; `SensitivityAnalysisTest`
(Java) passes; full Java suite 162 pass / 2 skipped / 0 fail; full Python suite
531 pass / 3 skipped / **13 fail — all `test_opf.py`, all `RuntimeError: IPOPT
library is not loaded`, an environment-only missing-solver issue, pre-existing,
unrelated to these changes** (confirmed by re-running one on the clean tree).
A 500-iteration pull-and-drop smoke test confirmed the leak fix.

The model identifier must NOT appear in any commit/PR/code artifact (chat only).

---

## 3. Upstream PRs (opened on the USER'S FORKS, not upstream)

The user owns forks and asked for PRs on the forks (head+base both on the fork).
Retarget to `powsybl/*` from the GitHub PR UI when ready.

- **powsybl-open-loadflow** — PR
  https://github.com/gautierbureau/powsybl-open-loadflow/pull/21
  branch `claude/buffer-factor-reader-once-on-ac-sensitivity-analysis`
  (fork commit `b99cd7b`). Buffers the `SensitivityFactorReader` once so the AC
  path doesn't read it twice (`getVariableTargetVoltageInfo` +
  `analyzeContingencySet`). Patch: `upstream-patches/powsybl-open-loadflow/…patch`.
  Compiles (`./mvnw compile`); full `mvn test` NOT run there.
- **powsybl-core** — PR https://github.com/gautierbureau/powsybl-core/pull/3
  branch `claude/lazy-sensitivity-analysis-result-lookup-indexes`
  (fork commit `c720b20`). Builds `SensitivityAnalysisResult` lookup indexes
  lazily (the user's own original patch; ~39% import speedup on their 2M-factor
  bench). Patch: `upstream-patches/powsybl-core/…patch`. Not built/tested in-session.

Note: pypowsybl uses the *streaming* `SensitivityResultWriter` API and never
builds a `SensitivityAnalysisResult`, so the core patch does not help pypowsybl
directly — it's a win for other core callers. The OLF patch DOES help pypowsybl
(its `MatrixFactorReader` is the streaming reader being double-read).

---

## 4. CGMES import memory scoping (OPEN — most likely next thread)

**Status: diagnostic only, no code change yet.** Full write-up with numbers,
class histograms, JFR attributions, and ranked fixes is in
`upstream-patches/cgmes-import-memory-scoping.md` (commit `c65d525`). Read that
file first — summary here.

### Measured (powsybl-core 7.2.1 jars, GraalVM JDK21 HotSpot)
Two cases: SmallGrid (4.85 MB XML, 59k triples) and **PEGASE-13k (104.5 MB XML,
1.42M triples — the production-scale reference)**.

PEGASE-13k, default params:
- **~3.6 GB total allocation churn** per import (~35× input) — this is the GC
  pressure that dominates time.
- **Peak heap ~1 GB** (~9.5× input), peak occurs *during conversion*.
- Retained triplestore during import: 267 MB (~197 B/statement).
- Network retained after import (default): 149 MB. With both
  `store-cgmes-model-as-network-extension` /
  `store-cgmes-conversion-context-as-network-extension` flags on: **429 MB**.
- Load 6-8 s + convert 5-8 s.

### Where it goes
- rdf4j `MemoryStore` per-value statement-list indexing
  (`MemStatementList`+`MemStatement[]`+`AtomicReference`) = **41% of the resident
  store** (109 MB); value-factory weak/soft interning registry another 14%.
- Allocation churn: SPARQL query evaluation machinery ~25-30% (overtakes XML
  parsing at scale), string handling ~18%, PropertyBag `HashMap` population ~12%.
- Notable waste: an rdf4j-internal `System.getProperty` in the per-iteration
  query path burns ~100 MB/import (2.8%).

### Ranked optimization opportunities (from the doc)
1. **Dedupe repeated SPARQL queries** — `baseVoltages()` runs 3×;
   `terminals()`, `switches()`, `transformers()`, `transformerEnds()`,
   `acLineSegments()`, `seriesCompensators()` each run 2×. Low-risk, real win on
   churn+time. Fix by threading results through the call orchestration, NOT
   blanket memoization (which would raise retention).
2. **Canonicalize PropertyBag value strings** at materialization in
   `TripleStoreRDF4J.query()` (~line 208) — fresh String per cell today, no
   dedup; a per-query/per-import canonical `HashMap<String,String>` collapses
   repeated ids/URIs.
3. **Lazy-init the 3 always-empty ArrayLists** in `PropertyBag`
   (`resourceNames`/`classPropertyNames`/`multiValuedPropertyNames`,
   `PropertyBag.java:267-269`) — ~72 B dead weight per result row.
4. **Streaming query API** — most `convert(cgmes.<type>(), …)` sites iterate
   once and discard; a `forEachQueryResult`-style API avoids building full
   `PropertyBags` for the biggest sets. Bigger change, biggest churn win after 1-2.
5. Release update-phase Context caches eagerly (pattern already exists in
   `LimitsMapping`/`RegulatingControlMapping`).
6. (Structural/long-term) The parse-everything-into-rdf4j + SPARQL design *is*
   the floor; a streaming StAX profile reader would be an order-of-magnitude
   change but is a rewrite.
7. (Upstream rdf4j) the per-iteration `System.getProperty` — report/patch in rdf4j.

Non-findings / already-fine: post-import retention is fine by default (model
closed mid-convert); rdf4j already interns values *in the store*; pypowsybl
inherits the good defaults (doesn't set the store-as-extension flags).

### Reproduction harness (committed source, regenerate data)
`upstream-patches/cgmes-mem-harness/` contains:
- `pom.xml` — pulls powsybl-cgmes-conversion + triple-store-impl-rdf4j 7.2.1,
  copies deps to `target/lib`. **Note the `commons-io 2.16.1` pin** — required,
  else `ZipArchiveDataSource` throws `NoSuchMethodError FileTimes.fromUnixTime`.
- `src/main/java/bench/CgmesMemBench.java` — phases (load model → convert →
  close → drop), forced GC + `MemoryPoolMXBean` peak + `ThreadMXBean`
  allocated-bytes + `jcmd GC.class_histogram` at each phase.
- `m2mat.py` — converts a MATPOWER `.m` case to MAT v5 (`scipy.io.savemat`,
  struct `mpc`) for the pegase dataset.

To regenerate the PEGASE-13k CGMES dataset (not committed — ~104 MB):
```
curl -sSL -o case13659pegase.m \
  https://raw.githubusercontent.com/MATPOWER/matpower/master/data/case13659pegase.m
python3 m2mat.py case13659pegase.m case13659pegase.mat      # needs scipy
python3 -c "import pypowsybl as pp; n=pp.network.load('case13659pegase.mat'); n.save('pegase13k', format='CGMES')"
zip pegase13k.zip pegase13k_EQ.xml pegase13k_SSH.xml pegase13k_SV.xml pegase13k_TP.xml
```
Run the bench:
```
cd upstream-patches/cgmes-mem-harness
JAVA_HOME=/opt/graalvm/... ./…/mvnw -q package -DskipTests
java -Xmx12g -Dorg.slf4j.simpleLogger.defaultLogLevel=warn \
  -cp "target/cgmes-mem-1.0.jar:target/lib/*" \
  bench.CgmesMemBench <path>/pegase13k.zip out-dir <retainExtensions:true|false>
```
For allocation attribution add `-XX:StartFlightRecording=filename=x.jfr,settings=profile`
and use `jfr view allocation-by-class x.jfr` / `jfr view allocation-by-site x.jfr`.
SmallGrid data is in-repo at
`powsybl-core/cgmes/cgmes-conformity/src/main/resources/conformity/cas-1.1.3-data-4.0.3/SmallGrid/NodeBreaker/…`.

---

## 5. Recommended next steps (in priority order)

1. **Prototype CGMES fixes #1-#3 in `/workspace/powsybl-core`** and measure
   before/after with the PEGASE harness. These are low-risk and the numbers are
   there to justify them. Then open PRs on `gautierbureau/powsybl-core`
   (same fork-PR workflow as §3).
2. Decide whether to retarget the two existing fork PRs (§3) to `powsybl/*`
   upstream, or land the CGMES work first and bundle.
3. (Optional) the peak-memory-during-`run` sensitivity options in
   `upstream-patches/README.md` §"Investigated but not yet applied" — gated on a
   real profile; the CGMES work is higher-value.

### Working conventions for the next session
- Develop on `claude/sensitivity-analysis-optimization-uvp5ka`; commit + push
  when work is complete; `git push -u origin <branch>` with retry/backoff.
- Do NOT open PRs unless asked. When asked, PR on the user's FORKS only
  (head+base on fork), never on `powsybl/*`.
- Do NOT put the model identifier in any pushed artifact.
- The `test_opf.py` IPOPT failures are environmental — ignore them.
- Keep temp/bench files in the session scratchpad; only commit source, never the
  big generated CGMES/MAT data.
