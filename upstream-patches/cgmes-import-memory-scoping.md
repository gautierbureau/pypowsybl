# CGMES Import — Memory Consumption Scoping

Scoping analysis of where memory goes during CGMES import in powsybl-core,
based on (a) a code walk of `triple-store`, `cgmes-model`, and
`cgmes-conversion` on current `main`, and (b) empirical measurements
(heap checkpoints, class histograms, thread-allocation counters, JFR
allocation profiling) against powsybl-core 7.2.1 jars on a HotSpot JVM
(GraalVM JDK 21), on two cases:

- **SmallGrid** NodeBreaker conformity case (EQ+SSH+SV+TP + boundary,
  **4.85 MB** of XML, ~59 k triples);
- **PEGASE 13k** — the 13 659-bus / 20 467-branch PEGASE case
  (MATPOWER → IIDM → CGMES export), bus-branch EQ+SSH+SV+TP,
  **104.5 MB** of XML, **1 424 167 triples**. This is the
  production-scale reference.

## Headline numbers

| Metric | SmallGrid (4.85 MB) | PEGASE 13k (104.5 MB) | Ratio vs input (PEGASE) |
|---|---|---|---|
| Load: bytes **allocated** | 112 MB | 1.63-1.74 GB | 16× |
| Load: **peak heap** | 94-100 MB | 619-707 MB | ~6.5× |
| Triplestore **retained** (live after GC) | 13.5 MB | **267.5 MB** | 2.6× |
| Conversion: bytes **allocated** | 128 MB | 1.97-2.01 GB | 19× |
| Conversion: **peak heap** | 58 MB | **955-1155 MB** | ~9.5× |
| IIDM Network retained (default params) | ~7 MB | **149 MB** | 1.4× |
| Network + model + context retained (both store-flags on) | 19.1 MB | **428.7 MB** | 4.1× |
| **Total allocation churn for one import** | ~240 MB | **~3.6 GB** | **~35×** |
| Import wall-clock (load + convert) | ~2.3 s | 11-16 s | — |

The dominant experience-level costs on PEGASE 13k:
- **Peak heap ~1 GB for a 104 MB IGM** (~9.5×) — the moment of maximum
  pressure is *during conversion*, when the triplestore (267 MB), the
  partially-built Network (~150 MB), the Context caches and the
  in-flight SPARQL materializations all coexist.
- **~3.6 GB of transient allocations** — GC pressure that dominates
  import time.
- Retention after import is modest by default (149 MB Network); turning
  on the two store-as-extension flags nearly **triples** it (428 MB).

A 1 GB CGM at these ratios: ~2.6 GB live triplestore, ~10 GB peak heap,
~35 GB churn. That is the "huge" consumption.

## Where the memory goes

### 1. The rdf4j in-memory triplestore (the resident floor)

Every CGMES XML file is parsed entirely into a single
`SailRepository(new MemoryStore())` — hard-coded in
`TripleStoreRDF4J.java:61`; one repository per CGMES model, one named
graph per file. There is **no disk-backed option** and no option to
skip profiles: whatever files are in the datasource are fully loaded.

Class histogram of the loaded store — PEGASE 13k (1 424 167 statements,
267.5 MB live, **~197 bytes/statement all-in**):

| Class | Instances | Bytes | Share |
|---|---|---|---|
| `MemStatement` | 1 424 167 | 57.0 MB | 21% |
| `MemStatement[]` | 510 142 | 49.2 MB | 18% |
| `MemStatementList` | 1 064 261 | 42.6 MB | 16% |
| `byte[]` (String bodies) | 627 135 | 31.6 MB | 12% |
| `AtomicReference` | 1 064 265 | 17.0 MB | 6% |
| `java.lang.String` | 625 853 | 15.0 MB | 6% |
| `WeakHashMap$Entry` + `WeakReference` + `SoftReference` | ~978 k | 36.2 MB | 14% |
| `MemIRI` + `MemLiteral` | 373 180 | 16.8 MB | 6% |

Three observations:
- **The per-value statement-list indexing machinery
  (`MemStatementList` + `MemStatement[]` + `AtomicReference`) is the
  single biggest bucket: ~109 MB, 41% — nearly 2× the statement objects
  themselves.** This is rdf4j 5.x's price for SPARQL-queryable
  in-memory storage.
- **The value factory's weak/soft interning registry costs another
  ~36 MB (14%)** (`WeakHashMap$Entry`/`WeakReference`/`SoftReference`
  around interned `MemIRI`/`MemLiteral` values).
- String data proper is ~47 MB (18%) on PEGASE. (On the small,
  string-heavy SmallGrid case strings were ~46% — profile mix matters,
  but structure dominates at scale.)

### 2. Allocation churn (the ~35×)

