<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
# Drishti Tools: shared, domain-free utilities for the Drishti family

**Status: design for the lead and the owner, written 2026-11-21. Nothing built, nothing run, no decision taken.** Read from git
only: Drishti `main` at `9ce7c3170`; `drishti-lake` `origin/main` at `84cb76c`; `drishti-rachana` `origin/g2/integration` at
`40fa759` (and `origin/g3/server` for what G3 already changed). Line counts are `wc -l` over `src/main`. "Differing lines" is a
`diff` of the two files after removing the package names.

**The request (owner, 2026-11-21).** A private Maven repository `drishti-tools` of shared utilities with no domain in them, used
by Drishti, Drishti Lake, Rachana's server and tools, and later Drishti Risk. Less repeated boilerplate; rich over time; **start
with xlsx**; use it wherever we can. Drishti Elements is JavaScript only and is not a consumer.

## 0. The answer in one page

1. **The real duplication is smaller and differently placed than the notes assumed.** Measured (section 1):
   - **Drishti and Lake hold the same code twice: about 2,900 lines** that both still need after G4 (TLS and proxy 1,902;
     settings and placeholders 342; single-flight and the load guard 272; two test fixtures 420; and the roots class, which
     stays product-specific). The copies differ by 2 to 29 lines each: they are one code base today and will drift.
   - **Drishti writes .xlsx in four separate ways** (1,580 lines): `XlsxWriter` + charts + formulas, the pivot export's own
     writer, the playground's streaming writer, and the capacity workbook. That is the "one way per job" rule broken inside one
     repository, and it is why xlsx is the right first module.
   - **Rachana has almost nothing to share: about 120 lines** (path anchoring inside `ServerConfig`, `Ed25519`). `Sha384`, `Rlib`
     (the deterministic zip) and `ViewJson` live in Rachana **core**, are written in plain Java on purpose (the JavaScript build
     has no `MessageDigest` or `java.util.zip`) and **must stay where they are**. They are not duplicates to remove.
   - The archive readers (1,105 lines, twice) are **not** a tools candidate: Drishti's copy is deleted by the Lake migration
     itself in G4, and the code reads through Lake's range reader.
   - **"Windows-ROOT" is in no code in any of the three repositories.** It exists only as a `MAVEN_OPTS` line in Rachana's
     `java/README.md`. TLS trust today is PEM, PKCS12/JKS or the JVM default. Windows-ROOT is a new feature (section 6), not a copy.
2. **xlsx: keep our own writer, as one module, and prove it against Apache POI in tests.** POI is the de-facto library but
   there is no official one (the standard is ECMA-376 / ISO 29500); POI brings about a dozen jars and roughly 15 to 20 MB into
   every consumer, against a JDK-only module of about 1,300 lines after the four writers are folded. POI becomes the **test
   oracle** (test scope only), and the **reader**, when we add one, is a thin wrapper over POI in its own artifact (TL-2, TL-3).
3. **Timing: create the repository now, with five small modules; Lake adopts in G3; Rachana does not; Drishti adopts in G4.**
   The one argument that makes "now" right for Lake: `com.ash.lake.Placeholders` and `com.ash.lake.SettingsFile` are in Lake's
   public `api-surface.txt`. Once Lake tags `v1.0.0` they are frozen there and the duplication is permanent (or a 2.0). For
   Rachana the gain (120 lines) does not pay for giving a dependency-free server its first outside dependency days before its
   tag (TL-7). The churn the lead fears is real for Rachana and small and mechanical for Lake (19 files deleted, imports changed).
4. **Cost.** Now: **10.5 bd** in the tools repository + **1.5 bd** in Lake. G4: **5 bd** in Drishti. Combined i8: about **11.5 bd**
   of extras, each taken only when a second consumer exists.

## 1. The duplicates, measured

### 1.1 The same code in Drishti and in Lake (both need it after G4)

