# CGMES Import — Memory Consumption Scoping

Scoping analysis of where memory goes during CGMES import in powsybl-core,
based on (a) a code walk of `triple-store`, `cgmes-model`, and
`cgmes-conversion` on current `main`, and (b) empirical measurements
(heap checkpoints, class histograms, thread-allocation counters, JFR
allocation profiling) of the SmallGrid NodeBreaker conformity case
(EQ+SSH+SV+TP + boundary, **4.85 MB of XML**) against powsybl-core 7.2.1
jars on a HotSpot JVM (GraalVM JDK 21).

## Headline numbers (SmallGrid, 4.85 MB input)

| Metric | Value | Ratio vs input |
|---|---|---|
| Triplestore load: bytes **allocated** | 112 MB | 23× |
| Triplestore load: **peak heap** | 94-100 MB | ~20× |
| Triplestore **retained** (live after GC) | 13.5 MB | 2.8× |
| Conversion: bytes **allocated** | 128 MB | 26× |
| Conversion: peak heap | 58 MB | 12× |
| IIDM Network retained (default params) | ~7 MB | 1.5× |
| Network + model + context retained (both store-flags on) | 19.1 MB | 3.9× |
| **Total allocation churn for one import** | **~240 MB** | **~50×** |

Scaling linearly, a 1 GB CGM implies ~50 GB of transient allocations
(GC pressure — this is what makes import slow and memory-hungry even
when the retained set fits), with a live triplestore of roughly
2.5-3 GB held for the whole import. That is the "huge" consumption.

Import timing on this case: 1.0-1.3 s load + ~1.0 s conversion.

## Where the memory goes

### 1. The rdf4j in-memory triplestore (the resident floor)

Every CGMES XML file is parsed entirely into a single
`SailRepository(new MemoryStore())` — hard-coded in
`TripleStoreRDF4J.java:61`; one repository per CGMES model, one named
graph per file. There is **no disk-backed option** and no option to
skip profiles: whatever files are in the datasource are fully loaded.

Class histogram of the loaded store (58 899 statements for SmallGrid,
13.5 MB live) — dominant classes:

| Class | Instances | Bytes | Share |
|---|---|---|---|
| `byte[]` (String bodies) | 50 562 | 5.0 MB | 37% |
| `MemStatement` | 58 899 | 2.4 MB | 17% |
| `MemStatement[]` | 21 856 | 2.0 MB | 15% |
| `MemStatementList` | 46 793 | 1.9 MB | 14% |
| `java.lang.String` | 49 280 | 1.2 MB | 9% |
| `AtomicReference` | 46 797 | 0.7 MB | 5% |
| `MemIRI` + `MemLiteral` | 17 446 | 0.8 MB | 6% |

Two observations:
- **String data is the single largest chunk (~46% incl. headers).**
  rdf4j interns values in a `MemValueFactory`, so repeated IRIs are
  shared — this is already fairly tight.
- **~230 bytes/statement all-in.** The `MemStatementList` +
  `MemStatement[]` + `AtomicReference` triplet (~4.6 MB here) is rdf4j
  5.x's per-value statement-list indexing — the price of SPARQL-queryable
  in-memory storage.

### 2. Allocation churn (the 50×)

JFR allocation-by-site for the full import:

| Site | Share | What it is |
|---|---|---|
| `String.substring` | 15% | RDF/XML parsing + `Value.stringValue()` |
| `Unsafe.allocateInstance` + `MemValueFactory$$Lambda` + `ConcurrentHashMap.initTable` | 26% | rdf4j value-factory interning machinery (weak registries) |
| `AbstractStringBuilder.append` | 7% | IRI/string building |
| `HashMap.putVal` + `resize` | 7% | PropertyBag population |
| Query evaluation (`ArrayBindingSet`, `MemStatementIterator`, `StatementPatternQueryEvaluationStep`, …) | ~15% | SPARQL evaluation of the ~40-50 conversion queries |
| `byte[]` + `String` combined (by class) | 36% | fresh strings everywhere |

Load phase (112 MB) is parser + store-insertion churn. Conversion
phase (128 MB) is SPARQL evaluation + PropertyBags materialization.

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
  the rdf4j repository. Measured: heap **drops** during conversion
  (18 MB → 11.6 MB) because the triplestore dies before `convert()`
  returns. Only the Network (~7 MB incl. CGMES aliases/properties)
  survives.
- With both flags on: 19.1 MB retained vs 7.2 MB — the whole
  triplestore + Context caches live as long as the Network
  (`CgmesModelExtensionImpl` / `CgmesConversionContextExtensionImpl`).
- **pypowsybl does not set these parameters** → default (off) applies.
- `remove-properties-and-aliases-after-import` exists to strip the
  IIDM-side CGMES property copies too (disables later SSH update).

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
`jfr view allocation-by-class` / `allocation-by-site`.

Input: SmallGrid NodeBreaker BaseCase Complete (EQ+SSH+SV+TP+EQ_BD+TP_BD)
zipped from `cgmes-conformity` main resources.
