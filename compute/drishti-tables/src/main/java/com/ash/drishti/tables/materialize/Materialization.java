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
package com.ash.drishti.tables.materialize;

import com.ash.drishti.tables.def.TableDef;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The layer's materialisation (HOW_IT_FITS.md §A28 section 3.4; HOW_IT_FITS.md section A6 as amended by Q14): how a table's rows are
 * kept, behind one API every consumer asks. BI's cached, rollup and live layers move behind it in the switch (section 7.3, step 5):
 *
 * <table>
 *   <caption>What moves where</caption>
 *   <tr><th>BI today</th><th>Behind</th></tr>
 *   <tr><td>{@code Layers}, {@code TableBuilder}, {@code DatasetRefresher} (the cached layer)</td><td>{@link OnLoad}</td></tr>
 *   <tr><td>{@code LiveTables}, {@code LiveTable}, {@code LiveFeed}, {@code LiveHistory}</td><td>{@link Live}</td></tr>
 *   <tr><td>{@code direct} storage</td><td>{@code materialize: none} (the compiler's route)</td></tr>
 *   <tr><td>rollups (BI's and the pivot's P3)</td><td>the model's {@code rollups:}, the engine's partial states</td></tr>
 * </table>
 *
 * <p>Those classes read BI's {@code Dataset} and DEL's {@code Node} trees today, so they move with the switch that makes BI read the layer's
 * definitions; {@link FileSwap} (no BI type) has moved already. The answer's {@code meta.layer} and {@code meta.rollup} keep their names.
 */
public interface Materialization {

    /** The mode a table asks for ({@code none}, {@code on-load}, {@code live}). */
    static String mode(TableDef t) {
        return t.materialize().mode();
    }

    /** One business date's built partition of an on-load table. */
    record Partition(String table, int major, LocalDate date, Path file, long rows, Instant builtAt, String sourceVersion) {}

    /** On-load: Parquet per business date, rebuilt when the source's data-load signal arrives. */
    interface OnLoad extends Materialization {

        /** The partition queries read now, if one is built. */
        Optional<Partition> current(TableDef t, LocalDate date);

        /** The source of {@code t} was loaded for {@code date}: rebuild (in the background, bounded by the build pool). */
        void loaded(TableDef t, LocalDate date);

        /** Builds now and returns the new partition (the command line's {@code tables refresh}, planned). */
        Partition build(TableDef t, LocalDate date);

        /** The dates held, oldest first. */
        List<LocalDate> held(TableDef t);
    }

    /** Live: in memory by key, maintained from what the source pushes, bounded by rows and age. */
    interface Live extends Materialization {

        /** How many rows the table holds now. */
        long rows(TableDef t);

        /** The highest {@code update_seq} applied (a reader resumes after it). */
        long sequence(TableDef t);
    }

    /**
     * A live table's bounds, read from its definition ({@code materialize: { live: { rows, age } }}).
     *
     * @param rows the most rows kept, or {@link Integer#MAX_VALUE}
     * @param age the oldest row kept, or null for no age limit
     */
    record LiveBounds(int rows, Duration age) {

        /** The bounds of {@code t}; {@code dfltRows} when it gives none. */
        public static LiveBounds of(TableDef t, int dfltRows) {
            TableDef.Materialize m = t.materialize();
            return new LiveBounds(m.rows() == null ? dfltRows : m.rows(), age(m.age()));
        }

        /** {@code 30s}, {@code 15m}, {@code 2h}, {@code 1d} or ISO-8601 ({@code PT2H}); null for none. */
        public static Duration age(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            String s = text.strip();
            if (s.matches("\\d+[smhd]")) {
                long n = Long.parseLong(s.substring(0, s.length() - 1));
                return switch (s.charAt(s.length() - 1)) {
                    case 's' -> Duration.ofSeconds(n);
                    case 'm' -> Duration.ofMinutes(n);
                    case 'h' -> Duration.ofHours(n);
                    default -> Duration.ofDays(n);
                };
            }
            return Duration.parse(s);
        }
    }
}