| What | Drishti (`main`) | Lines | Lake (`origin/main`) | Lines | Differing lines | Non-JDK imports |
|---|---|---|---|---|---|---|
| TLS: `TlsContexts`, `TlsSettings`, `TlsMaterial`, `PemReader`, `Der`, `CertInfo`, `Secrets`, `TlsException` | `kernel/drishti-api/.../api/tls/` (+ `TlsHttp` 135, `package-info` 22, Drishti only) | 1,279 | `java/lake-io/.../lake/io/net/` | 1,121 | `TlsContexts` 4, `TlsSettings` 9 | none |
| Proxy: `ProxySettings`, `NoProxy`, `SiteProxy`, `ProxyException` | `kernel/drishti-api/.../api/proxy/` | 764 | `java/lake-io/.../lake/io/net/` | 740 | `ProxySettings` 19 | none |
| `SettingSpec` (the record TLS and proxy describe their settings with) | `kernel/drishti-api/.../api/SettingSpec.java` | 41 | `java/lake-io/.../io/net/SettingSpec.java` | 41 | 0 | none |
| `Placeholders` (`${key}`, `${key:default}`) | `kernel/drishti-settingsfile/.../settingsfile/` | 147 | `java/lake-api/.../lake/Placeholders.java` | 145 | 14 | none |
| `SettingsFile` (the ini named on the command line) | same module | 198 | `java/lake-api/.../lake/SettingsFile.java` | 197 | 29 | none |
| Roots and path anchoring | `kernel/drishti-api/.../api/Roots.java` | 167 | `java/lake-api/.../lake/LakeRoots.java` | 162 | 93 (product-specific) | none |
| `SingleFlight` | `kernel/drishti-api/.../api/SingleFlight.java` | 66 | `java/lake-io/.../lake/common/SingleFlight.java` | 66 | 2 | none |
| `LoadGuard` (date window, drop share) | `kernel/drishti-api/.../api/LoadGuard.java` | 209 | `java/lake-io/.../lake/common/LoadGuard.java` | 206 | 17 | none |
| Test fixtures `FakeProxy`, `TlsFixture` | `testing/drishti-testkit/.../testkit/` | 420 | `java/lake-tck/.../lake/tck/` | 420 | 2 each | none |
| `TempFiles` | `kernel/drishti-common/.../common/TempFiles.java` | 266 | `java/lake-io/.../lake/common/TempFiles.java` | 131 | 229 (already diverged) | none |

Users in Drishti outside the lake modules (so the code stays needed after G4): TLS and proxy in 51 main files across 21 modules
(every connector plug-in, `drishti-ai`, the server); placeholders and the settings file in 28; `LoadGuard` in 12 (nine connector
plug-ins); `SingleFlight` in 6; `TempFiles` in 27. In Lake, `io.net` is used by `lake-delta` (6 files) and `lake-iceberg` (3) and is
**not** in the public surface; `Placeholders`, `SettingsFile` and `LakeRoots` **are** (`java/api-surface.txt`, lines 33 to 37).

**Not tools candidates** (twice today, once after G4 without us doing anything): `Archives` 635/636, `TarStream` 250/250,
`GzipMembers` 219/219 (`lake/drishti-lakeload` and `java/lake-load`); they import Lake's `FileAccess` and `RangeReader`, and
Drishti's copy leaves with the loader in G4.

### 1.2 Repeated inside Drishti

