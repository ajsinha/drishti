<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# The access model: licence, module, lens, permission

*Internal design, decided with the product owner on 2026-11-07. Status: design; the mechanism is built with the domain-module split in
the e+f+g release train (v2.9.11.g); the signed licence, the permission rules and the database store come with trust, RBAC and security
(2.9.11.i), after the owner's security discussion.*

Drishti serves many industries and lines of business as **modules** (banking and risk first; public sector, AML and others later). A
customer runs only what it licenses, and a person sees only what their role allows. This document fixes how that is decided and enforced,
end to end: backend, data, screens, JavaScript, Help.

## 1. Four layers, one chain

| Layer | Question | Decided by |
|---|---|---|
| **Licence** | Which modules may this customer run, within which limits? | the customer's licence |
| **Module** | Does this capability exist here? | installed ∧ licensed ∧ enabled by an administrator |
| **Lens** | Which experience does a person work in? (Risk, Trading, Market, World, later Public sector, AML) | its definition; offered only when every module it needs is on |
| **Permission** | Which lenses may this person open, and what may they do inside? | their roles and attributes; row rules and masks inside |

```
licence  →  modules on  →  lenses offered  →  lenses this person may open  →  actions, rows and columns inside
(customer)  (installation)  (needs its modules)  (role)                          (role + row rules + masks)
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
| JavaScript and assets | which libraries may be served (e.g. drishti-risk) | not served to an installation or person without the module |
| The AI assistant, exports, embeds, API tokens | the same questions | tools, exports and embeds absent; tokens never exceed their owner |

The console and the browser never ask the registry directly: they ask the server, which asks the registry. One trust point.

## 3. The model

| Entity | Holds |
|---|---|
| **Module** | id, domain, version, dependencies on other modules, what it contributes (compute, services, packs, screens, JavaScript libraries, Help, AI tools), the licence feature that unlocks it |
| **Licence** | customer, modules allowed, limits (users, nodes, data volume, per module or per installation), valid from and to |
| **Lens** | name, the modules it needs, its menus, home and Help |
| **Role** | lenses it may open; actions inside each (view, edit, approve, see raw rows, administer) |
| **Assignment** | a person or group → roles, plus the attributes row rules use (desk, legal entity, ...) |

## 4. Two stores behind one interface

| | Now (v2.9.11.g) | Later (2.9.11.i) |
|---|---|---|
| Store | **YAML**: `config/modules/<module>.yaml` shipped with each module; `config/access/licence.yaml`, `lenses.yaml`, `roles.yaml`, `assignments.yaml` (planned paths) | **the one database**: SQLite by default, PostgreSQL; the same tables in both complete schema files; no migrations; switched by configuration; a tool copies the YAML into it once |
| Interface | `AccessStore` (read, change, watch) → `YamlAccessStore` | the same interface → `DatabaseAccessStore` |
| Changes | proposed and approved through the Approvals queue (four eyes), audited | the same |

One test suite runs against both stores and must give the same answers.

## 5. Embedded now, a service later

| | Now | Later |
|---|---|---|
| Where it runs | inside drishti-server, in process | its own service: an encrypted REST server (TLS or mTLS, signed answers) every node and console asks |
| Interface | `AccessRegistry` → `EmbeddedAccessRegistry` | the same interface → `RemoteAccessRegistry` (HTTP client: mTLS, signature check, local cache, change notifications) |
| When unreachable | not applicable | a setting: fail closed, or a grace period on the last signed answer (defaults decided in 2.9.11.i) |

Every answer is cacheable and invalidated on change, so asking stays cheap when the registry is remote.

## 6. Guarantees, as tests

- **Module off = gone everywhere**, per module: no bean, engine, job, API, pack, connector, route, menu entry, search hit, command, Help
  page, JavaScript asset, AI tool, export or embed (the engine switches' existing "engine off everywhere" test, generalised).
- **A lens whose modules are off does not exist**: no menu, no home, no link.
- **Only drishti-registry reads the access model** (an architecture test, as for the domain boundaries).
- **Both stores answer the same** (one suite, run against each).
- **Session isolation**: nothing per person in shared state.

## 7. What is built when

| Release | Built |
|---|---|
| v2.9.11.g (the e+f+g train, with the domain-module split) | drishti-registry with the embedded registry and the YAML store; module manifests; lenses declaring their modules; the administrator's module switch; editions folded into modules and lenses; the guarantees of section 6 |
| 2.9.11.i (after the security discussion) | the signed licence (offline verification, expiry and grace, limits, audit); permissions per lens and action; data licensing; the database store; the standalone service when wanted |

## 8. Related

The domain boundaries and the move: `docs/architecture/DOMAIN_MODULES.md` (planned; it lands with the split). The JavaScript libraries
modules contribute: `docs/architecture/ELEMENTS.md` (planned until the train merges). The one database: [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md).
The roadmap: [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).