JFR allocation-by-site for the full PEGASE 13k import (~3.7 GB total):

| Site cluster | Share | What it is |
|---|---|---|
| `String.substring` + `String.getBytes` + `AbstractStringBuilder` | ~18% | RDF/XML parsing + `Value.stringValue()` + `PropertyBag.extractIdentifier` |
| SPARQL evaluation (`ArrayBindingSet*`, `StatementPatternQueryEvaluationStep*`, `MemStatementIterator`, `createBindingSet`, `SimpleBinding`, `Value[]`) | ~25-30% | evaluating the ~40-50 conversion queries (several run 2-3×) |
| `HashMap.putVal` + `resize` + `HashMap$Node` | ~12% | PropertyBag population per result row |
| `ParsedIRI` + RDFXML parser internals | ~8% | per-IRI parsing during load |
| `MemorySailStore.addStatement` + insert path | ~4% | store insertion |
| `System.getProperty` | 2.8% (~100 MB!) | rdf4j per-iteration debug-flag check in the query-evaluation path — pure waste at this call volume |
| `byte[]` + `String` combined (by class) | ~31% | fresh strings everywhere |

Load phase (~1.7 GB) is parser + store-insertion churn. Conversion
phase (~2.0 GB) is SPARQL evaluation + PropertyBags materialization —
at production scale the query-evaluation machinery overtakes parsing
as the top allocator.

### 3. PropertyBags materialization

Every query result is fully materialized before use
(`TripleStoreRDF4J.query`, lines 179-227):
- `PropertyBag extends HashMap<String,String>` — one HashMap per result
  row. ~37 bytes of map overhead per property + a **fresh value String
  per cell** (`getValue().stringValue()`) with **no deduplication** —
  the same container id / base-voltage id / namespace referenced by
  thousands of rows is stored as that many distinct Strings.
- Keys are shared per query (the binding-name list), not interned
  across queries.
- Each PropertyBag also eagerly allocates **three always-empty
  `ArrayList`s** (`resourceNames`, `classPropertyNames`,
  `multiValuedPropertyNames`, `PropertyBag.java:267-269`) — ~72 bytes
  of dead weight per row, only used on the export path.
- `QueryResults.distinctResults(...)` wraps every query and holds a
  seen-set of all solutions for the duration of iteration.

### 4. Redundant queries (each re-materializing full result sets)

No memoization exists at the `CgmesModelTripleStore` accessor level —
each call is a fresh SPARQL query. Observed duplicates in one import:
- `baseVoltages()` — **3×** (`Conversion.java:160,161,176`)
- `terminals()` — **2×** (model cache build + `Context.buildUpdateCache`)
- `switches()`, `transformers()`, `transformerEnds()`,
  `acLineSegments()`, `seriesCompensators()` — **2×** each
  (`NodeContainerMapping.build()` adjacency pass at
  `NodeContainerMapping.java:159-165`, then again in the conversion loop)
- several SSH-updated types re-queried in the update phase.

### 5. Duplicated resident copies during conversion

While conversion runs, the same data exists in up to three shapes:
1. triples in the MemoryStore,
2. `Context.cachedGrouped*` — 7 grouped caches holding entire result
   sets (transformer ends, tap changers + table points, shunt points,
   reactive curves) for the whole conversion (`Context.java:161-169`),
3. the update phase creates a **second `Context`** with 10 more
   `Map<String, PropertyBag>` caches (terminals, tap changers,
   regulating controls, operational limits, SV voltages, switches, …)
   coexisting with the import context (`Conversion.java:267-295`,
   `Context.java:206-224`).

Plus the model-level caches (`cachedTerminals`, `cachedNodesById`, …,
`AbstractCgmesModel.java:228-234`).

### 6. Post-import retention (the part that is already fine by default)

- Default parameters (`store-cgmes-model-as-network-extension=false`,
  `store-cgmes-conversion-context-as-network-extension=false` — both
  default false on main and 7.x): `Conversion.convert()` calls
  `cgmes.close()` mid-flow (`Conversion.java:269-272`), shutting down
  the rdf4j repository before `convert()` returns. Only the Network
  survives (PEGASE: 149 MB incl. CGMES aliases/properties).
- With both flags on (PEGASE): **428.7 MB retained vs 148.9 MB
  default** — the whole triplestore (267 MB) + Context caches live as
  long as the Network (`CgmesModelExtensionImpl` /
  `CgmesConversionContextExtensionImpl`).
- **pypowsybl does not set these parameters** → default (off) applies.
- `remove-properties-and-aliases-after-import` exists to strip the
  IIDM-side CGMES property copies too (disables later SSH update).

### 7. IIDM-side per-identifiable scaffolding (bonus finding)

