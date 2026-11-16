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

  Status: design, not built. An EXAMPLE: the order in which the console's route groups move from the Python console to the Drishti
  Console on Spring Boot in release 2.9.11.i5 (docs/architecture/KERNEL_AND_MODULES.md section 10.8). Sizes measured on 970160f9f.
-->

# Console route groups: the migration order

The Java console fronts every request from the first day of i5. A group moves when its Playwright browser tests pass against the Java
console with the rest still proxied to the Python console; its lines in `config/console/legacy-routes.yaml` are deleted in the same merge.
The pytest unit tests of the group's routes and `core/` helpers are replaced by JUnit and MockMvc tests. After G10 the Python console is
deleted.

Totals today: `routes/` 13,357 lines in 65 files; `core/` 20,140 lines in 102 files (they move with the groups that use them);
`plugins/` 3,548 lines; 148 templates (10,856 lines); about 86,000 lines of tests, of which about 32,000 are Playwright browser tests in
148 files (unchanged) and 68 files are unit tests of routes or `core/` (replaced per group).

| # | Group | Route files | Lines · routes | Templates | Main `core/` helpers | Notes |
|---|---|---|---|---|---|---|
| G0 | Shell and serving | `auth_routes`, `common`, `elements_routes`, `layout_routes` | 521 · 15 | `base.html`, `_footer`, `_nav_public`, `_theme_menu`, `_dyk`, `_macros/` (6), `auth/` (2), `account.html`, `confirm_post.html` | `app.py` (740), `backend.py` (926), `auth.py`, `channel.py`, `channel_relay.py`, `cluster.py`, `csrf.py`, `retry.py` | sign-in and sessions (Spring Session, Security), static and Elements, the live channel, the proxy to the Python console, module page hosting through `drishti-module-runtime` |
| G1 | Banking and economy pages | `plugins/banking/routes/*` (9 files), `plugins/banking/*.py` | 3,548 · 65 | 20 (`plugins/*/templates`) | `plugins.py`, `modules.py`, `engines.py` | the proving ground: the i2 declarative parts re-hosted unchanged; pass-throughs already `api.proxy` entries |
| G2 | Admin | `admin_routes`, `admin_commands_routes`, `admin_engines_routes`, `compute_admin_routes`, `collab_admin_routes`, `embed_admin_routes`, `config_editor_routes`, `connector_routes`, `lake_routes`, `lake_maint_routes`, `net_routes`, `server_routes`, `monitor_routes`, `pack_routes`, `dist_routes`, `ship_routes`, `edition_routes`, `functions_routes` | 3,868 · 200 | `admin/` (44), `monitor/` (2), `servers.html` | `admin_forms.py`, `lakes.py`, `lake_maint.py`, `settings_page.py`, `edition.py`, `packwizard.py` | the largest group by routes, but plain forms over kernel APIs |
| G3 | Terminal, Home, data, search | `terminal_routes`, `home_routes`, `data_routes`, `history_routes`, `asof_routes`, `phone_routes`, `render_routes`, `api_routes` | 1,750 · 50 | `terminal/` (26), `landing.html` | `menus.py`, `search.py`, `facts.py`, `facts_expr.py`, `describe.py`, `phone.py`, `history_rows.py` | the daily screens |
| G4 | Pivot and BI | `pivot_routes`, `bi_routes`, `model_routes`, `report_routes`, `export_routes`, `data_out_routes`, `tables_editor_routes`, `workspace_routes`, `artifact_routes` | 1,715 · 98 | `bi/` (20), `workspace/` (2), `reports.html`, `reports_all.html` | `model_layer.py`, `export.py`, `promote_report.py`, `arrow_answer.py` | heavy fan-out: the virtual-thread gain is measured here |
| G5 | Explain, approvals, alerts, collaboration | `explain_routes`, `approval_routes`, `alert_routes`, `changes_routes`, `collab_routes`, `thread_routes`, `drafts_routes`, `datamap_routes` | 1,244 · 59 | `explain`, `approvals`, `approvals_sutra`, `alert_form`, `alerts`, `alerts_all`, `inbox`, `subscribe` | `datamap.py` | live-channel users |
| G6 | Embed | `embed_routes`, `embed_v2_routes`, `embed_report_routes`, `embed_signin_routes` | 1,185 · 44 | `embed/` (1) | — | token sign-in for embeds, after SSO (section 10.11) is in place |
| G7 | Designers, Build, Studio, forms | `designer_routes`, `designer_grid_routes`, `build_routes`, `studio_routes`, `form_routes` | 1,844 · 72 | `designer/` (4), `build/` (10), `studio/` (2), `_designer_kit.html` | `designer*.py` (6 files), `build_registry.py`, `build_types.py`, `forms.py`, `element_api.py`, `element_sheet.py`, `sutra_diff.py` | the largest JavaScript-facing contracts |
| G8 | Help | `help_routes` | 364 · 13 | `help/` (9) | `guides.py` | merges modules' Help (section 4.7) |
| G9 | AI assistant | `ai_routes` | 213 · 5 | — | — | small |
| G10 | Notebooks, Python, transforms, Calc | `notebook_routes`, `python_routes`, `transform_routes`, `calc_routes` | 638 · 24 | `bi/notebook.html` (counted in G4) | `notebook.py`, `transform_editor.py` | last; the transform parse moves to kernel Python workers first; Calc's Pyodide stays in the browser |