| What | Files | Lines |
|---|---|---|
| **xlsx writer 1**: the exports | `kernel/drishti-server/.../server/dist/XlsxWriter.java` 378, `XlsxCharts.java` 170, `XlsxFormulas.java` 125 | 673 |
| **xlsx writer 2**: the pivot export | `compute/drishti-bicompute/.../bicompute/export/Xlsx.java` (its own `num`, `ref`, `col`, `esc`) | 241 |
| **xlsx writer 3**: the playgrounds (streaming, formulas, named ranges, lists) | `kernel/drishti-server/.../server/playground/SheetStream.java` 249, `WorkbookStream.java` 198 | 447 |
| **xlsx writer 4**: the capacity workbook | `kernel/drishti-server/.../server/capacity/CapacityWorkbook.java` | 219 |
| Callers of the four | 19 main files (7 in `modules/banking`: the VaR, FRTB and SIMM playground books, the regulatory report, sign-off) | |
| SHA and hex helpers | **29** private or static `sha`, `sha256`, `sha1`, `hex` methods (for example `LockFile`, `ModulePack`, `PackArchive`, `EmbedKeys`, `Ledger`, `SessionStore`); `MessageDigest.getInstance` in 72 main files | about 300 |
| Canonical JSON (sorted keys, for identities and signatures) | `RunSources.canonical`, `QuestionNormaliser`, `MqParser`, `compute/.../reactive/Results.java`, `net/.../digest/Digest.java` (Jackson, sorted) | 5 places |
| Zip writing | 9 main files use `ZipOutputStream` (four are the xlsx writers; `DmodZip` 165, `Dmod`, `PackFragment`, `ExportService`, `ModulePublicController`); 13 read zips | |
| CSV | `server/dist/CsvWriter.java` 89, `plugins/drishti-connectors/.../file/CsvReader.java` 103 | 192 |
| Retry | `kernel/drishti-common/.../RetryPolicy.java` 162 (+ `RetryTracker`, `RetryBoard`, product-specific) | 162 |

### 1.3 Rachana (`origin/g2/integration`)

| What | File | Lines | Verdict |
|---|---|---|---|
| SHA-384 in plain Java | `java/rachana-core/.../lib/Sha384.java` | 94 | **Stays in core.** Must run in the JavaScript build |
| Deterministic zip (own CRC, stored entries) + `hex` | `java/rachana-core/.../lib/Rlib.java` | 167 | **Stays in core**, same reason |
| Canonical ViewModel JSON | `java/rachana-core/.../view/ViewJson.java` | 101 | **Stays in core** |
| Ed25519 sign and verify (JDK `Signature`) | `java/rachana-tools/.../tools/Ed25519.java` | 86 | Could use `tools-codec` later (i8) |
| Sorted JSON writer for the language files | `java/rachana-tools/.../tools/LangJson.java` | 105 | Already deleted on `g3/server` |
| Config paths: absolute or `${config}` / `${project}`, bare relative refused | `java/rachana-server/.../server/ServerConfig.java` lines 482 to 520 (of 632) | about 40 | Same rule as `Roots.anchorValue`; could use `tools-settings` later |
| TLS / proxy | `server.behind-tls-proxy` is a flag only; the server has no TLS or proxy code | 0 | Nothing to share today |

`rachana-server` and `rachana-tools` depend on Rachana's own modules and the JDK, nothing else.

## 2. Modules, coordinates, packages

Repository `drishti-tools` (private, proprietary), layout as Lake's: `java/` with one parent POM, `docs/`, `tools/release`.
Group `com.ash.tools`; Java package `com.ash.tools.<module>`; JDK 21 (`maven.compiler.release` 21, enforcer `[21,)`); every module
its own jar; one `tools-bom`. Version `1.0.0` at the first tag; one property in each consumer, `drishti.tools.version`.