The phase-2 histogram of the converted PEGASE Network shows, alongside
the expected element data, **104 239 `java.util.Properties` +
104 464 `ConcurrentHashMap` + 348 998 `HashMap` + 137 369
`LinkedHashMap`** (~38 MB of map objects before contents). IIDM's
`AbstractIdentifiable` allocates properties/aliases containers per
identifiable even when empty or near-empty; with CGMES imports
attaching several aliases/properties per element this is populated,
but the container-per-identifiable pattern is itself a measurable
share of the 149 MB Network. This is an iidm-impl finding, not a
CGMES one — noted here because CGMES imports are where networks with
100 k+ identifiables typically come from.

## Ranked optimization opportunities

1. **Deduplicate the repeated queries** (`baseVoltages` 3×,
   `terminals`/`switches`/`transformers`/`transformerEnds`/
   `acLineSegments`/`seriesCompensators` 2×). Each duplicate re-runs
   SPARQL evaluation and re-materializes a full PropertyBags. Low-risk,
   pure win on both churn and time; roughly 15-25% of conversion-phase
   allocations by inspection of the duplicated sets. Fix shape: cache
   at the call orchestration level (pass results down), not blanket
   memoization (which would raise retention).
2. **Canonicalize PropertyBag value strings** at materialization
   (`TripleStoreRDF4J.query`, line ~208) with a per-query (or
   per-import) `HashMap<String,String>` canonical map. Repetitive cells
   (ids, container refs, enum-like URIs) collapse to shared instances.
   Cuts transient footprint and every downstream cache (`Context`
   grouped caches, update caches, model caches) that holds those bags.
3. **Lazy-init the three per-PropertyBag ArrayLists**
   (`PropertyBag.java:267-269`). Trivial patch, ~72 B × (number of
   result rows across ~45 queries, several 10k-100k rows on real cases).
4. **Stream instead of materialize where the consumer is a single
   loop.** Most `convert(cgmes.<type>(), …)` call sites iterate once
   and discard. A `forEachQueryResult`-style API on the triplestore
   would avoid building full `PropertyBags` for the biggest sets
   (terminals, operational limits, SV values). Bigger API change,
   biggest churn win after #1/#2.
5. **Release update-phase caches eagerly.** The second (update)
   `Context`'s 10 maps could be dropped per-type as consumed, and the
   grouped caches on the import context could be cleared after the
   equipment that needs them is converted (LimitsMapping and
   RegulatingControlMapping already do exactly this — the pattern
   exists in-tree).
6. **(Structural, long-term) Alternative to full SPARQL triplestore.**
   The resident floor (~230 B/statement, 2.8× input) and most of the
   50× churn are inherent to parse-everything-into-rdf4j + query-with-
   SPARQL. A streaming/StAX profile reader that fills typed structures
   directly would change the game by an order of magnitude, but is a
   rewrite of the model layer, not an optimization. (The repo's
   `cgmes-model-alternatives` module exists to benchmark query
   strategies — evidence this cost is a known concern.)

7. **(Upstream rdf4j) per-iteration `System.getProperty` in query
   evaluation** — 2.8% of all allocation pressure (~100 MB per PEGASE
   import) from a debug-flag check inside the iteration machinery.
   Worth reporting/patching upstream in rdf4j; zero powsybl code change.

Non-findings / already-fine:
- Post-import retention is fine by default (model closed mid-convert);
  pypowsybl inherits the good defaults.
- rdf4j already interns values in the store itself; the interning gap
  is only on the query-result path.
- `IsolationLevels.NONE` is already used for loading.

## Reproduction

Harness (session scratchpad, not committed):
`cgmes-mem` maven project — phases: load `CgmesModelFactory.create` →
convert (`Conversion.convert`) → close → drop; at each phase forced GC,
`MemoryPoolMXBean` peaks, `ThreadMXBean.getThreadAllocatedBytes`, and a
`jcmd GC.class_histogram` dump; plus a JFR `settings=profile` run and
`jfr view allocation-by-class` / `allocation-by-site`. JVM: GraalVM
JDK 21 (HotSpot mode), `-Xmx12g` for PEGASE.

Inputs:
- SmallGrid NodeBreaker BaseCase Complete (EQ+SSH+SV+TP+EQ_BD+TP_BD)
  zipped from `cgmes-conformity` main resources.
- PEGASE 13k CGMES: `case13659pegase.m` (MATPOWER repo) → parsed to
  MAT v5 (`scipy.io.savemat`, struct `mpc`) → imported via pypowsybl's
  MATPOWER importer → `network.save(..., format='CGMES')` → EQ 53.8 MB
  + SSH 16.3 MB + SV 22.0 MB + TP 12.3 MB, zipped (5.5 MB). Bus-branch
  topology; no boundary files (self-contained IGM). Numbers above are
  from single runs; load/convert wall-clock varied ±20% across runs,
  byte counts ±3%.
