package com.ash.drishti.server.bi.engine;

/**
 * The SQL differences between the embedded engine (DuckDB) and the stores a {@code direct} table can push down to (PostgreSQL). Everything else
 * the compiler writes (SELECT, JOIN, GROUP BY, FILTER, CASE, bound parameters) is standard.
 */
public enum Dialect {
    DUCKDB, POSTGRESQL;

    public static Dialect of(String name) {
        return "postgresql".equalsIgnoreCase(name) ? POSTGRESQL : DUCKDB;
    }

    public String doubleType() {
        return this == DUCKDB ? "DOUBLE" : "DOUBLE PRECISION";
    }

    public String textType() {
        return this == DUCKDB ? "VARCHAR" : "TEXT";
    }

    /** A date part of a date or timestamp expression. */
    public String part(String part, String expr) {
        if (this == DUCKDB) {
            return part + "(" + expr + ")";
        }
        return "CAST(EXTRACT(" + part.toUpperCase(java.util.Locale.ROOT) + " FROM " + expr + ") AS BIGINT)";
    }

    /** {@code percentile_cont}: DuckDB's {@code quantile_cont(x, p)}, PostgreSQL's ordered-set aggregate. The bound {@code p} follows the expression in DuckDB, precedes it in PostgreSQL. */
    public boolean percentileFirst() {
        return this == POSTGRESQL;
    }

    public String percentile(String expr, String p) {
        return this == DUCKDB ? "quantile_cont(" + expr + ", " + p + ")" : "percentile_cont(" + p + ") WITHIN GROUP (ORDER BY " + expr + ")";
    }

    /** A quoted identifier: the name is doubled for any quote in it, so no name can leave its quotes. */
    public static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