| Artifact | Package | Contents | Dependencies | When |
|---|---|---|---|---|
| `tools-bom` | | versions of all modules | | now |
| `tools-settings` | `com.ash.tools.settings` | `Placeholders`, `SettingsFile`, `SettingSpec`, `Secrets`, `PathAnchors` | **JDK only** | now |
| `tools-net` | `com.ash.tools.net` | the TLS and proxy classes of 1.1, `TlsHttp` | **JDK only**; `tools-settings` | now |
| `tools-concurrent` | `com.ash.tools.concurrent` | `SingleFlight` | **JDK only** | now |
| `tools-time` | `com.ash.tools.time` | `LoadGuard` (the date window and drop-share guard); later calendars and "today in a zone" | **JDK only** | now |
| `tools-xlsx` | `com.ash.tools.xlsx` | the one workbook writer (section 3.1) | **JDK only**; POI in test scope | now |
| `tools-testkit` | `com.ash.tools.testkit` | `FakeProxy`, `TlsFixture` | JDK; JUnit API | now |
| `tools-guards` | (tests only, not published) | section 5 | JUnit | now |
| `tools-codec` | `com.ash.tools.codec` | `Hashes`, `CanonicalJson`, `DeterministicZip`, `Ed25519` | JDK only | i8 |
| `tools-csv`, `tools-xlsx-read`, `tools-retry`, `tools-files` | | section 6 | per module | i8 |

