<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# The access model: licence, module, lens, function, permission

*Internal design, decided with the product owner on 2026-11-07. Status: design; the mechanism is built with the domain-module split in
the e+f+g release train (v2.9.11.g); the signed licence, the permission rules and the database store come with trust, RBAC and security
(2.9.11.j), after the owner's security discussion.*

Drishti serves many industries and lines of business as **modules** (banking and risk first; public sector, AML and others later). A
customer runs only what it licenses, and a person sees only what their role allows. This document fixes how that is decided and enforced,
end to end: backend, data, screens, JavaScript, Help.

## 1. Four layers, one chain

| Layer | Question | Decided by |
|---|---|---|
| **Licence** | Which modules may this customer run, within which limits? | the customer's licence |
| **Module** | Does this capability exist here? | installed ∧ licensed ∧ enabled by an administrator |
| **Lens** | Which experience does a person work in? (Risk, Trading, Market, World, later Public sector, AML) | its definition; offered only when every module it needs is on |
| **Function** | Which functions (command words such as VAR, TRD, EQV, EXP) may be used? | each belongs to one module; lenses bundle functions; roles are granted or denied single functions on top (as Bloomberg entitles functions) |
| **Permission** | Which lenses may this person open, and what may they do inside? | their roles and attributes; row rules and masks inside |

```
licence  →  modules on  →  lenses offered  →  functions this person may use  →  actions, rows and columns inside
(customer)  (installation)  (needs its modules)  (lens bundles + grants/denials)  (role + row rules + masks)
```

A module sells; a lens serves. Permissions are granted per lens and action, never per screen.

## 2. One registry, asked by every layer

**drishti-registry** is its own backend component (a Maven module). Every layer asks it; nothing else reads the access model.

| Asked by | What it asks | What "no" means |
|---|---|---|
| The server at start | which modules to load | an unlicensed or disabled module's beans, engines, jobs and APIs are never registered |
| Every API call | module on? lens open to this person? action allowed? | 404 "module not licensed" for a module that is off; 403 with the reason for a person not entitled; audited |
| Data | which packs, connectors and lake tables to load | an off module's packs and connectors are not loaded |
| The console | routes, menus, the sitemap, the Build home, search, commands, Help | entries absent; direct links refused |
| The command line, type-ahead, keys, shared views | function allowed for this person? | the function is invisible; typed anyway, it answers "VRES is not available to you: ask your administrator", audited |
| JavaScript and assets | which libraries may be served (e.g. drishti-risk) | not served to an installation or person without the module |
| The AI assistant, exports, embeds, API tokens | the same questions | tools, exports and embeds absent; tokens never exceed their owner |

The console and the browser never ask the registry directly: they ask the server, which asks the registry. One trust point.

## 3. The model

| Entity | Holds |
|---|---|
| **Module** | id, domain, version, dependencies on other modules, what it contributes (compute, services, packs, screens, JavaScript libraries, Help, AI tools), the licence feature that unlocks it |
| **Licence** | customer, modules allowed, limits (users, nodes, data volume, per module or per installation), valid from and to |
| **Lens** | name, the modules it needs, the functions it bundles, its menus, home and Help |
| **Function** | the command word (VAR, TRD, EQV ...), its module, the APIs and screens behind it, its Help page; the administrator's on/off switch (the mnemonic switch, folded in) |
| **Role** | lenses it may open; single functions granted or denied on top of the lenses' bundles; actions inside (view, edit, approve, see raw rows, administer) |
| **Assignment** | a person or group → roles, plus the attributes row rules use (desk, legal entity, ...) |

## 4. Two stores behind one interface

| | Now (v2.9.11.g) | Later (2.9.11.j) |
|---|---|---|
| Store | **YAML**: each module's manifest in its jar (`META-INF/drishti/module.yaml`, `lenses.yaml`), the site's switch `config/modules/<module>.yaml`; `config/access/licence.yaml`, `lenses.yaml`, `roles.yaml`, `assignments.yaml` (section 8) | **the one database**: SQLite by default, PostgreSQL; the same tables in both complete schema files; no migrations; switched by configuration; a tool copies the YAML into it once |
| Interface | `AccessStore` (read, change, watch) → `YamlAccessStore` | the same interface → `DatabaseAccessStore` |
| Changes | proposed and approved through the Approvals queue (four eyes), audited | the same |

