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
package com.ash.drishti.dataload;

import java.util.Map;

/**
 * The Upload form's fields (DATA_LAKE_GUIDE.md section 3.5). The table is the main field: {@code pack}, {@code kind} and {@code connector} are
 * what it resolves to, sent back as the picker labelled them, and are chosen only for a new table.
 *
 * @param item the file on the desk (its id); the SHA-256 and rows always come from it, never from the form
 * @param file a file's name when none is on the desk yet (a preview only)
 * @param ackReplace the tick that a replace may remove the rows the table already holds on the date
 */
public record ReadyForm(String item, String file, String table, String pack, String kind, String connector, String lake, String businessDate, String mode,
        Boolean newTable, String key, Map<String, String> folders, Boolean hold, Boolean ackReplace) {}
