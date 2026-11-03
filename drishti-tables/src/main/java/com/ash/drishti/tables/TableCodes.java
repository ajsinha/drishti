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
package com.ash.drishti.tables;

/**
 * The table layer's problem codes (docs/guides/TROUBLESHOOTING.md, "Virtual tables"). The language's own codes are kept where the problem
 * is the language's: {@code DRS-9127} (an aggregate in row or rule scope), {@code DRS-9152} (an unknown name), {@code DRS-9122}
 * (relationships), as BI_FOUNDATION_2911E.md section 2.1 decides.
 */
public final class TableCodes {

    /** The definition cannot be read: not YAML, an unknown key, a required key missing, a value of the wrong shape. */
    public static final String UNREADABLE = "DRS-9600";
    /** A table's or model's name breaks the naming rule ({@code <domain>.<name>}, lower case, underscores; the file's folder is the domain). */
    public static final String NAME = "DRS-9601";
    /** A built-in column ({@code id}, {@code business_date}, {@code index}, {@code update_seq}, {@code updated_at}) is declared. */
    public static final String BUILT_IN = "DRS-9602";
    /** A path is not a path: the grain, a column's path, {@code @} or {@code ^} with no element to name. */
    public static final String PATH = "DRS-9603";
    /** A column has none, or more than one, of {@code path}, {@code expr}, {@code python}. */
    public static final String COLUMN = "DRS-9604";
    /** A type the layer does not know. */
    public static final String TYPE = "DRS-9605";
    /** Keys and de-duplication: a key that is not a column, {@code keep} with no keys. */
    public static final String KEYS = "DRS-9606";
    /** Materialisation: an unknown mode, live without keys, live limits. */
    public static final String MATERIALIZE = "DRS-9607";
    /** A row rule with no role or no condition. */
    public static final String RULE = "DRS-9608";
    /** A name the catalogue does not hold: a table, a model, a column, a measure, an alias. */
    public static final String REFERENCE = "DRS-9609";
    /** Two definitions with one name, or a {@code was:} name that another definition holds. */
    public static final String DUPLICATE = "DRS-9610";
    /** Tables over tables that come back to themselves. */
    public static final String CYCLE = "DRS-9611";
    /** A check on the sample documents: keys not unique, a path no sample document holds. */
    public static final String SAMPLE = "DRS-9613";
    /** A file in BI's old form found where the layer reads ({@code rachana: 2} under {@code config/tables}). */
    public static final String OLD_FORM = "DRS-9614";

    /** The language's: an aggregate in row or rule scope, or a scope rule broken. */
    public static final String SCOPE = "DRS-9127";
    /** The language's: an unknown name in an expression. */
    public static final String UNKNOWN_NAME = "DRS-9152";
    /** BI's: a relationship's ends (unknown alias or column, key types that differ). */
    public static final String RELATIONSHIP = "DRS-9122";
    /** The migration refused an item or an expression (BI_FOUNDATION_2911E.md section 4.2). */
    public static final String MIGRATION_REFUSED = "DRS-9195";
    /** A server found a file in an old form and refused it, naming the command to run (section 4.3). */
    public static final String MIGRATE_FIRST = "DRS-9196";

    private TableCodes() {}
}
