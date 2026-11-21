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
# The Drishti engine specification, `engine: 1`

**Status: draft 1 (2026-11-21). Nothing in it is built.** Normative once the owner approves it and the first kit is cut. The
design it is made from is [../plans/ENGINE_INTEGRATION_DESIGN.md](../plans/ENGINE_INTEGRATION_DESIGN.md); where the two differ,
this text wins. It moves to the `drishti-engine` repository (`spec/1/`) when that repository exists, with the machine-readable
files beside it.

| File | Normative for | State |
|---|---|---|
| this text | meaning: the model, obligations, errors, versions | draft 1 |
| `descriptor.schema.json` (section 6.3) | the descriptor's structure | draft, printed here |
| `config.schema.json` (section 9.2) | a host's engine file | draft, printed here |
| `case.schema.json` (section 12.2) | kit cases | draft, summarised here |
| `engine-api` sources | the Java signatures of sections 4 and 5 | to be generated into this text from the sources once they exist |
| `tck/laws/` | the laws of section 12.4 as executable cases | to be written |

## Contents

0. [Conventions](#0-conventions) · 1. [Scope](#1-scope) · 2. [Model](#2-model) · 3. [Types and values](#3-types-and-values) ·
4. [Interfaces an engine implements](#4-interfaces-an-engine-implements) · 5. [Interfaces a host provides](#5-interfaces-a-host-provides) ·
6. [The descriptor](#6-the-descriptor) · 7. [Lifecycle](#7-lifecycle) · 8. [Obligations](#8-obligations) ·
9. [The host's engine file](#9-the-hosts-engine-file) · 10. [Packs and binding](#10-packs-and-binding) ·
11. [Errors](#11-errors) · 12. [The kit](#12-the-kit) · 13. [Versioning](#13-versioning) ·
14. [The process binding](#14-the-process-binding) · 15. [Generated artefacts](#15-generated-artefacts) ·
16. [A worked example](#16-a-worked-example)

## 0. Conventions

- **MUST**, **MUST NOT**, **SHOULD**, **MAY** are used as in RFC 2119.
- An **engine** is an implementation of section 4 with a descriptor (section 6). A **host** is a program that loads engines
  and provides section 5. Drishti is the first host; `engine-cli` and the kit's harness are the second and third.
- **[K]** marks a rule the kit (section 12) checks. Where this text and the kit disagree, that is a defect in one of them and
  a release blocker; until it is fixed, **the kit wins**.
- Names: an engine id, input, output, parameter and option name is lower-case letters, digits and `-`, starting with a
  letter, at most 64 characters. A column name is as the contract writes it (Drishti's are `UPPER_SNAKE`).
- All text is UTF-8. All paths an engine is given are absolute.

## 1. Scope

1. This specification defines how a **compute engine** is described, implemented, run and proven. It does not define any
   measure, contract, screen or domain. No word of any domain (scope, desk, currency, cohort) appears in the API.
2. A component is an engine only if it is **pure** (its answer depends on declared inputs, parameters and options, and it
   writes nothing but its answer), **deterministic** (section 8.1), **closed** (section 8.4) and **explained** (section 8.3).
   A loader, a feed, a write-back, an approval flow and a general query language are not engines and MUST NOT be offered
   through this API.
3. One model serves every way of running. An engine MUST NOT behave differently because a request came from a person, a
   schedule, a data change or a stream; only the host knows, through `Request.requestClass` for admission (section 3.4).
4. A host MUST be able to run a conforming engine from its descriptor and one configuration file, with no code written for
   that engine.

## 2. Model

```
contracts ──▶ Inputs ──▶ ┌────────────── Engine (one per instance) ──────────────┐
                         │  Session (one per snapshot; the loaded state)          │ ──▶ Answer: tables + stamp
params, visibility ────▶ │     ask(request)  explain(cell)  workbook(request)     │ ──▶ Explanation, workbook
changes ───────────────▶ │     apply(changes)   partial(shard)                    │ ──▶ Partial ──▶ assemble
                         └────────────────────────────────────────────────────────┘
```

| Word | Meaning |
|---|---|
| **descriptor** | the engine's declaration (section 6): the single source for binding, checks, generation and the kit |
| **contract** | a table's definition in the Lake contract schema, named `<owner>/<name>@<major>` |
| **input** | a named table the engine reads, of one contract |
| **snapshot** | the values of the dimensions the descriptor declares (`{ date: 2026-01-13 }`, `{ cohort: C1 }`, or none), fixing which rows of each input are meant |
| **session** | the engine's loaded state for one snapshot; opened and closed by the host; answers any number of requests |
| **request** | which outputs, with which parameters, for which visibility |
| **output** | a named table the engine answers with; its schema is in the descriptor |
| **measure** | an output column marked `measure: true`: a figure that MUST be explainable |
| **visibility** | the rows of each input a request may be computed from, decided by the host |
| **stamp** | the identity of an answer: engine, version, descriptor, options, input stamps, request, seed |
| **option** | a deployment setting, fixed for an engine instance |
| **parameter** | a setting of one request |

## 3. Types and values

### 3.1 Column types

The types of the Rachana Data Protocol v1 (PROTOCOL 3.1), with the Java carrier an engine sees.

| Type | Carrier in a batch or table | Compared by the kit as |
|---|---|---|
| `string` | `String[]` | text, by code point |
| `integer` | `long[]` | value |
| `number` | `double[]` (a null is `NaN` together with the null flag) | **raw bits** (`Double.doubleToRawLongBits`), unless the column declares a tolerance (section 8.1) |
| `decimal` | `String[]` of plain decimal text (`"1250.50"`), scale as declared | text |
| `boolean` | `boolean[]` | value |
| `date`, `timestamp`, `time` | `String[]` in the ISO 8601 forms of PROTOCOL 3.1 | text |
| `list` of `number` (a vector) | through the vector sink (input) or `double[][]` (output) | raw bits per element |

An engine MUST NOT put `NaN` or an infinity in a non-null `number` cell of an output. [K]

### 3.2 Parameter and option types

`text`, `int`, `number`, `decimal`, `bool`, `date`, `enum` (with `options`), `list` (of one of the former, with `items`), and
`records` (a small table: a list of mappings whose `fields` are declared). Each MAY declare `default`, `required`, `min`,
`max`, `allowed`, `label`, `help`, `unit`. The host normalises a request's parameters before the engine sees them: defaults
filled, types converted, unknown names refused (`ENG-3001`), values outside what is allowed refused (`ENG-3002`). An engine
therefore receives only declared names with values of the declared types, sorted by name. An engine MUST NOT read a parameter
it does not declare for the output asked. [K]

### 3.3 Value records (`com.ash.engine`)

```java
/** The dimensions of the descriptor's snapshot block, each as canonical text. Empty when the descriptor declares none. */
public record Snapshot(SortedMap<String, String> dimensions) {}

/** A row filter over an input's scope columns. The whole tree is data: no code. */
public sealed interface Filter {
    Filter ALL = new All();
    record All() implements Filter {}
    record Eq(String column, String value) implements Filter {}
    record In(String column, Set<String> values) implements Filter {}
    record Range(String column, String fromInclusive, String toInclusive) implements Filter {}   // either bound may be null
    record And(List<Filter> all) implements Filter {}
    record Or(List<Filter> any) implements Filter {}
    record Not(Filter filter) implements Filter {}
}

/**
 * What a request may be computed from. signature: equal for two visibilities exactly when they are the same rule
 * (an engine keys any per-viewer cache by it). rows: input name to filter, for every input that declares scope columns.
 * masked: columns of outputs the host will mask (an engine MAY skip computing them).
 */
public record Visibility(String signature, Map<String, Filter> rows, Set<String> masked) {}

public enum RequestClass { INTERACTIVE, EVENT, BATCH }

/** id: the correlation id. outputs: the outputs asked, in order. params: normalised (3.2). actor: for the audit line only. */
public record Request(String id, List<String> outputs, SortedMap<String, Object> params, Visibility visibility,
        String actor, long seed, RequestClass requestClass) {}

public enum Status { COMPLETE, PARTIAL, NOT_FOUND }

/** An input, or columns of it, that were not there; outputs: the outputs that could not be answered for it. */
public record Missing(String input, String contract, Snapshot snapshot, List<String> columns, List<String> outputs) {
    public String words();        // "not found: risk/scenario-pv@1 for date 2026-01-13; missing columns: DESK"
}

/** tables: one per output answered, in the order asked. COMPLETE: missing is empty. NOT_FOUND: tables is empty. */
public record Answer(Status status, List<Table> tables, List<Missing> missing, Stamp stamp, List<String> notes) {}

public record Stamp(String engine, String version, String descriptor, String options, SortedMap<String, String> inputs,
        String request, long seed) {
    public String text();         // one line, stable: the cache key and the provenance value
}

/** One measure cell: the output, the measure column, and the row's key values. */
public record Cell(String output, String column, SortedMap<String, String> key) {}

/** steps: in order; the last step's value is the cell's value. */
public record Explanation(Cell cell, Object value, List<Step> steps) {}

/**
 * formula: Rachana-EL over `given` and earlier steps by name (`step.<name>`); null when opaque.
 * given: named values (numbers, text, lists, lists of records). opaque: the step is not expressible in EL;
 * then `why` says so and `cell` names the workbook cell that holds it.
 */
public record Step(String name, String label, String formula, SortedMap<String, Object> given, Object value,
        boolean opaque, String why, String cell) {}

/** The rows of one input that changed since the session's stamp. upserts: whole rows. */
public record Changes(String input, Batch upserts, List<String> deletedKeys, String stamp) {}

/** One part of a snapshot: the shard unit column's values it holds, as a filter. */
public record Shard(String id, Filter rows) {}

/** A partial answer: tables of additive aggregates in the engine's own layout. Serialisable (section 14.2). */
public record Partial(String shard, List<Table> aggregates, Stamp stamp) {}
```

### 3.4 Tables

```java
public record ColumnSchema(String name, String type, boolean nullable, boolean measure, String unit, String format,
        String exact) {}                       // exact: "bits", "relative:1e-12", "text" (decimal)
public record TableSchema(String name, List<ColumnSchema> columns, List<String> key, List<String> order) {}

/** Immutable, columnar. An accessor for another type than the column's is an IllegalArgumentException. */
public interface Table {
    TableSchema schema();
    int rows();
    String[] texts(String column);
    long[] integers(String column);
    double[] numbers(String column);
    boolean[] flags(String column);
    double[][] lists(String column);
    boolean isNull(String column, int row);
}

public final class Tables {
    public static Builder of(TableSchema schema);
    public interface Builder {
        Builder row(Object... valuesInColumnOrder);
        Builder columns(Map<String, Object> arraysByColumn);     // whole columns at once; arrays are taken, not copied
        Table build();                                            // sorts by `order`, checks key uniqueness and types
    }
}
```

A returned table MUST match the output's declared schema exactly: column names, order, types, nullability; key values unique;
rows in the declared `order`. [K] A host MAY read the arrays without copying; an engine MUST NOT change them after `build()`.

## 4. Interfaces an engine implements

Package `com.ash.engine`, artifact `com.ash.engine:engine-api`, which depends on the JDK only.

### 4.1 `Engine` (required)

```java
public interface Engine extends AutoCloseable {
    /** The descriptor, read from the jar's own file. MUST equal that file. */
    Descriptor descriptor();

    /** Called once. Options are already valid against the descriptor. Refuse with EngineException (the reason is shown). */
    void open(EngineContext context) throws EngineException;

    /**
     * Opens the loaded state for one snapshot. The engine reads what it keeps from `inputs` now or lazily at the first ask
     * (either is allowed; memory MUST be reserved before it is taken, section 8.5). `inputs` stays valid until the session
     * is closed. Absent required inputs do not fail the open: they make answers NOT_FOUND (section 8.2).
     */
    Session session(Snapshot snapshot, Inputs inputs, Control control) throws EngineException;

    /** Releases everything. Sessions are already closed. */
    @Override void close();
}
```

The class named by the descriptor's `entry` MUST be public with a public constructor without parameters, which MUST do no
work. A host creates one object per engine instance (per tenant, per test) and MAY create several in one JVM. [K]

### 4.2 `Session` (required)

```java
public interface Session extends AutoCloseable {
    Snapshot snapshot();

    /** Bytes held now: heap, direct and mapped. Cheap; called often. */
    long memoryBytes();

    /** Thread-safe: called for many requests at once. Never returns null. */
    Answer ask(Request request, Control control) throws EngineException;

    /** Idempotent. After it, memoryBytes() is 0 and no thread of the host's executor still runs this session's work. */
    @Override void close();
}
```

### 4.3 Capability interfaces

An engine's `Session` (or, for `Assembling`, its `Engine`) implements an interface exactly when the descriptor lists the
capability. [K]

```java
/** Capability `explain` (and `workbook`). Required when any output has a measure column. Implemented by the Session. */
public interface Explaining {
    Explanation explain(Request request, Cell cell, Control control) throws EngineException;

    /** Writes one .xlsx for the request's outputs (section 8.3). The stream is the host's; do not close it. */
    void workbook(Request request, OutputStream out, Control control) throws EngineException, IOException;
}

/** Capability `incremental`. Implemented by the Session. */
public interface Incremental {
    /**
     * Applies the changes atomically with respect to ask: a request sees the state before or after, never between.
     * Called by one thread at a time. false: the engine cannot apply these; the host reopens the session.
     */
    boolean apply(List<Changes> changes, Control control) throws EngineException;

    /** The keys of `output` an applied change may have touched, or empty for "cannot say" (the host re-asks all subscribed). */
    default Optional<Set<String>> affected(String output, List<Changes> changes) { return Optional.empty(); }
}

/** Capability `partition`. Implemented by the Session. */
public interface Partitioned {
    Partial partial(Request request, Shard shard, Control control) throws EngineException;
}

/** Capability `partition`. Implemented by the Engine: needs no session. */
public interface Assembling {
    Answer assemble(Request request, List<Partial> partials, Control control) throws EngineException;
}
```

The capability `process` needs no interface (section 14). The capability `supplier` needs none either: it states that the
engine's outputs may be bound to another engine's `uses` port.

## 5. Interfaces a host provides

### 5.1 `EngineContext`

```java
public interface EngineContext {
    Options options();                          // the engine's options: typed getters by declared name
    Path workDir();                             // absolute, this instance's own, empty at open; the only place it may write
    Clock clock();                              // business time as the host defines it; the only clock an engine may read
    ExecutorService executor();                 // bounded to limits.threads; the only threads an engine may use
    MemoryBudget memory();
    Audit audit();                              // audit.note(requestId, words): beside the host's own line per request
    Log log();                                  // debug, info, warn; never row values at info or above
    Optional<Supplier> supplier(String port);   // a `uses` port; empty when the site bound none
}

public interface MemoryBudget {
    /** Reserves before allocating. Throws EngineException ENG-5002 (in words) when the budget cannot give it. */
    Reservation reserve(long bytes, String what) throws EngineException;
    interface Reservation extends AutoCloseable { void resize(long bytes) throws EngineException; @Override void close(); }
}

/** Another engine's output, asked with the current request's visibility. */
public interface Supplier {
    Answer ask(String output, SortedMap<String, Object> params, Request onBehalfOf, Control control) throws EngineException;
}
```

### 5.2 `Inputs`

```java
public interface Inputs {
    Set<String> names();                        // exactly the descriptor's inputs
    InputTable table(String name);              // an undeclared name: IllegalArgumentException
}

public interface InputTable {
    String name();
    boolean present();                          // false: the table is not there for this snapshot
    Set<String> columns();                      // the declared columns that are there
    String stamp();                             // changes when and only when the rows for this snapshot change
    long rows();                                // -1 when not known without reading

    /** Delivers batches on the calling thread. where: over scope columns; the host applies it exactly, or the engine does. */
    void scan(Scan scan, BatchSink sink) throws EngineException;

    /** One vector column with some text columns, a row at a time, in a stable order. */
    void vectors(VectorScan scan, VectorSink sink) throws EngineException;
}

public record Scan(List<String> columns, Filter where, int batchRows) {}
public record VectorScan(String column, List<String> texts, Filter where) {}

/** The arrays belong to the host and are reused for the next batch: copy what is kept. */
public interface Batch {
    int rows();
    String[] texts(String column);
    long[] integers(String column);
    double[] numbers(String column);
    boolean[] flags(String column);
    boolean isNull(String column, int row);
}
@FunctionalInterface public interface BatchSink { void batch(Batch batch) throws EngineException; }

/** values[0..length) is the row's vector (length -1: null). values and texts are reused for the next row. */
@FunctionalInterface public interface VectorSink { void row(String id, double[] values, int length, String[] texts); }
```

1. A host MUST deliver only declared columns; asking for another is `ENG-2003`. [K]
2. A host MUST apply the site's column map and row filter (section 9) before delivery: an engine sees the contract's names.
3. A host MAY deliver rows in any order and any batch size unless the input declares `ordered-by`. An engine's answer MUST
   NOT depend on either (section 8.1). [K]
4. In `request` mode the host has already applied the viewer's row rules and masks; `Visibility.rows` for that input is
   `Filter.ALL`.

### 5.3 `Control`

```java
public interface Control {
    boolean cancelled();
    void check() throws EngineException;        // throws ENG-5010 (cancelled) or ENG-5011 (deadline passed)
    Instant deadline();
    void progress(double fraction, String words);
}
```

An engine MUST call `check()` at least once per batch read and at least once per declared `cancel-latency` of work. [K]

## 6. The descriptor

### 6.1 Place and form

One YAML 1.2 document, read strictly (a repeated key, a tag or an unknown key is an error, `ENG-1001`), in the jar at
`META-INF/drishti-engine/<id>.engine.yaml`. A jar MAY hold several engines. The first key is `engine: 1`.

### 6.2 Keys

| Key | Required | Meaning |
|---|---|---|
| `engine` | yes | the descriptor format: `1` |
| `id`, `version`, `title`, `about` | yes | identity; `version` is semantic |
| `publisher` | yes | `{ id, name }` |
| `api` | yes | the range of `engine-api` it was built for: `">=1.0 <2"` |
| `entry` | yes | the class implementing `Engine` |
| `codes` | | the prefix of its own error codes (`RSK`); section 11.3 |
| `licence` | | `{ feature }`: a host's licence feature name |
| `snapshot` | | `dimensions: [ { name, type, label } ]`; absent: none |
| `inputs` | yes | map of name to input (6.2.1); MAY be empty only for an engine with supplier ports |
| `options` | | map of name to a typed declaration (3.2) |
| `params` | | map of name to a typed declaration (3.2), shared by outputs |
| `outputs` | yes | map of name to output (6.2.2); at least one |
| `capabilities` | | list of `explain`, `workbook`, `incremental`, `partition`, `process`, `supplier` |
| `partition` | with the capability | `{ input, unit }`: the column whose values a shard is made of |
| `uses` | | map of port name to `{ contract, about }`: an output of another engine it may ask |
| `limits` | | `{ memory: { fixed-bytes, per-row-bytes: { <input>: n } }, cancel-latency: 1s }` |
| `aliases` | | map of an older configuration key to an option name (a host keeps old keys working) |
| `ui` | | hints for generation only (15): `{ group, words: [..], icon }`; never read at run time |

#### 6.2.1 An input

| Key | Required | Meaning |
|---|---|---|
| `contract` | yes | `<owner>/<name>@<major>` |
| `about` | yes | words |
| `required` | | default `true` |
| `mode` | | `session` (default) or `request` (5.2 rule 4) |
| `key` | | the contract's key columns the engine relies on |
| `columns` | yes | the columns read. **Only these are delivered** |
| `vector` | | the vector column, if any |
| `scope-columns` | | the columns a visibility filter may name; MUST be within `columns` |
| `changes` | | `none` (default) or `rows`: `apply` accepts changes of this input |
| `ordered-by` | | columns the host MUST deliver in order; without it order is free |
| `per` | | the snapshot dimensions that select its rows (default: all declared); `[]`: the same rows for every snapshot |

#### 6.2.2 An output

| Key | Required | Meaning |
|---|---|---|
| `title`, `about` | yes | words |
| `shape` | | `table` (default), `row` (exactly one row), `series` (a table ordered along `axis`) |
| `params` | | the parameter names it accepts (default: none) |
| `needs` | | the inputs it cannot be answered without (default: all required inputs) |
| `key` | for `table`, `series` | key columns |
| `order` | | columns the rows are sorted by (default: the key) |
| `axis` | for `series` | the ordering column |
| `columns` | yes | list of `{ name, type, nullable, label, unit, format, measure, exact, passes }` |
| `paged` | | `true`: large; a host keeps the answer under its stamp and pages it |
| `stochastic` | | `true`: depends on `Request.seed` |
| `contract` | | a contract the table also conforms to, when it is stored |

`measure: true` makes the explain obligations apply (8.3). `exact` is `bits` (default for `number`), `text` (the only value
for `decimal`), or `relative:<x>` with a reason in `about`. `passes: <input>.<column>` says the column is an input column
handed through unchanged (a host masks it for a viewer who has that input column masked).

### 6.3 JSON Schema (draft 2020-12)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:drishti:engine:1:descriptor",
  "type": "object",
  "additionalProperties": false,
  "required": ["engine", "id", "version", "title", "about", "publisher", "api", "entry", "inputs", "outputs"],
  "properties": {
    "engine": { "const": 1 },
    "id": { "$ref": "#/$defs/name" },
    "version": { "type": "string", "pattern": "^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$" },
    "title": { "type": "string", "minLength": 1, "maxLength": 80 },
    "about": { "type": "string", "minLength": 1 },
    "publisher": { "type": "object", "additionalProperties": false, "required": ["id", "name"],
                   "properties": { "id": { "type": "string" }, "name": { "type": "string" } } },
    "api": { "type": "string" },
    "entry": { "type": "string", "pattern": "^[A-Za-z_][\\w.$]*$" },
    "codes": { "type": "string", "pattern": "^[A-Z]{2,5}$", "not": { "enum": ["ENG", "DRS", "RCH", "LK"] } },
    "licence": { "type": "object", "additionalProperties": false, "properties": { "feature": { "type": "string" } } },
    "snapshot": { "type": "object", "additionalProperties": false, "properties": {
      "dimensions": { "type": "array", "items": { "type": "object", "additionalProperties": false,
        "required": ["name", "type"], "properties": { "name": { "$ref": "#/$defs/name" },
          "type": { "enum": ["date", "text", "int"] }, "label": { "type": "string" } } } } } },
    "inputs": { "type": "object", "propertyNames": { "$ref": "#/$defs/name" },
                "additionalProperties": { "$ref": "#/$defs/input" } },
    "options": { "type": "object", "propertyNames": { "$ref": "#/$defs/name" },
                 "additionalProperties": { "$ref": "#/$defs/typed" } },
    "params": { "type": "object", "propertyNames": { "$ref": "#/$defs/name" },
                "additionalProperties": { "$ref": "#/$defs/typed" } },
    "outputs": { "type": "object", "minProperties": 1, "propertyNames": { "$ref": "#/$defs/name" },
                 "additionalProperties": { "$ref": "#/$defs/output" } },
    "capabilities": { "type": "array", "uniqueItems": true,
                      "items": { "enum": ["explain", "workbook", "incremental", "partition", "process", "supplier"] } },
    "partition": { "type": "object", "additionalProperties": false, "required": ["input", "unit"],
                   "properties": { "input": { "$ref": "#/$defs/name" }, "unit": { "type": "string" } } },
    "uses": { "type": "object", "additionalProperties": { "type": "object", "additionalProperties": false,
              "required": ["contract", "about"],
              "properties": { "contract": { "$ref": "#/$defs/contract" }, "about": { "type": "string" } } } },
    "limits": { "type": "object", "additionalProperties": false, "properties": {
      "memory": { "type": "object", "additionalProperties": false, "properties": {
        "fixed-bytes": { "type": "integer", "minimum": 0 },
        "per-row-bytes": { "type": "object", "additionalProperties": { "type": "integer", "minimum": 0 } } } },
      "cancel-latency": { "type": "string", "pattern": "^\\d+(ms|s)$" } } },
    "aliases": { "type": "object", "additionalProperties": { "$ref": "#/$defs/name" } },
    "ui": { "type": "object" }
  },
  "$defs": {
    "name": { "type": "string", "pattern": "^[a-z][a-z0-9-]{0,63}$" },
    "contract": { "type": "string", "pattern": "^[a-z][a-z0-9-]*/[a-z][a-z0-9-]*@\\d+$" },
    "typed": { "type": "object", "additionalProperties": false, "required": ["type"], "properties": {
      "type": { "enum": ["text", "int", "number", "decimal", "bool", "date", "enum", "list", "records"] },
      "label": { "type": "string" }, "help": { "type": "string" }, "unit": { "type": "string" },
      "default": {}, "required": { "type": "boolean" }, "min": { "type": "number" }, "max": { "type": "number" },
      "allowed": { "type": "array" }, "options": { "type": "array", "items": { "type": "string" } },
      "items": { "enum": ["text", "int", "number", "decimal", "bool", "date"] },
      "fields": { "type": "object", "additionalProperties": { "$ref": "#/$defs/typed" } } },
      "allOf": [
        { "if": { "properties": { "type": { "const": "enum" } } }, "then": { "required": ["options"] } },
        { "if": { "properties": { "type": { "const": "list" } } }, "then": { "required": ["items"] } },
        { "if": { "properties": { "type": { "const": "records" } } }, "then": { "required": ["fields"] } } ] },
    "input": { "type": "object", "additionalProperties": false, "required": ["contract", "about", "columns"], "properties": {
      "contract": { "$ref": "#/$defs/contract" }, "about": { "type": "string" },
      "required": { "type": "boolean" }, "mode": { "enum": ["session", "request"] },
      "key": { "type": "array", "items": { "type": "string" } },
      "columns": { "type": "array", "minItems": 1, "uniqueItems": true, "items": { "type": "string" } },
      "vector": { "type": "string" },
      "scope-columns": { "type": "array", "uniqueItems": true, "items": { "type": "string" } },
      "changes": { "enum": ["none", "rows"] },
      "ordered-by": { "type": "array", "items": { "type": "string" } },
      "per": { "type": "array", "items": { "$ref": "#/$defs/name" } } } },
    "column": { "type": "object", "additionalProperties": false, "required": ["name", "type"], "properties": {
      "name": { "type": "string" },
      "type": { "enum": ["string", "integer", "number", "decimal", "boolean", "date", "timestamp", "time", "list"] },
      "nullable": { "type": "boolean" }, "label": { "type": "string" }, "unit": { "type": "string" },
      "format": { "type": "string" }, "scale": { "type": "integer" }, "measure": { "type": "boolean" },
      "exact": { "type": "string", "pattern": "^(bits|text|relative:[0-9.eE-]+)$" },
      "passes": { "type": "string" } } },
    "output": { "type": "object", "additionalProperties": false, "required": ["title", "about", "columns"], "properties": {
      "title": { "type": "string" }, "about": { "type": "string" },
      "shape": { "enum": ["table", "row", "series"] },
      "params": { "type": "array", "items": { "$ref": "#/$defs/name" } },
      "needs": { "type": "array", "items": { "$ref": "#/$defs/name" } },
      "key": { "type": "array", "items": { "type": "string" } },
      "order": { "type": "array", "items": { "type": "string" } },
      "axis": { "type": "string" },
      "columns": { "type": "array", "minItems": 1, "items": { "$ref": "#/$defs/column" } },
      "paged": { "type": "boolean" }, "stochastic": { "type": "boolean" },
      "contract": { "$ref": "#/$defs/contract" } } }
  }
}
```

### 6.4 Rules the schema cannot say

1. Every name in an output's `params` MUST be in `params`; every name in `needs` and `partition.input` in `inputs`. [K]
2. `explain` and `workbook` MUST be listed when any column has `measure: true`. [K]
3. Every option, parameter, input, output and column MUST have words (`about`, `label` or `help`). [K]
4. `key` columns MUST be non-nullable and MUST NOT be measures. [K]
5. `Engine.descriptor()` MUST equal the file. [K]

## 7. Lifecycle

### 7.1 Engine instance

| State | Entered by | Allowed next |
|---|---|---|
| `NEW` | construction | `open` → `OPEN`; `close` → `CLOSED` |
| `OPEN` | `open` returned | `session`, `assemble`; `close` → `CLOSED` |
| `FAILED` | `open` threw | `close` → `CLOSED` |
| `CLOSED` | `close` returned | none; a further `close` MUST be harmless |

A host MUST call `open` exactly once, MUST close every session before `close`, and MUST NOT call anything after `close`.

### 7.2 Session

| State | Entered by | Allowed next |
|---|---|---|
| `OPENING` | `Engine.session` called | returns → `READY`; throws → no session exists |
| `READY` | | `ask`, `explain`, `workbook`, `partial` (concurrently); `apply` (one at a time, concurrent with asks); `close` |
| `STALE` | the host saw a newer stamp of an input (the engine is not told) | the host finishes requests in flight, then `apply` → `READY`, or `close` and a new session |
| `CLOSED` | `close` returned | none |

1. A host MUST NOT call `close` while a call on the session is running; it cancels them through `Control` first and waits for
   the engine's declared `cancel-latency`.
2. A host MAY open sessions of several snapshots at once and MAY close any of them at any time between calls (eviction).
   An engine MUST NOT assume a session lives beyond a call.
3. At most one session per engine instance and snapshot is open at a time.

### 7.3 Request

`ACCEPTED` → `RUNNING` → one of:

| Outcome | How the engine signals it |
|---|---|
| `COMPLETE`, `PARTIAL`, `NOT_FOUND` | returns an `Answer` (8.2) |
| `REFUSED` | throws `EngineException` of kind `REFUSED` or `NOT_PERMITTED`, with words a person may read |
| `LIMIT` | throws kind `LIMIT` (a budget, a size bound it declares) |
| `CANCELLED`, `TIMED_OUT` | `Control.check()` threw; the engine lets it pass |
| `FAILED` | any other exception; its text goes to the host's log only |

## 8. Obligations

### 8.1 Determinism

1. For the same engine version, options, input rows, snapshot, request (outputs, parameters, visibility) and seed, an engine
   MUST return tables that are equal cell for cell under the comparison of 3.1. [K]
2. This MUST hold over repeated runs, over any executor size from 1 up, over any batch size and any delivery order of rows
   (unless `ordered-by`), over a fresh session and one that has answered other requests, and over the in-process and process
   bindings. [K]
3. An engine MUST NOT read a clock other than `EngineContext.clock()`, MUST draw random numbers only from a generator seeded
   by `Request.seed`, and MUST NOT let iteration order of a hash structure reach an output. [K, by scan and by law]
4. A `number` column MAY declare `exact: relative:<x>` only where a native library computes it; the engine MUST then offer an
   option that forces the pure-Java path, under which the column is `bits`. [K]
5. `Answer.stamp` MUST be built with `Stamps.of(context, session, request)` (a helper in the API), so every engine's stamps
   have one form.

### 8.2 Missing inputs

1. A required input that is not present, or lacks a declared column, MUST give `NOT_FOUND` (no output answerable) or `PARTIAL`
   (some answerable), with a `Missing` per input naming the contract, the snapshot, the columns and the outputs. [K]
2. An engine MUST NOT substitute, estimate or default a missing input value, and MUST NOT price or revalue to fill one. A
   missing value inside a present row is treated as the contract says (null), never invented.
3. A `uses` port that is not bound is the same: the outputs that need it are missing with the port's name ("not supplied").
4. Missing inputs are never an exception.

### 8.3 Explanation and workbook

For every output column with `measure: true`:

1. `explain` MUST answer for every cell the engine can return, with at least one step, and the last step's value MUST equal
   the cell under the column's `exact` class. [K]
2. Each step that is not `opaque` MUST carry a Rachana-EL formula that, evaluated by the reference evaluator over the step's
   `given` values and earlier steps' values, gives the step's value within relative 1e-12 (numbers) or exactly (text,
   integers, decimals as text). [K] `given` MUST hold the engine's own numbers for this cell, not an example.
3. Steps MUST be enough for a reader to follow the figure from input values: the `given` of the first steps are values of
   input rows (bounded: an engine MAY give the rows that decide the figure and a count of the rest, saying so in `label`).
4. An `opaque` step MUST say `why` and name the workbook cell that holds its value. An engine SHOULD have none; the kit
   reports the count and a host MAY refuse an engine above a bound it sets.
5. `workbook` MUST write a valid `.xlsx` with: a sheet `Data` holding values only; a sheet `Main` whose figures are formulas
   over `Data`; a defined name `OUT_<OUTPUT>_<COLUMN>` (upper case, `-` as `_`) per measure, for the first row of the answer,
   and a drop-down cell per `enum` parameter that changes the result. Every formula's cached value MUST be present.
6. Recalculated by a spreadsheet engine the author did not write, each defined name MUST equal the engine's cell within
   relative 1e-9, and the cached values MUST equal the recalculated ones. [K] Two calls for the same request MUST write the
   same bytes. [K]
7. Explanations and workbooks are computed under the request's visibility and MUST NOT show a row outside it. [K]

### 8.4 Closed

An engine MUST NOT: open a network connection; start a process; load native code other than from its own jar, behind a guard
that falls back to Java; read or write a file outside `workDir()` (reading its own jar's resources is allowed); create a
thread or an executor; call `System.exit` or `Runtime.halt`; read environment variables or system properties for behaviour;
hold mutable static state; use reflection to reach a host class. [K, by scan] An engine MUST NOT read data except through
`Inputs` and `Supplier`.

### 8.5 Resources

1. Before allocating more than 1 MB for a session or a request, an engine MUST reserve it from `MemoryBudget`, and MUST
   release it at `close` or when the request ends. A refusal (`ENG-5002`) MUST leave the engine usable. [K]
2. `Session.memoryBytes()` MUST be within 20% of what the session holds, and 0 after `close`. [K, by heap measurement on
   the kit's cases]
3. All work runs on the calling thread or `EngineContext.executor()`.
4. A request MUST stop within the declared `cancel-latency` (default 1 s) of cancellation or deadline. [K]
5. Files under `workDir()` are the engine's to manage; a host MAY delete the folder when the engine is closed, and an engine
   MUST start correctly from an empty one.

### 8.6 Visibility and isolation

1. A request whose `Visibility` lacks a filter for an input that declares `scope-columns` MUST be refused (`ENG-4002`); a
   host MUST refuse a request with no visibility before the engine is called (`ENG-4001`). Nothing is ever read as "all" by
   default: `Filter.ALL` is given explicitly.
2. An answer under a visibility MUST equal the answer, under `Filter.ALL`, of a session whose inputs hold only the rows the
   visibility allows (**filter equivalence**). [K]
3. Any state derived from one request (a cache of a view, a bitset) MUST be keyed by `Visibility.signature` and MUST NOT be
   reachable by a request with another signature. [K, by interleaving]
4. An engine MUST NOT keep `actor`, and MUST NOT vary an answer by it.
5. Two engine instances in one JVM MUST share nothing mutable. [K]

### 8.7 Incremental and partition

1. **Incremental equivalence.** After `apply(changes)` returns true, every answer MUST equal that of a fresh session over
   the changed inputs. [K]
2. **Additivity.** For any split of the partition unit's values into shards, `assemble` over the shards' partials MUST equal
   `ask` on the whole. [K] A `Partial` MUST NOT hold more than aggregates: its size MUST NOT grow with the number of input
   rows of a shard beyond the size of the answer's own grain (a host MAY bound it).
3. `assemble` MUST refuse partials whose stamps differ in engine version, options, request or seed (`ENG-5021`).

## 9. The host's engine file

Normative for Drishti; another host MAY use the same schema.

### 9.1 Keys

`config/engines/<id>.yaml`, under `drishti.engines.<id>`:

| Key | Required | Meaning |
|---|---|---|
| `enabled` | yes | the switch |
| `changed` | | `{ by, at, reason }`, written by the server |
| `engine.artifact` | yes | Maven coordinates `group:artifact`, or a jar path anchored to a named root (`${module.dir}/lib/x.jar`) |
| `engine.version` | yes | exact; MUST equal the descriptor's |
| `engine.sha384` | yes | of the jar; a mismatch refuses the start (`ENG-1011`) |
| `engine.run` | | `in-process` (default) or `process` |
| `engine.allow-uncertified` | | default `false` (12.6) |
| `snapshot` | | `hot` (sessions kept open), `pinned`, `idle-close` |
| `inputs.<name>` | | binding left open by contracts (10.2): `kinds: [..]` or `table: <lake table>`; `columns: { <contract column>: <table column> }`; `where:` one Rachana-EL expression over a row (`@.`), hermetic |
| `options` | | the engine's options; checked against the descriptor |
| `triggers` | | list (9.3); default `[ { on: request } ]` |
| `asks.<name>` | | a named request for triggers: `{ outputs, params, snapshot, as }` |
| `outputs.<name>` | | `enabled`, `serve: [protocol, flight]`, `live: true`, `store: { lake, table, mode }`, `access` |
| `uses.<port>` | | `{ engine, output }` |
| `access` | yes | `opens: [..]`, `visibility: <host rule>` |
| `limits` | | `memory-mb`, `threads`, `request-seconds`, `concurrent: { interactive, event, batch }`, `disk-gb`, `refusal-ttl-seconds` |
| `placement` | | `roles`, `nodes`, `shards: { count }` |

### 9.2 JSON Schema (the value of `drishti.engines.<id>`)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:drishti:engine:1:config",
  "type": "object",
  "additionalProperties": false,
  "required": ["enabled", "engine", "access"],
  "properties": {
    "enabled": { "type": ["boolean", "string"] },
    "changed": { "type": "object" },
    "engine": { "type": "object", "additionalProperties": false, "required": ["artifact", "version", "sha384"], "properties": {
      "artifact": { "type": "string" }, "version": { "type": "string" },
      "sha384": { "type": "string", "pattern": "^[0-9a-f]{96}$" },
      "run": { "enum": ["in-process", "process"] }, "allow-uncertified": { "type": "boolean" } } },
    "snapshot": { "type": "object", "additionalProperties": false, "properties": {
      "hot": { "type": "integer", "minimum": 1 }, "idle-close": { "type": "string" }, "pinned": { "type": "object" } } },
    "inputs": { "type": "object", "additionalProperties": { "type": "object", "additionalProperties": false, "properties": {
      "kinds": { "type": "array", "items": { "type": "string" } }, "table": { "type": "string" },
      "columns": { "type": "object", "additionalProperties": { "type": "string" } },
      "where": { "type": "string" } },
      "not": { "required": ["kinds", "table"] } } },
    "options": { "type": "object" },
    "triggers": { "type": "array", "items": { "type": "object", "additionalProperties": false, "required": ["on"], "properties": {
      "on": { "enum": ["request", "schedule", "data-version", "changes", "after"] },
      "cron": { "type": "string" }, "zone": { "type": "string" }, "every": { "type": "string" },
      "input": { "type": "string" }, "inputs": { "type": "array", "items": { "type": "string" } },
      "from": { "type": "string" }, "engine": { "type": "string" }, "output": { "type": "string" },
      "ask": { "type": "string" }, "quiet": { "type": "string" }, "max-wait": { "type": "string" } } } },
    "asks": { "type": "object", "additionalProperties": { "type": "object", "additionalProperties": false,
      "required": ["outputs"], "properties": { "outputs": { "type": "array", "items": { "type": "string" } },
        "params": { "type": "object" }, "snapshot": { "type": "object" }, "as": { "type": "string" } } } },
    "outputs": { "type": "object", "additionalProperties": { "type": "object", "additionalProperties": false, "properties": {
      "enabled": { "type": ["boolean", "string"] },
      "serve": { "type": "array", "items": { "enum": ["protocol", "flight"] } },
      "live": { "type": "boolean" },
      "store": { "type": "object", "additionalProperties": false, "required": ["lake", "table", "mode"], "properties": {
        "lake": { "type": "string" }, "table": { "type": "string" }, "mode": { "enum": ["by-snapshot", "append"] } } },
      "access": { "type": "object" } } } },
    "uses": { "type": "object", "additionalProperties": { "type": "object", "additionalProperties": false,
      "required": ["engine", "output"], "properties": { "engine": { "type": "string" }, "output": { "type": "string" } } } },
    "access": { "type": "object", "additionalProperties": false, "required": ["visibility"], "properties": {
      "opens": { "type": "array", "items": { "type": "string" } }, "visibility": { "type": "string" } } },
    "limits": { "type": "object", "additionalProperties": false, "properties": {
      "memory-mb": { "type": "integer", "minimum": 0 }, "threads": { "type": "integer", "minimum": 0 },
      "request-seconds": { "type": "integer", "minimum": 1 }, "disk-gb": { "type": "integer", "minimum": 0 },
      "refusal-ttl-seconds": { "type": "integer", "minimum": 0 },
      "concurrent": { "type": "object", "additionalProperties": { "type": "integer", "minimum": 1 } } } },
    "placement": { "type": "object" }
  }
}
```

### 9.3 Triggers

| `on` | Fires | The host then |
|---|---|---|
| `request` | a caller asks | opens or finds the session; `ask` |
| `schedule` | `cron` with `zone`, or `every` | runs the named `ask` in class `BATCH` as the identity `as` names |
| `data-version` | the stamp of one of `inputs` (default: all) moved | marks sessions stale; reopens hot ones; re-asks stored and subscribed requests in class `EVENT` |
| `changes` | rows arrive on `from` (a live table or stream) for `input` | `apply`; re-asks subscribed requests (narrowed by `affected`); sends differences by key to live tables |
| `after` | `engine`/`output` was stored | as `data-version` |

1. A host MUST coalesce firings (`quiet`, `max-wait`) and MUST NOT run two `apply` calls of one session at once.
2. A `changes` trigger whose source is not configured or not reachable is **not an error**: the engine serves its snapshot
   and the host shows "waiting: not configured". No engine start fails for an absent stream, schedule peer or supplier.
3. A file MUST NOT contain code. `where` is the only expression; it is Rachana-EL, evaluated by the host, and MUST read
   nothing but the row.

### 9.4 Start checks

A host MUST refuse to start an engine (state `blocked`, with the words and the key) when: the jar is absent or its hash
differs; the descriptor is invalid or outside the host's API range; an option is unknown or invalid; a required input is
unbound or ambiguous (10.2); `access.visibility` names no rule; the certificate is absent or does not match and
`allow-uncertified` is false. None of these stops the host or any other engine.

## 10. Packs and binding

*How a pack becomes aware of an engine, and how an engine's results reach a pack.* Normative for Drishti.

### 10.1 Declarations

1. A kind **conforms** to a contract when its own contract file says so: `conforms: [ <owner>/<name>@<major> ]`. The pack's
   build MUST check it (every required field of the contract present with a compatible type); a false claim fails the build.
2. A pack **uses** engine outputs in `pack.yaml`:

   ```yaml
   uses:
     engines:
       - engine: <id>
         version: ">=1.0 <2"
         outputs: [ <output>, … ]
         optional: false
         as: { <output>: { kind: <pack kind name>, mnemonic: <WORD> } }
   ```
3. A pack MUST NOT name an engine's class, jar or table. A pack MUST NOT contain a formula for a column an engine it uses
   marks as a measure.

### 10.2 Binding an input

For each input of each enabled engine, the first rule that applies:

| # | Condition | Result |
|---|---|---|
| 1 | the engine file names `kinds` or `table` | those, in order; several kinds are presented as one table (their union, each row's source in the host's own column) |
| 2 | exactly one kind of the enabled modules conforms to the input's contract | that kind |
| 3 | several conform | `blocked` (`ENG-2012`), listing them; a host MUST NOT choose |
| 4 | none conforms, input required | `blocked` (`ENG-2011`) |
| 5 | none conforms, input optional | bound to "absent": `InputTable.present()` is false |

A host MUST show, for every engine, each input's bound table, the rule that bound it and its current stamp.

### 10.3 Absence

When an engine a pack uses is not installed, is off, is blocked, or is outside the pack's version range:

1. Everything derived from the use is not offered: the virtual kinds and their mnemonics, the screens and menu entries that
   read them, Help pages, tiles, command words, pivot measures, AI tools.
2. Opened directly, each answers in words with the reason and who can change it (`ENG-5030` to `ENG-5033`).
3. With `optional: true` the screen renders and only the panels reading the output show their empty state with the reason.
4. The pack, its module and the host MUST start. No absence of an engine is a start failure.

### 10.4 Publication of outputs

For each enabled output a host MUST publish:

1. a Data Protocol table `engine.<id>.<output>` whose schema is the descriptor's (key, column types, labels, formats,
   units), with `can` set from what the host does for it (`page` for `paged`, `changes` for `live`, `arrow`);
2. an address for a parameterised answer: the table name followed by `~` and the normalised parameters as
   `name=value` pairs joined by `~` in name order (a host's existing id form), or the host's own request body;
3. when a pack gives it `as: { kind, mnemonic }`: a **virtual kind** of that name in the pack's vocabulary, computed on
   request, never stored, and its mnemonic;
4. when `store` is set: the rows in the lake table under the output's `contract`, with the answer's stamp in the host's
   provenance column; the stored table is then a kind like any other;
5. `…/explain` and `…/workbook` beside the table.

A caller sees a table only if the output's `access` allows it; rows are those of the caller's visibility. A fragment's table
parameter (a contract) accepts an output whose columns meet it; otherwise the problem is Rachana's `RCH-2116` at the use.

## 11. Errors

### 11.1 `EngineException`

```java
public final class EngineException extends Exception {
    public enum Kind { REFUSED, NOT_PERMITTED, LIMIT, CANCELLED, TIMED_OUT, UNAVAILABLE, FAILED }
    public EngineException(String code, Kind kind, String message);
    public EngineException(String code, Kind kind, String message, Map<String, String> detail, Throwable cause);
    public String code();  public Kind kind();  public Map<String, String> detail();
    public boolean retry();                    // true for LIMIT (after a wait), UNAVAILABLE, TIMED_OUT
}
```

1. `message` is for a person: what was asked, what is allowed, what to do. It MUST NOT contain a row's values, a path
   outside `workDir()`, a stack trace or a class name.
2. Kinds `REFUSED`, `NOT_PERMITTED`, `LIMIT` are shown to the caller. `FAILED` is logged and the caller is told only that
   the engine failed, with the request id.
3. A host maps kinds once (Drishti: `REFUSED` 422, `NOT_PERMITTED` 403, `LIMIT` 503 with `Retry-After`, `CANCELLED` 499,
   `TIMED_OUT` 504, `UNAVAILABLE` 503, `FAILED` 500; engine off 404) and its own code family once (`ENG-` and the engine's
   prefix to `DRS-`).

### 11.2 `ENG-` codes

| Code | Kind | Meaning |
|---|---|---|
| `ENG-1001` | REFUSED | the descriptor is not valid (position and key in the words) |
| `ENG-1002` | REFUSED | the descriptor's `api` range does not include this host's |
| `ENG-1003` | REFUSED | `entry` cannot be loaded, or `Engine.descriptor()` differs from the file |
| `ENG-1004` | REFUSED | a capability is listed without its interface, or the reverse |
| `ENG-1010` | REFUSED | the engine file is not valid (key in the words) |
| `ENG-1011` | REFUSED | the jar's hash or version differs from the pin |
| `ENG-1012` | REFUSED | no certificate for the pinned jar, or it does not match |
| `ENG-1013` | REFUSED | an option is unknown or outside what is allowed |
| `ENG-2001` | REFUSED | an input name the descriptor does not declare |
| `ENG-2003` | REFUSED | a column the input does not declare was asked for |
| `ENG-2011` | REFUSED | a required input has no conforming kind |
| `ENG-2012` | REFUSED | several kinds conform to an input and the file names none |
| `ENG-2013` | REFUSED | a bound table does not meet the contract (the first differences in the words) |
| `ENG-2020` | UNAVAILABLE | an input could not be read (the source is down); not "missing" |
| `ENG-3001` | REFUSED | an unknown parameter, with the valid names |
| `ENG-3002` | REFUSED | a parameter outside what is allowed, with what is |
| `ENG-3003` | REFUSED | an unknown output, or a parameter the output does not accept |
| `ENG-3004` | REFUSED | the snapshot lacks a declared dimension or has a value of another type |
| `ENG-3005` | REFUSED | `explain` asked for a cell that is not a measure or not in the answer |
| `ENG-4001` | NOT_PERMITTED | a request without a visibility |
| `ENG-4002` | NOT_PERMITTED | the visibility has no filter for an input with scope columns |
| `ENG-4003` | NOT_PERMITTED | the caller has a mask on an input column the output is computed from |
| `ENG-4004` | NOT_PERMITTED | the caller may not ask this output |
| `ENG-5001` | LIMIT | too many requests of this class now |
| `ENG-5002` | LIMIT | the memory budget cannot give what the session or request needs (how much, of how much) |
| `ENG-5003` | LIMIT | the work folder's disk bound |
| `ENG-5004` | LIMIT | an answer larger than the output's bound |
| `ENG-5010` | CANCELLED | cancelled |
| `ENG-5011` | TIMED_OUT | the deadline passed |
| `ENG-5020` | REFUSED | a call in a state that does not allow it (section 7) |
| `ENG-5021` | REFUSED | partials that do not belong together |
| `ENG-5030` | UNAVAILABLE | the engine is not installed on this server |
| `ENG-5031` | UNAVAILABLE | the engine is off (who, when, why) |
| `ENG-5032` | UNAVAILABLE | the engine is blocked (the start check that failed) |
| `ENG-5033` | UNAVAILABLE | the installed version is outside the range a pack needs |
| `ENG-6001` to `ENG-6099` | | kit findings, one per rule marked [K]; never thrown at run time |
| `ENG-7001` | UNAVAILABLE | the engine's process is not running or did not answer |
| `ENG-7002` | FAILED | the process and the host disagree on the wire version |
| `ENG-9000` | FAILED | the engine threw something else |

### 11.3 An engine's own codes

An engine with `codes: XYZ` MAY throw `XYZ-nnnn` codes for refusals particular to it. It MUST list each in
`META-INF/drishti-engine/<id>.codes.yaml` (`code`, `kind`, `meaning`), and the kit's coverage gate needs a case for each.
An engine MUST use the `ENG-` code where one says the same fact.

## 12. The kit

### 12.1 Running it

```
engine tck run --engine <jar> [--id <engine id>] --cases <dir> [--binding in-process|process|both] --out <dir>
```

writes `results.jsonl` (one line per case and law, doubles as raw-bit hex), `report.md` and `certificate.json`. Exit status
0 only when every rule marked [K] holds. The command needs a JDK 21 runtime and nothing else: no host, no network.

### 12.2 Case files

`tck/cases/<engine>/<suite>.yaml`, one YAML document (a template: `<…>` and `|` mark places and choices):

```text
suite: <engine>/<name>
engine: <id>
since: "1.0"                      # the engine version that introduced the suite
description: <words>
given:                            # shared by the cases; a case's own `given` is merged over it
  snapshot: { <dimension>: <value> }
  options: { <option>: <value> }
  inputs:
    <input>: { rows: [ { <COLUMN>: <value>, … }, … ] }      # or  { file: <name>.jsonl }  or  absent
  suppliers: { <port>: { answers: [ { params: {…}, tables: {…} } ] } }
cases:
  - id: <kebab-case>
    source: <where the expected values come from>            # required: a hand calculation, a reference, a recorder
    given: { … }                                             # optional overrides
    request:
      outputs: [ <output>, … ]
      params: { … }
      visibility: all | { <input>: <filter> }                # filter: { eq: [COL, v] } { in: [COL, [..]] } { and: [..] } …
      seed: 0
    expect:                                                  # exactly one of the three forms
      status: complete | partial
      tables:
        <output>:
          columns: [ <COLUMN>, … ]
          rows: [ [ <value>, … ], … ]
      missing: [ { input: <name>, columns: [..], outputs: [..] } ]
    # or   expect: { status: not-found, missing: [ … ] }
    # or   expect: { refused: <CODE> }
    explain: [ { cell: { output, column, key: {…} }, steps: <n or omitted> } ]   # default: every measure cell
    changes: [ { input: <name>, upserts: [ {…} ], deleted: [ <key> ] } ]         # then `expect` is after apply
    laws: all | [ <law>, … ] | { skip: [ <law> ], why: <words> }
```

Values: a YAML number for a `number` column is the double nearest to it and is compared by raw bits; the form `"0x…"`
(16 hex digits) gives the bits directly. `decimal`, `date` and `timestamp` are text. `null` is null. Comparison of `tables`
is exact: all rows, in order, all listed columns; an unlisted column of the output is an error unless the case says
`columns: subset`.

### 12.3 What a run checks

| Group | Rule |
|---|---|
| Descriptor | 6.3, 6.4 |
| Scan | 8.4, 8.1 rule 3, over the jar and its declared dependencies |
| Contracts | 3.2, 3.4, 5.2 rule 1: a recording `Inputs` and a checking `Tables` |
| Cases | each case's `expect` |
| Laws | 12.4, on every case unless skipped with a reason |
| Lifecycle | section 7; 8.5; two instances; an empty work folder; a 1 MB budget gives `ENG-5002` and the next ask works |
| Explain | 8.3 for each measure cell of each case |
| Gates | 12.5 |

### 12.4 Laws

| Law | Derived run | Must equal the case's answer |
|---|---|---|
| `repeat` | the same again, same session and a new one | yes |
| `threads` | executor sizes 1, 4, all | yes |
| `batching` | batch sizes 1, 7, 4,096 | yes |
| `order` | input rows reversed and shuffled (seeded) | yes, unless `ordered-by` |
| `filter-equivalence` | for the case's visibility V: inputs reduced to V, asked under `all`; and for `all` cases, a V the kit derives from the first scope column's values | the case under V |
| `interleaving` | two visibilities asked from 8 threads, 200 asks | each its solo answer |
| `incremental` | half of each `changes: rows` input withheld, session opened, the rest applied | yes (capability) |
| `additivity` | the partition unit's values split 1, 2 and 3 ways, partials assembled | yes (capability) |
| `binding` | the other binding | yes (capability `process`) |
| `absence` | each optional input removed in turn | outputs that do not `need` it unchanged; the others missing |
| `seed` | another seed | unchanged for outputs not `stochastic` |
| `explain` | every measure cell | 8.3 rules 1, 2, 6, 7 |

### 12.5 Gates

1. **Coverage.** Every output; every `options` and `enum`/`allowed` value of every parameter and option that an output
   accepts; every column of every input read by at least one case; every own error code; `NOT_FOUND` for every required
   input.
2. **Additive only.** The cases of the previous release of the same major, unchanged, pass. A changed expected value is a
   new major.
3. **Oracles.** A case without `source`, a measure without a workbook recalculation, a capability without its law: failures,
   never skips.
4. **Stability.** The whole run twice gives the same `results.jsonl` bytes.

### 12.6 The certificate

```json
{ "engine": "wmean", "version": "1.0.0", "jar-sha384": "…", "descriptor-sha384": "…",
  "api": "1.0.0", "tck": "1.0.0", "bindings": ["in-process"], "cases": 6, "laws": 58,
  "opaque-steps": 0, "results-sha384": "…", "at": "2026-11-21T20:00:00Z" }
```

It is written beside the jar as `<jar name>.certificate.json`. A host MUST verify `jar-sha384` and that `tck`'s major is one
it accepts. A certificate is a statement by whoever ran the kit; a host that needs more than that signs or re-runs (the kit
runs inside Drishti against the configured engine with `drishti.py engine tck <id>`).

## 13. Versioning

1. **This specification** has a major (`engine: 1`). Within it, keys, capabilities, parameter types, error codes and laws
   MAY be added. A host MUST refuse a descriptor key it does not know (`ENG-1001`), so an engine that needs a newer minor
   says so in `api`.
2. **`engine-api`** follows semantic versioning on its public packages. Within a major: no method is removed from an
   interface an engine implements; a method added to one has a default. A descriptor's `api` range MUST include the version
   the engine was compiled against.
3. **An engine** follows semantic versioning. Within a major it MUST NOT: remove or rename an output, column, parameter or
   option; change a type, key, order or default; change any value an existing case expects; make an optional input
   required. It MAY add outputs, nullable columns at the end, parameters and options with defaults that reproduce the old
   behaviour, optional inputs.
4. **Contracts** are referred to by major. A new major of an input contract is a new major of the engine.
5. **A host pins** one exact version and hash. Two versions of one engine MAY run side by side only under two ids.
6. **Stored answers** carry their stamp; a host MUST NOT serve an answer whose stamp names another engine version,
   descriptor, options or input stamp than the current ones.
7. **The kit** has a major matching this specification's. Laws added in a minor are reported but do not fail an engine
   certified before them until the next major.

## 14. The process binding

### 14.1 Running

1. An engine listing `process` MUST give the same answers when its `Engine` runs in another process than the host
   (law `binding`).
2. A host starts the worker as `java` (or, for an engine whose `entry` is of the form `python:<module>:<Class>`, `python`)
   with an explicit command line. No native launcher is used, so a site that cannot place executables runs every engine.
   Artifacts come from the local repository or a named folder; nothing is fetched at run time.
3. A worker crash is `ENG-7001` for the requests in flight; the host restarts the worker and reopens hot sessions; the
   engine's switch stays on.
4. A host MUST run in a separate process any engine that is neither first-party nor approved for in-process use by the
   site's own rule.

### 14.2 Wire forms

| Value | Form |
|---|---|
| `Table`, `Batch`, a `Partial`'s aggregates | Arrow IPC stream; the schema's metadata carries `TableSchema` |
| `Request`, `Snapshot`, `Visibility`, `Filter`, `Stamp`, `Missing`, `Explanation`, `Changes` (without rows), `EngineException` | canonical JSON (sorted keys, no insignificant space, numbers as shortest round-trip, doubles inside explanations also as raw-bit hex) |
| Workbook | bytes |

Calls are the methods of sections 4 and 5 by name. Two carriers: the host's worker pool (Arrow files in a scratch folder
and a control channel on the worker's standard streams) for a co-located worker; Arrow Flight (`DoAction` for open, session,
apply, close, explain; `DoGet` for ask, partial, workbook; the worker pulls inputs with `DoGet` from the host) for a remote
one. An engine is written once and does not know which.

## 15. Generated artefacts

`engine gen --engine <jar or descriptor> --out <dir> [--check]`. Output is deterministic; `GENERATED.sha384` lists every
file's hash; `--check` fails when the folder differs from what would be written. Generated files are committed and reviewed.

| Artefact | From | Normative rule |
|---|---|---|
| `sutras/engine-<id>-<output>.sutra.yaml`: a page per output | outputs, params, measures | uses only fragments of the `core` library; valid at language level 1.2; a default a pack MAY wrap or replace |
| `fragments/<id>-<output>.v1.fragment.yaml`: one typed fragment per output | the same | its table parameter is the output's contract |
| `docs/<id>.md`: the Help page | everything with words | no version history |
| `openapi/<id>.yaml` | outputs, params, errors | describes the host's generic endpoints for this engine |
| `config/<id>.yaml`: a template engine file; `CONFIGURATION` rows | options, limits | every option with its default and words |
| `tools/<id>.tools.json`: AI tool definitions | outputs, params | one tool per output, one per `explain` |
| `java/…/<Output>Params.java`, `<Output>Row.java`, `<Id>.java` (a typed client interface) | params, columns | records and an interface only; no logic |
| `tck/cases/<id>/<output>.yaml`: skeletons | outputs, params, inputs | one case per coverage item, `expect` to be filled, failing until it is |
| `workbook/<id>-<output>.xlsx.yaml`: a workbook layout | explanation steps of a sample case | only when every step is in the translatable subset of Rachana-EL |

At run time a host generates nothing: endpoints, checks, the Admin card and gating read the descriptor. A host MAY bind
`<Output>Params` records and the typed client by method handles and a dynamic proxy; such binding MUST NOT change any value.

## 16. A worked example

A minimal engine, end to end: the weighted mean of observations by group.

### 16.1 Descriptor

`META-INF/drishti-engine/wmean.engine.yaml`

```yaml
engine: 1
id: wmean
version: 1.0.0
title: Weighted mean
about: The weighted mean of a value by group, from observations supplied under a contract.
publisher: { id: org.example, name: Example Lab }
api: ">=1.0 <2"
entry: org.example.wmean.WmeanEngine
snapshot:
  dimensions: [ { name: date, type: date, label: Business date } ]
inputs:
  observations:
    contract: stats/observation@1
    about: One row per observation.
    key: [ ID ]
    columns: [ ID, GROUP, VALUE, WEIGHT ]
    scope-columns: [ GROUP ]
    changes: rows
params:
  min-weight: { type: number, default: 0, min: 0, label: Minimum weight, help: Observations with a smaller weight are left out. }
outputs:
  mean-by-group:
    title: Weighted mean by group
    about: One row per group that has at least one observation of positive weight.
    params: [ min-weight ]
    key: [ GROUP ]
    columns:
      - { name: GROUP,  type: string,  nullable: false, label: Group }
      - { name: COUNT,  type: integer, nullable: false, label: Observations }
      - { name: WEIGHT, type: number,  nullable: false, label: Total weight }
      - { name: MEAN,   type: number,  nullable: false, label: Weighted mean, measure: true, exact: bits }
capabilities: [ explain, workbook, incremental, partition ]
partition: { input: observations, unit: GROUP }
limits:
  memory: { fixed-bytes: 65536, per-row-bytes: { observations: 40 } }
  cancel-latency: 200ms
ui: { group: Statistics, words: [ WMEAN ] }
```

### 16.2 Implementation sketch

```java
package org.example.wmean;

public final class WmeanEngine implements Engine, Assembling {
    private EngineContext context;

    @Override public Descriptor descriptor() { return Descriptors.of(WmeanEngine.class, "wmean"); }
    @Override public void open(EngineContext context) { this.context = context; }

    @Override public Session session(Snapshot snapshot, Inputs inputs, Control control) throws EngineException {
        InputTable t = inputs.table("observations");
        if (!t.present()) {
            return new WmeanSession(context, snapshot, null);            // every ask answers NOT_FOUND
        }
        var hold = context.memory().reserve(Math.max(t.rows(), 0) * 40 + 65_536, "observations");
        Rows rows = new Rows();                                            // ID, GROUP, VALUE, WEIGHT as arrays
        t.scan(new Scan(List.of("ID", "GROUP", "VALUE", "WEIGHT"), Filter.ALL, 4_096), batch -> {
            control.check();
            rows.add(batch);                                               // copies: the batch's arrays are reused
        });
        rows.sortById();                                                   // a fixed order of summation: law `order`
        return new WmeanSession(context, snapshot, rows, hold);
    }

    @Override public Answer assemble(Request request, List<Partial> partials, Control control) {
        return WmeanSession.finish(request, Sums.merge(partials), context);   // groups are disjoint across shards
    }
    @Override public void close() { }
}

final class WmeanSession implements Session, Explaining, Incremental, Partitioned {
    private volatile Rows rows;                                            // replaced whole by apply: asks see old or new

    @Override public Answer ask(Request request, Control control) throws EngineException {
        if (rows == null) {
            return Answers.notFound(context, this, request,
                    new Missing("observations", "stats/observation@1", snapshot, List.of(), request.outputs()));
        }
        Filter see = request.visibility().rows().get("observations");
        if (see == null) {
            throw new EngineException("ENG-4002", Kind.NOT_PERMITTED, "the request names no rows of observations it may read");
        }
        double min = (Double) request.params().get("min-weight");
        Sums sums = Sums.of(rows, see, min, control);                      // per group, in ID order: sum(w), sum(v*w), count
        return finish(request, sums, context);
    }

    static Answer finish(Request request, Sums sums, EngineContext context) {
        Tables.Builder out = Tables.of(SCHEMA);
        sums.groupsInOrder().forEach(g -> out.row(g.name(), g.count(), g.weight(), g.product() / g.weight()));
        return Answers.complete(context, request, out.build());
    }

    @Override public Explanation explain(Request request, Cell cell, Control control) throws EngineException {
        List<Map<String, Object>> used = rowsOf(cell.key().get("GROUP"), request);     // [{ID, VALUE, WEIGHT, PRODUCT}]
        double product = sum(used, "PRODUCT"), weight = sum(used, "WEIGHT");
        return new Explanation(cell, product / weight, List.of(
            Steps.of("product", "Sum of value × weight", "sum($.rows, 'PRODUCT')", Map.of("rows", used), product),
            Steps.of("weight",  "Sum of weight",         "sum($.rows, 'WEIGHT')",  Map.of("rows", used), weight),
            Steps.of("mean",    "Weighted mean",         "step.product / step.weight", Map.of(), product / weight)));
    }

    @Override public void workbook(Request request, OutputStream out, Control control) throws IOException {
        // Data: ID, GROUP, VALUE, WEIGHT (values).  Main: B2 = minimum weight; per group
        // =SUMPRODUCT((Data!B:B=A5)*(Data!D:D>=$B$2)*Data!C:C*Data!D:D)/SUMIFS(Data!D:D,Data!B:B,A5,Data!D:D,">="&$B$2)
        // defined name OUT_MEAN_BY_GROUP_MEAN = Main!$D$5.  Generated from the steps by `engine gen` (section 15).
    }

    @Override public boolean apply(List<Changes> changes, Control control) {
        rows = rows.with(changes.get(0));                                  // a new sorted copy; one assignment
        return true;
    }
    @Override public Partial partial(Request request, Shard shard, Control control) throws EngineException { /* Sums as a table */ }
    @Override public long memoryBytes() { return rows == null ? 0 : rows.bytes(); }
    @Override public void close() { rows = null; hold.close(); }
}
```

What the sketch shows: no thread, no clock, no file, no user; state replaced whole so `apply` is atomic; a fixed order of
summation so the answer does not depend on delivery; the missing table as an answer; the visibility refused when absent.

### 16.3 A kit case

`tck/cases/wmean/mean-by-group.yaml`

```yaml
suite: wmean/mean-by-group
engine: wmean
since: "1.0"
description: The weighted mean by group; the minimum weight; a missing table; a refused parameter.
given:
  snapshot: { date: "2026-01-13" }
  inputs:
    observations:
      rows:
        - { ID: o1, GROUP: A, VALUE: 10, WEIGHT: 1 }
        - { ID: o2, GROUP: A, VALUE: 20, WEIGHT: 3 }
        - { ID: o3, GROUP: B, VALUE: 5,  WEIGHT: 2 }
cases:
  - id: two-groups
    source: "by hand: A = (10×1 + 20×3) / 4 = 17.5; B = (5×2) / 2 = 5"
    request: { outputs: [ mean-by-group ], params: {}, visibility: all, seed: 0 }
    expect:
      status: complete
      tables:
        mean-by-group:
          columns: [ GROUP, COUNT, WEIGHT, MEAN ]
          rows:
            - [ A, 2, 4, "0x4031800000000000" ]      # 17.5
            - [ B, 1, 2, 5 ]

  - id: minimum-weight-leaves-an-observation-out
    source: "by hand: A keeps o2 only: 20"
    request: { outputs: [ mean-by-group ], params: { min-weight: 2 }, visibility: all, seed: 0 }
    expect:
      status: complete
      tables: { mean-by-group: { columns: [ GROUP, COUNT, WEIGHT, MEAN ], rows: [ [ A, 1, 3, 20 ], [ B, 1, 2, 5 ] ] } }

  - id: a-viewer-of-group-b-sees-only-b
    source: "filter equivalence, by hand"
    request: { outputs: [ mean-by-group ], params: {}, visibility: { observations: { eq: [ GROUP, B ] } }, seed: 0 }
    expect:
      status: complete
      tables: { mean-by-group: { columns: [ GROUP, COUNT, WEIGHT, MEAN ], rows: [ [ B, 1, 2, 5 ] ] } }

  - id: a-new-observation-arrives
    source: "by hand: A = (10 + 60 + 30×4) / 8 = 23.75"
    request: { outputs: [ mean-by-group ], params: {}, visibility: all, seed: 0 }
    changes: [ { input: observations, upserts: [ { ID: o4, GROUP: A, VALUE: 30, WEIGHT: 4 } ] } ]
    expect:
      status: complete
      tables: { mean-by-group: { columns: [ GROUP, COUNT, WEIGHT, MEAN ], rows: [ [ A, 3, 8, 23.75 ], [ B, 1, 2, 5 ] ] } }

  - id: no-observations-table
    source: "section 8.2"
    given: { inputs: { observations: absent } }
    request: { outputs: [ mean-by-group ], params: {}, visibility: all, seed: 0 }
    expect: { status: not-found, missing: [ { input: observations, outputs: [ mean-by-group ] } ] }

  - id: negative-minimum-weight-is-refused
    source: "section 3.2: min 0"
    request: { outputs: [ mean-by-group ], params: { min-weight: -1 }, visibility: all, seed: 0 }
    expect: { refused: ENG-3002 }
```

### 16.4 The kit run

The form of the output (nothing is built yet; the counts are what these six cases would give):

```
$ engine tck run --engine wmean-engine-1.0.0.jar --cases tck/cases --out target/tck
descriptor   wmean 1.0.0  api >=1.0 <2  ok        scan  0 findings
cases        6 of 6
laws         repeat 5  threads 5  batching 5  order 5  filter-equivalence 5  interleaving 5
             incremental 5  additivity 5  absence 0 (no optional input)  seed 5  explain 8 cells  binding - (not declared)
lifecycle    states ok  two instances ok  close: 0 bytes, 0 tasks  cancel 41 ms (declared 200 ms)  1 MB budget: ENG-5002, then ok
explain      8 cells, 24 steps, 0 opaque; workbook recalculated: 8 of 8 equal
gates        coverage ok  additive-only: no previous release  oracles ok  stable over two runs
certificate  target/tck/wmean-engine-1.0.0.jar.certificate.json
```

(The refused case and the not-found case take the laws that apply to them: a refusal is the same refusal under every law.)

### 16.5 The engine file, and a pack that uses it

`config/engines/wmean.yaml`

```yaml
drishti:
  engines:
    wmean:
      enabled: ${DRISHTI_WMEAN_ENABLED:true}
      changed: { by: "", at: "", reason: "" }
      engine:
        artifact: org.example:wmean-engine
        version: 1.0.0
        sha384: "9f2c…"                        # 96 hex digits
      snapshot: { hot: 5 }
      # inputs: nothing to say. One kind of the modules that are on conforms to stats/observation@1.
      triggers:
        - { on: request }
        - { on: data-version }
      outputs:
        mean-by-group: { serve: [ protocol ], live: true }
      access: { opens: [ role:analyst ], visibility: core.all-rows }
      limits: { memory-mb: 64, request-seconds: 5 }
```

The pack that owns the data:

```yaml
# pack.yaml (surveys), the parts that matter
kinds:
  response: { conforms: [ stats/observation@1 ] }      # its contract maps RESPONDENT_ID→ID, REGION→GROUP, SCORE→VALUE, WEIGHT
uses:
  engines:
    - engine: wmean
      version: ">=1.0 <2"
      outputs: [ mean-by-group ]
      optional: true
      as: { mean-by-group: { kind: regional-score, mnemonic: RSCORE } }
```

At start Drishti binds `observations` to `response` by rule 2 of 10.2 and shows it on Admin → Engines → Weighted mean.
`RSCORE <GO>` opens the virtual kind `regional-score`. A load of `response` moves its stamp; the session reopens; the open
screen updates through its live table.

### 16.6 The generated screen

`engine gen` writes `sutras/engine-wmean-mean-by-group.sutra.yaml` (illustrative: the generator's templates are fixed against
the `core` library's catalogue when it is built, and the `data:` source form is the host profile's):

```yaml
rachana: 1.2
sutra: engine-wmean-mean-by-group
version: 1
title: Weighted mean by group
libraries: [ core@1 ]
state:
  min_weight: { type: number, default: 0 }
data:
  result: { engine: wmean/mean-by-group, with: { min-weight: "state.min_weight" } }
panels:
  - use: core/page-header@1
    with: { title: Weighted mean by group, note: "The weighted mean of a value by group, from observations supplied under a contract." }
  - use: core/entry-form@1
    as: params
    with:
      fields: [ { name: min_weight, label: Minimum weight, type: number, min: 0, help: Observations with a smaller weight are left out. } ]
    on: { change: [ { set: { min_weight: "event.values.min_weight" } } ] }
  - use: core/measure-table@1
    as: answer
    with:
      rows: $.result
      key: GROUP
      columns:
        - { label: Group, bind: "@.GROUP" }
        - { label: Observations, bind: "@.COUNT" }
        - { label: Total weight, bind: "@.WEIGHT" }
        - { label: Weighted mean, bind: "@.MEAN", measure: true }
      explain: { engine: wmean/mean-by-group }          # the "why" drawer: the steps of section 16.2
      workbook: { engine: wmean/mean-by-group }         # the formula workbook button
  - use: core/source-note@1
    with: { text: "Computed by Weighted mean 1.0.0 (Example Lab). Stamp shown with each answer." }
```

Nothing in sections 16.5 and 16.6 was written by the engine's author beyond the descriptor of 16.1, and nothing in Drishti
was written for this engine.