Rules of the build: no module depends on another unless the table says so; nothing in `src/main` names a product (no "drishti",
"lake", "rachana": property names, environment names and home folders are **parameters** the consumer passes, which is exactly
where today's copies differ); no global state except what `SettingsFile` already holds by design.

## 3. APIs

Signatures are today's, kept, so adoption is a change of import. Changes are marked **new**.

### 3.1 `tools-xlsx` (first)

One streaming writer, the playground writer's shape, which already covers the other three:

```java
package com.ash.tools.xlsx;

public final class Workbook implements AutoCloseable {
    public static Workbook to(OutputStream out);                    // one pass, constant memory per sheet
    public static Workbook to(OutputStream out, Styles styles);
    public Sheet sheet(String name);                                // the name made valid and unique
    public void name(String name, String sheet, String ref);       // a defined name
    public void close();                                            // writes styles, workbook, content types
}
public final class Sheet {
    public Sheet freeze(int rows, int cols);
    public Sheet width(int col, double w);
    public Sheet widths(int from, int to, double w);
    public Sheet merge(String range);
    public Sheet list(String sqref, List<String> options);          // a drop-down
    public Sheet checks(String sqref, String pass);                 // conditional format
    public Sheet print(PrintSetup setup);
    public Sheet row(int r);
    public Sheet text(int col, String s, Style style);              // always text, never a formula
    public Sheet number(int col, double v, Style style);
    public Sheet date(int col, LocalDate d, Style style);
    public Sheet bool(int col, boolean v, Style style);
    public Sheet formula(int col, String f, Double cached, Style style);
    public Sheet formulaText(int col, String f, String cached, Style style);
    public Sheet chart(ChartSpec chart);                            // a native chart beside the data
    public long cells();
}
public record PrintSetup(boolean landscape, int paperSize, double[] margins, String header, String footer) {}
public record ChartSpec(Kind kind, String title, String categories, List<String> series, boolean dateCategories) {
    public enum Kind { LINE, BAR, COLUMN, AREA, PIE, SCATTER }
}
public final class Styles { public static Styles standard(); public Style named(String name); public Styles with(String name, Style s); }
public final class Cells {                                          // the helpers four files repeat today
    public static String col(int index); public static String ref(int col, int row);
    public static String num(double v);  public static String esc(String s); public static String quoteSheet(String sheet);
}
public final class Formulas {
    public static List<String> functions(String formula);
    public static String check(String formula);                     // null, or why it is refused
    public static List<String> scan(InputStream xlsx);              // every formula in a workbook
}
```

What stays in Drishti: `ExportDoc` and the mapping of an export document onto `Workbook` (an adapter of about 120 lines);
`PlaygroundTemplate`, `SeriesLayout` and the playground books (domain); the capacity workbook's layout. Proof of the module:
every feature written, then **read back with Apache POI** (test scope) and compared cell by cell, formulas evaluated by POI
equal to the cached values; byte-identical output for the same input twice (fixed entry times); a 1,000,000-cell stream under
a fixed heap.

### 3.2 `tools-settings`

```java
public final class Placeholders {
    public static String resolve(String text);
    public static String resolve(String text, Function<String,String> more);
    public static String resolveOr(String text, Function<String,String> view, String missing);
    public static String require(String text, Function<String,String> more);
    public static String lookup(String key, Function<String,String> view);
    public static boolean has(String text);
}
public final class SettingsFile {
    public static List<String> applyFrom(String systemProperty);    // new: the property name is the caller's
    public static List<String> files(String named);
    public static List<String> filesRead();
    public static String sourceOf(String key);
    public static Map<String,String> sources();
    public static boolean isSecret(String key);
    public static String shown(String key, String value);           // masked when secret
    public static Map<String,String> read(String path);             // relative path refused
    public static String envName(String key);
}
public final class PathAnchors {                                    // new name; the shared half of Roots / LakeRoots / ServerConfig
    public static boolean isAbsolute(String value);                 // Windows drive and UNC rules as plain string work
    public static Path anchored(String value, Map<String,Path> roots);
    public static String anchorValue(String key, String value, Map<String,Path> roots, List<String> problems);  // bare relative refused
}
public record SettingSpec(String name, String type, boolean required, String defaultValue, String description, boolean secret, String group) {}
```

`Roots` (Drishti) and `LakeRoots` (Lake) stay in their products as the lists of **named** roots, each a thin class over `PathAnchors`.

### 3.3 `tools-net`, `tools-concurrent`, `tools-time`, `tools-testkit`

```java
// com.ash.tools.net: as today
public record TlsSettings(...) { static TlsSettings disabled(); static TlsSettings parse(Map<String,String> s, String prefix);
                                 static TlsSettings parse(Map<String,String> s, String prefix, Function<String,String> env);
                                 static List<SettingSpec> settingSpecs(String prefix); static boolean anyGiven(Map<String,String> s, String prefix); }
public final class TlsContexts { static TlsMaterial build(TlsSettings s); static TlsMaterial build(TlsSettings s, Function<String,String> env, Clock clock); }
public final class TlsHttp     { static TlsMaterial material(Map<String,String> settings, String url, String urlSetting);
                                 static HttpClient.Builder apply(HttpClient.Builder b, TlsMaterial m); }
public final class ProxySettings { static ProxySettings none(); static ProxySettings resolve(Map<String,String> settings, String targetUrl);
                                 static ProxySettings resolve(Map<String,String> settings, String targetUrl, Function<String,String> env);
                                 boolean active(); String host(); int port(); NoProxy noProxy(); URI uri(); boolean bypasses(String host);
                                 Map<String,Object> describe(); String annotate(String health, String targetUrl); /* ... */ }
public final class SiteProxy   { static void configure(Map<String,String> settings, boolean fromEnvironment); static void reset(); }

// com.ash.tools.concurrent
public final class SingleFlight<K> { public <V> V get(K key, Function<K,V> peek, Function<K,V> load, BiConsumer<K,V> store); }

// com.ash.tools.time
public final class LoadGuard { public LoadGuard(ZoneId zone, Clock clock, LocalDate asOf, int futureDays, double maxDropShare, boolean forceDrop);
                               public static LoadGuard fromArgs(String[] args, Map<String,String> env, String envPrefix, Clock clock);   // prefix: new
                               public boolean accept(LocalDate day, String what); public void checkDrop(String what, long dropping, long total, String unit);
                               public LocalDate keepFromDays(int n); public LocalDate keepFromMonths(int n); public void finish(); /* ... */ }
```

The Lake copies are the source (already free of Drishti's names and JDK-only); Drishti's `TlsHttp` is added.

## 4. Adoption by milestone

| When | Where | What | Removed |
|---|---|---|---|
| **Now, before the G3 tags** | `drishti-tools` | T0 to T5 (section 7): repository, guards, BOM, the five modules and the test kit, tag `v1.0.0`, installed in `~/.m2` | |
| **G3** | **Lake** | pin `tools-bom`; `lake-api` uses `tools-settings` (JDK only, so `lake-api` stays free of third parties); `lake-io` uses `tools-net`, `tools-concurrent`, `tools-time`; `lake-tck` uses `tools-testkit`; `api-surface.txt` regenerated **before** `v1.0.0` | 19 files, about **2,900 lines**: `io/net/*` (13 files, 1,902), `Placeholders`, `SettingsFile`, `SingleFlight`, `LoadGuard` (614), `FakeProxy`, `TlsFixture` (420); `LakeRoots` shrinks onto `PathAnchors` |
| **G3** | **Rachana** | **nothing** (TL-7). Core never; server and tools not before their tags | 0 |
| **G4** | **Drishti** | pin `tools-bom` (one tools version on the class path: Drishti's, never below Lake's; guard). `api.tls`, `api.proxy`, `SettingSpec`, `settingsfile.{Placeholders,SettingsFile}`, `SingleFlight`, `LoadGuard`, the two fixtures replaced by imports (about 85 main files, mechanical); the **four xlsx writers** replaced by `tools-xlsx` | about **4,700 lines**: TLS and proxy 2,084, settings 345, single-flight and guard 275, fixtures 420, xlsx 1,580 less the adapter; `Roots` shrinks |
| **Combined i8** | all | section 6, each when its second consumer arrives: `tools-codec` with Drishti Risk's recorder (R1); Rachana's server when the live tables give it TLS and exports (H2); `tools-xlsx` is what the Risk playgrounds use from the first day (RK-16) | the 29 hash helpers, the 5 canonical-JSON sites, `CsvWriter`/`CsvReader`, `RetryPolicy`, both `TempFiles` |

G4 already rewrites these imports in Drishti: the lake modules that carry half of the TLS, proxy and placeholder users leave in
the same milestone, and the one drill covers the result. xlsx in G4 is proven by **the same workbooks before and after**: every
export and playground book written from the frozen inputs on i7a code and on the migrated code, compared cell by cell and formula
by formula (POI as the reader), added to the migration evidence.

**If the tools tag is late** for Lake's G3 gate: Lake tags with its copies, and the two settings classes stay in Lake's surface
as deprecated delegates from the next minor release. Workable, but it is the permanent residue item 0.3 is meant to avoid.

## 5. Guards and tests

In `tools-guards`, run on every build (the pattern of `lake-guards`):

| Guard | Rule |
|---|---|
| `ApiSurfaceTest` | `java/api-surface.txt` generated from the jars; a release may only **add** (so the newest tools version always serves every consumer); a removal needs a major version |
| `DomainWordsTest` | no identifier, string or comment in `src/main` contains, as a whole word, a word of `domain-words.txt` (trade, trader, counterparty, portfolio, VaR, exposure, netting, collateral, desk, Endur, sutra, ...); only unambiguous words (not "book", "limit", "position", "pack", which plain utilities use); the list only grows |
| `NoProductNameTest` | no "drishti", "rachana", "com.ash.lake", `DRISHTI_`, `LAKE_` in `src/main` |
| `DependenciesTest` | each module's compile dependencies equal its line in `dependencies.txt`; the modules marked JDK-only have none |
| `ModuleRulesTest` | module-to-module dependencies only as section 2 states; no cycles; no `internal` package used from another module |
| `LicenceHeaderAndLineCapTest` | the header on every file; 2,500 lines at most (one shared constant) |
| `ExemptionsOnlyShrinkTest` | any exemption list may only get shorter |

In each consumer: **no second copy** (no class named like a tools class; Lake and Drishti already have this guard for moved
classes); **the pin** (the version is a tag; jars in `~/.m2` equal the release's `MANIFEST.sha384`); Rachana's existing rule that
`rachana-core` has no dependency gains one line naming `com.ash.tools`. Tests: each module's tests move with its code (Lake's are
the source); every race fix keeps its multi-threaded test; TLS and proxy tests use the test kit's fixtures, no outside network,
no native binary (nothing here needs an .exe on Windows).

**The pin (R-1, as Rachana and LK-15).** `tools/release` builds from a tag, writes the manifest, installs the jars into `~/.m2`;
never a branch or a snapshot. Build order becomes tools, then Lake and Rachana, then Drishti; the pre-drill check verifies the
three pins before anything is compiled.

## 6. Feature-rich extras (combined i8)

Each is taken when a second consumer exists, with the standard library where there is one:

| Module | Contents | Library decision |
|---|---|---|
| `tools-codec` | `Hashes` (SHA-256/384 hex of text, bytes, file, stream), `CanonicalJson`, `DeterministicZip`, `Ed25519` | JDK (`MessageDigest`, `Signature`, `java.util.zip`). Existing identities must not change: fixtures of today's bytes first. Rachana core's own three stay |
| `tools-net` 1.1 | **Windows-ROOT**: `truststore-type: windows-root` (the JDK's SunMSCAPI key store, no .exe), merged with the JVM default | JDK |
| `tools-xlsx-read` | read sheets, cells, formulas | **Apache POI**, thin wrapper, own artifact so only readers pay for it |
| `tools-csv` | RFC 4180 read and write, streaming | **Apache Commons CSV** wrapped, or the 192 lines we have: TL-10 |
| `tools-retry` | retry with backoff, rate limit (`RetryPolicy` 162, `RateLimits` 72, `AskRateLimiter` 61) | JDK |
| `tools-files` | temp files with guaranteed clean-up (the two `TempFiles`, reconciled), Windows-safe process start | JDK |
| `tools-time` 1.1 | business calendars, "today in a zone" (`BusinessCalendar` 218) | JDK `java.time` |
| not modules | YAML (Jackson/SnakeYAML are already the standard: no wrapper of a wrapper), logging context (SLF4J MDC), archives (Lake's), CLI parsing until two programs share one | |

## 7. Estimate

Builder-days; the model per lane follows the token economy (opus deep coding, sonnet simpler coding, haiku repetitive).

| Step | Work | bd | Model |
|---|---|---|---|
| T0 | Repository layout, parent POM, `tools-bom`, the seven guards, `tools/release`, README | 1.5 | sonnet |
| T1 | `tools-settings` from Lake's copies; names as parameters; `PathAnchors` from the three anchoring rules; tests | 1.5 | sonnet |
| T2 | `tools-net` (+ `TlsHttp`) and `tools-testkit`; tests | 1.5 | sonnet |
| T3 | `tools-concurrent`, `tools-time` | 0.5 | haiku |
| T4 | `tools-xlsx`: the four writers folded into one streaming writer, charts, formulas, POI read-back tests, large-stream test | 5.0 | opus |
| T5 | API surface frozen, tag `v1.0.0`, manifest, install | 0.5 | sonnet |
| | **Now, tools repository** | **10.5** | |
| L1 | Lake adopts (19 files deleted, imports, surface regenerated, guards, kit green twice) | 1.5 | sonnet |
| D1 | G4: Drishti adopts settings, net, concurrent, time, test kit (about 85 files; no-second-copy and pin guards) | 2.0 | haiku, sonnet review |
| D2 | G4: Drishti adopts `tools-xlsx` (19 callers, the `ExportDoc` adapter, before-and-after workbook evidence) | 3.0 | opus |
| | **G4, Drishti** | **5.0** | |
| X1..X6 | i8 extras: codec 3, Windows-ROOT 1, xlsx read 2, CSV 1.5, retry + files + time 3, Rachana server adoption 1 | 11.5 | sonnet (codec: opus) |

T1 to T3 run in parallel after T0 (three builders at most); T4 is independent of all of them and of Lake. T5 needs T1 to T3,
not T4: if xlsx is not finished when Lake needs its pin, tag `v1.0.0` without it and `v1.1.0` with it (additive).

## 8. Risks

| Risk | Answer |
|---|---|
| Churn in Lake before its tag | The change is deletion and imports of code that differs by 2 to 29 lines; Lake's kit must be green twice after it; the fallback of section 4 if it is not |
| Two tools versions meet in Drishti (Lake pinned to 1.0, Drishti on 1.1) | tools is additive-only (`ApiSurfaceTest`); Drishti's BOM import comes first; a guard fails the build if Drishti's version is below Lake's |
| A fourth pinned repository slows every build | tools changes rarely; one command installs a tag; the pre-drill check names a missing pin |
| "Tools" becomes a dumping ground | each module needs two consumers or the owner's word; the domain-word guard; one module, one job |
| Own xlsx writer drifts from the standard | POI reads every test workbook; a real Excel and LibreOffice opening check stays in the release checklist |

## 9. Decisions TL-1 to TL-14

| # | Question | Recommendation |
|---|---|---|
| TL-1 | Repository and coordinates | **`drishti-tools`, group `com.ash.tools`, packages `com.ash.tools.<module>`, one jar per module, `tools-bom`, JDK 21** |
| TL-2 | xlsx writing: Apache POI or our own | **Our own, one JDK-only module** (about 1,300 lines after folding four writers); POI costs about a dozen jars and 15 to 20 MB in every consumer for features we do not use. **POI is the test oracle.** A stated exception to "standard library first", because no official library exists and the standard is proven by the oracle |
| TL-3 | xlsx reading | **Wrap POI**, in `tools-xlsx-read`, in i8; never our own parser |
| TL-4 | Create the repository now or at the start of combined i8 | **Now**: Lake's public surface freezes at its G3 tag with two of the duplicated classes in it |
| TL-5 | Modules in 1.0.0 | **settings, net, concurrent, time, xlsx, testkit**; nothing else |
| TL-6 | Lake adopts in G3 | **Yes**, 1.5 bd, before `v1.0.0`; fallback in section 4 |
| TL-7 | Rachana's server and tools adopt in G3 | **No.** About 120 shareable lines against the first outside dependency of a dependency-free server, days before its tag. Revisit in i8 (H2). Core: never |
| TL-8 | Drishti adopts in G4 | **Yes**, 5 bd, inside the one drill; the four xlsx writers become one |
| TL-9 | `Sha384`, `Rlib`, `ViewJson` in Rachana core | **Stay**; `tools-codec` wraps the JDK for everyone else. No test may require them to be one code |
| TL-10 | CSV: Commons CSV or our 192 lines | **Commons CSV wrapped** (one small jar, the RFC's reference behaviour), decided finally at i8 with a jar-size check |
| TL-11 | Windows-ROOT trust | **New feature of `tools-net` 1.1 in i8**, not in 1.0.0 (no new behaviour before the G3 tags) |
| TL-12 | Versions across consumers | **Additive-only API; the highest pinned version wins; guard in Drishti** |
| TL-13 | Where `LoadGuard` lives | **`tools-time`**: nine connector plug-ins of Drishti need it and must not pull `lake-io` for it |
| TL-14 | Entry rule for a new module | **Two consumers, or the owner's word; domain-free; the standard library first, a thin wrapper second, own code last and with an oracle test** |

## 10. Decisions taken (lead, under the owner's delegation, 2026-11-21)

TL-1 to TL-14 are **accepted as recommended**. TL-2 is a stated exception to "standard library first": our own JDK-only xlsx writer (one writer replacing Drishti's four), with Apache POI as the test-scope oracle that proves every workbook opens and reads back identically; a reader (TL-3) wraps POI in its own artifact later. Repository `github.com/ajsinha/drishti-tools` (private) created 2026-11-21. Owner objective governing it: reduce duplicates, fix bugs in one place, less code, modular and reusable.
