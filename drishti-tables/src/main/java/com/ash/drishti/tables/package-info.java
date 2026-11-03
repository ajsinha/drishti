/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
/**
 * The table layer (docs/architecture/VIRTUAL_TABLES.md; docs/architecture/BI_FOUNDATION_2911E.md sections 3 to 5): virtual tables and
 * models defined once, read by every consumer (BI, the pivot, Arrow Flight, alerts).
 *
 * <ul>
 *   <li>{@code def}: the definitions ({@link com.ash.drishti.tables.def.TableDef}, {@link com.ash.drishti.tables.def.ModelDef}), immutable.</li>
 *   <li>{@code yaml}: their files ({@code config/tables/<domain>/<name>.table.yaml} and {@code .model.yaml}), read with every problem
 *       named by its key.</li>
 *   <li>{@code expr}: a small reader and printer of Rachana expression text, for the checks and the migration (never an evaluator).</li>
 *   <li>{@code check}: one table, one model and the whole catalogue checked; expressions through
 *       {@link com.ash.drishti.tables.check.ExpressionChecker}, the seam where Rachana's checked mode plugs in.</li>
 *   <li>{@code catalog}: the catalogue (files and stored items), names and {@code was:} aliases, lineage.</li>
 *   <li>{@code preview}: a table's rows over sample documents, and the sample checks (keys unique, paths present).</li>
 *   <li>{@code materialize}: the materialisation's API (none, on-load, live) and its store layout; BI's layers move behind it.</li>
 *   <li>{@code migrate}: BI's datasets and models into tables and layer models, DEL into Rachana through a pluggable step.</li>
 *   <li>{@code cli}: {@code tables check}, {@code tables preview}, {@code tables migrate}, {@code tables lineage}.</li>
 * </ul>
 *
 * <p>Dependency direction: this module reads Rachana and the pivot engine; BI (drishti-server) reads this module; nothing here reads BI
 * ({@code ArchitectureRulesTest}).
 */
package com.ash.drishti.tables;
