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
 * The lake configuration (docs/architecture/DATA_ARCHITECTURE.md, docs/guides/DATA_LAKE_GUIDE.md): {@code config/lakes/<lake>.yaml}, one file per
 * lake, describes where the lake is and every table in it, and every writer and reader refers to it.
 *
 * <p><b>Why a module of its own.</b> Its consumers sit on both sides of the plugin boundary and at both ends of the build: the parallel
 * loader (drishti-lakeload, run alone as a command), the data inbox (drishti-dataload), the source registry that hands every connector its
 * settings (drishti-engine), the in-memory lake (drishti-memlake), the server's Admin page, and the Python tools through
 * {@link com.ash.drishti.lakeconfig.LakesCli}. drishti-api has no YAML reader and must stay dependency-free; drishti-common brings Spring,
 * which the loader run as a command does not need. So the reader lives here, with Jackson as its only dependency, once: the Python tools
 * call {@code LakesCli} for the JSON of a lake rather than keeping a second reader with its own rules.
 *
 * <ul>
 *   <li>{@link com.ash.drishti.lakeconfig.Lakes} reads and checks the folder; {@link com.ash.drishti.lakeconfig.Lake} and
 *       {@link com.ash.drishti.lakeconfig.LakeTable} are what it holds.</li>
 *   <li>{@link com.ash.drishti.lakeconfig.ConnectorLake} gives a Delta or Iceberg connector that names {@code lake:} the settings its
 *       plugin reads, at the one point all connector settings pass.</li>
 *   <li>{@link com.ash.drishti.lakeconfig.PackEntry} is a pack's recommended entry (L2); {@link com.ash.drishti.lakeconfig.LakeFiles}
 *       copies it into a lake file once, and migrates old connector files (L4).</li>
 *   <li>{@link com.ash.drishti.lakeconfig.LakeCheck} compares a lake file with the tables on disk.</li>
 * </ul>
 */
package com.ash.drishti.lakeconfig;