One test suite runs against both stores and must give the same answers.

## 5. Embedded now, a service later

| | Now | Later |
|---|---|---|
| Where it runs | inside drishti-server, in process | its own service: an encrypted REST server (TLS or mTLS, signed answers) every node and console asks |
| Interface | `AccessRegistry` → `EmbeddedAccessRegistry` | the same interface → `RemoteAccessRegistry` (HTTP client: mTLS, signature check, local cache, change notifications) |
| When unreachable | not applicable | a setting: fail closed, or a grace period on the last signed answer (defaults decided in 2.9.11.j) |

Every answer is cacheable and invalidated on change, so asking stays cheap when the registry is remote.

## 6. Guarantees, as tests

- **Module off = gone everywhere**, per module: no bean, engine, job, API, pack, connector, route, menu entry, search hit, command, Help
  page, JavaScript asset, AI tool, export or embed (the engine switches' existing "engine off everywhere" test, generalised).
- **A lens whose modules are off does not exist**: no menu, no home, no link.
- **Function off = gone everywhere**, per function: command line, type-ahead, search, menus, keys, the APIs behind it, its Help page, AI tools, links, embeds and tokens.
- **Only drishti-registry reads the access model** (an architecture test, as for the domain boundaries).
- **Both stores answer the same** (one suite, run against each).
- **Session isolation**: nothing per person in shared state.

## 7. What is built when

| Release | Built |
|---|---|
| v2.9.11.g (the e+f+g train, with the domain-module split) | drishti-registry with the embedded registry and the YAML store; module manifests declaring their functions; lenses declaring their modules and function bundles; the mnemonic switches folded into the registry (installation-level function on/off); the administrator's module switch; editions folded into modules and lenses; the guarantees of section 6 |
| 2.9.11.j (after the security discussion) | the signed licence (offline verification, expiry and grace, limits, audit); permissions per lens, function (grants and denials) and action; data licensing; the database store; the standalone service when wanted |

## 8. As built: the backend (phase 1 of the split)

What exists now, on the server and in the console (the console's part since phase 2's first half: [DOMAIN_MODULES.md](DOMAIN_MODULES.md) section 8).

| Part | As built |
|---|---|
| **drishti-registry** | A Maven module of its own, plain Java (Jackson only). `AccessRegistry` (the questions) with `EmbeddedAccessRegistry` (one immutable snapshot of decisions, recomputed when the model changes, listeners told); `AccessStore` (read, change, stamp, watch) with `YamlAccessStore`; `AccessRegistries.embedded(settings)` makes it from the server's settings. Nothing else reads the model: an architecture test fails a class outside the registry that names `YamlAccessStore` or `AccessModel`. |
| **Module manifests** | Each module's jar ships `META-INF/drishti/module.yaml`: id, domain, title, feature, requires, and what it contributes (Java packages, engines, packs, connectors, API prefixes, functions). Installed exactly when the manifest is on the class path. The core server's jar ships two content-only modules (packs, lenses and functions, no code): **economy** (world-economy, news-and-markets and the public sector's world-development, central-banks, national-statistics, oecd, official-news and gdelt-events; the World and Public sector lenses: [WORLD_DATA_GUIDE.md](../guides/WORLD_DATA_GUIDE.md)) and **capitalmarkets** (us-equities, funds, and the market data's functions). GLEIF's legal entities are core reference data. The core is always on and is not a module file. |
| **The switch** | `config/modules/<module>.yaml` holds `enabled:` only (`POST /api/v1/admin/modules` writes it, with a reason, audited); `drishti.modules.<module>.enabled` (`DRISHTI_MODULES_<MODULE>_ENABLED`) wins over the file. A module is on when installed ∧ licensed ∧ enabled ∧ every module it requires is on. A change takes effect at the next start (Admin shows *restart needed*). |
| **Licence** | `config/access/licence.yaml` (customer, modules or `*`, limits, valid-from, valid-to) is read and decided, **unsigned**; without the file the installation runs the **development licence** (every module, no limits, no end), so nothing changes for an existing installation. Signature, grace and limits' enforcement: 2.9.11.j. |
| **Loading** | A module's Spring auto-configuration carries `@ConditionalOnModule("<id>")`: off, none of its beans, engines, scheduled jobs or controllers exist. Its engines show *not installed: the module 'banking' is off* on Admin → Engines; its API (new and old prefixes) answers `404 DRS-1025` in words; its packs (by the manifest's list, else by the pack's `domain:`) and its connectors do not load. |
| **Lenses** | Each jar ships `META-INF/drishti/lenses.yaml` (the banking module: risk, trading, market (it needs capitalmarkets and banking), cro, mr-cube, ccr-cube; the core: world, cio, oversight), each with the modules it needs and the functions it bundles; `config/access/lenses.yaml` adds or replaces lenses. A lens whose modules are off is not offered, and an edition opened as a lens (`drishti.lenses`) whose registry lens is not offered is dropped. |
| **Functions** | Each manifest lists its functions (the engines' words with the APIs behind them, and every command of its packs); a word no module declares is the core's. The mnemonic switches (Admin → Functions, kept in the switches ledger and gossiped) fold in: the ledger is the store of the installation-level switch, the registry decides (`functionOn` = its module on ∧ not switched off), and `CommandSwitches` asks the registry. A function that is off answers `404 DRS-1026` on its APIs. |
| **Per person** | `lensesFor(subject)`, `allowed(subject, lens, action)` and `functionAllowed(subject, word)` take the person already; with no `roles.yaml` every offered lens and every function that is on is open to everyone (403 `DRS-1027` is wired for 2.9.11.j's grants and denials). |
| **Module permissions** | The first action permission of a module, ahead of 2.9.11.j's model: a role carries `permissions`, a list of the names `RoleDefinition.PERMISSIONS` knows (today `var:system-baskets`: create, edit, retire and import the VaR engine's system baskets, [VAR_GUIDE.md section 5.7](../guides/VAR_GUIDE.md#57-system-baskets)). Configured roles and packs' roles list it (`permissions: [var:system-baskets]`); a role defined in Admin → Roles ticks it in its *Permissions* group (`PUT /api/v1/admin/role-definitions/{name}` `permissions`, kept as a row of `drishti_role_power` by its own name in both schema files: no schema change); every administrator holds every permission. `Entitlements.holds(person, permission)` asks; `requirePermission` answers `403` naming the permission. 2.9.11.j folds it into the per-lens action grants. |
| **API** | `GET /api/v1/admin/modules` (states, what each brings, licence, lenses, warnings), `POST /api/v1/admin/modules` (the switch), `GET /api/v1/modules` (as the caller: the modules loaded and `off` with why, the lenses open to them with their modules and functions, and `functions.off`: each function they may not use with its module, the reason and the words to say); `/public/about` lists the `modules` loaded at this start. |
| **The console** | Asks the server, never the model: the installation's `modules` (from the about answer, per console app) and the person's `/api/v1/modules` (cached per person, set per request). Every door asks one helper module (`core/modules.py`): routes and pages (404), words (the edition's `word_on`, `command_shown`, `typed_shown`), menus and the sitemap (`domain:` tags, `requires: module:<id>`), the Admin strip, search and the type-ahead, the command line (*VRES is not available to you: ask your administrator*), Help, the Build home and static files (a module's files are not served while it is off, nor to a person whose lens does not use it; the Elements loader refuses a library of a module the page does not list). What a module brings to the console is its `drishti-console/plugins/<module>/plugin.yaml`. |
| **Configuration by module** | A module's packs, lakes, BI files, tables, report definitions and lenses sit in a folder named after it (`config/packs/banking/`, `config/lakes/banking/`, `config/bi/reports/banking/`, `config/tables/banking/`, `config/reports/banking/`, `config/lenses/banking/`), read only while it is on; its token scopes come with its jar (`META-INF/drishti/token-scopes.yaml`) and its Help with its console plugin. Editions are lenses: `config/lenses/<module>/<lens>.yaml` carries a lens's packs by module, Home, menus, Help and branding ([DOMAIN_MODULES.md](DOMAIN_MODULES.md) section 8). |
| **Guarantee tests** | `BankingModuleOffTest` (no bean of its code, no engine, no job, no API new or old, no pack of its domain, no connector of its own, no function), `CapitalMarketsModuleOffTest` (its packs, connectors and functions gone; banking and GLEIF unaffected), `EconomyModuleOffTest` and `PublicSectorLensTest` (the economy's eight packs, connectors, functions and both lenses gone, or the public sector's there; banking, capital markets and GLEIF unaffected), `BankingModuleOnTest` (loaded, the old paths answered as the new, a function off gone from its API), `AccessRegistryTest` (the decisions), the architecture rules.; in the console `test_modules_off.py` (banking off, capital markets off, single functions off, a lens without a module: every door; the server's `/api/v1/modules` and `/public/about` answers in `BankingModuleOffTest` and `BankingModuleOnTest`) and `test_module_boundaries.py` (the core imports, includes and names nothing of a module). |

## 9. Related

The domain boundaries and the move: [DOMAIN_MODULES.md](DOMAIN_MODULES.md). The kernel, the module package, its loading and data binding, and distributions: [KERNEL_AND_MODULES.md](KERNEL_AND_MODULES.md). The JavaScript libraries
modules contribute: `docs/architecture/ELEMENTS.md` (planned until the train merges). The one database: [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md).
The roadmap: [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).

## 10. Row rules

A role may restrict **which records of a kind** its holders see, by the record's fields and the person's attributes. Row rules are declared
in the configured roles (`drishti.security.roles.<role>` in `config/application.yaml` or a pack's roles), beside `kinds` and `permissions`:

```yaml
drishti:
  security:
    roles:
      rates-trader:
        kinds: [trade, position]
        edit: true
        rows:
          trade: "ASSET_CLASS = 'RATES' and DESK in $user.desks"
      endur-support:
        kinds: [trade]
        edit: true
        rows:
          trade: "SOURCE_SYSTEM = 'ENDUR'"
        edit-systems: [ENDUR]           # write-back only to Endur's records
```

| Key | Meaning |
|---|---|
| `rows.<kind>` | A row predicate ([DATA_LAKE_GUIDE.md section 6.2](../guides/DATA_LAKE_GUIDE.md#62-several-source-systems-shared-or-separate-tables): `=`, `<>`, `in (...)`, `and`, `or`, `not`, `is null`; text quoted). `$user.<attribute>` is the person's attribute (`desks`, `desk`, `name`, `roles`, `email`, as the user directory gives them): a list after `in`, its one value otherwise; an attribute the person lacks matches nothing. |
| `edit-systems` | The trading systems (`SOURCE_SYSTEM`) whose records the role may change through write-back; absent or empty: every system. |

**Combining.** A person sees a record when any of their roles that opens the kind passes it; a role that opens the kind without a rule for
it opens every record of it; an administrator and a server with sign-in off see every record. A person may change a record of a system when
none of their roles lists `edit-systems`, or one of them lists that system.

**Where they apply.** Row rules restrict **record views**: the live sets behind lists and blotters (the person's reader, per request,
tested on every row and every live change; part of the audience, so two people with different rules never share a filled set), search (the
rule joins the search's condition), FORM and Raw JSON (a record outside the rule is not found). **Aggregates keep their own scope model**:
VaR, BI totals and other computed measures are not filtered by row rules (BI's dataset rules and VaR's scopes decide them).

**Tests.** `RowRulesTest` (two people on one canonical kind, a role without a rule, edit rights per `SOURCE_SYSTEM`, a rule that is not a
predicate), `LiveIsolationTest` (a reader's row scope on shared sets), `SourceRoutingTest` (write-back to the record's own system).
