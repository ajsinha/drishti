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
package com.ash.drishti.lakeload;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

/**
 * Synthetic scenario-PV positions as a bank's risk system exports them (JSON Lines, one position a line): Endur-style UPPER_SNAKE fields,
 * a legal entity, a desk and a book, a few numbers (whole and fractional), nested legs, text with escapes and non-ASCII, and a
 * {@code SCENARIO_PV} vector of {@code scenarios} numbers with an occasional null (a scenario that did not price). Deterministic.
 */
final class PvData {

    static final String[] ENTITIES = {"LE-NY", "LE-LDN", "LE-TKY"};
    static final String[] DESKS = {"DESK-RATES", "DESK-FX", "DESK-COMM", "DESK-CREDIT"};
    static final String[] DATES = {"2026-10-27", "2026-10-28"};

    private PvData() {}

    /** {@code n} lines; position {@code i} is on date {@code DATES[i % dates]}. */
    static List<String> lines(int n, int scenarios, int dates, long seed) {
        Random r = new Random(seed);
        List<String> out = new ArrayList<>(n);
        StringBuilder b = new StringBuilder(scenarios * 12 + 600);
        for (int i = 0; i < n; i++) {
            b.setLength(0);
            String le = ENTITIES[r.nextInt(ENTITIES.length)];
            String desk = DESKS[r.nextInt(DESKS.length)];
            String date = DATES[i % dates];
            b.append("{\"POSITION_ID\":\"P-").append(1_000_000 + i).append('"');
            b.append(",\"BUSINESS_DATE\":\"").append(date).append('"');
            b.append(",\"LEGAL_ENTITY\":\"").append(le).append("\",\"DESK\":\"").append(desk).append('"');
            b.append(",\"TT_BOOK\":\"BOOK-").append(desk.substring(5)).append('-').append(r.nextInt(5)).append('"');
            b.append(",\"TRADE_ID\":").append(2_000_000 + i);                                      // a number id: text in the column
            b.append(",\"QUANTITY\":").append(r.nextInt(2000) - 1000);
            b.append(",\"BASE_PV\":").append(Math.round(r.nextGaussian() * 1e7) / 100.0);
            b.append(",\"CCY\":\"").append(i % 7 == 0 ? "EUR" : "USD").append('"');
            b.append(",\"COMMENT\":\"").append(i % 11 == 0 ? "caf\\u00e9 \\\"quoted\\\" line\\nnext – ü" : "").append('"');
            b.append(",\"LEGS\":[{\"LEG\":1,\"RATE\":").append(r.nextInt(500) / 10000.0).append(",\"FLAGS\":[true,false,null]},{\"LEG\":2,\"NOTIONAL\":1.0E7}]");
            b.append(",\"SCENARIO_PV\":");
            if (i % 97 == 5) {
                b.append("null");
            } else {
                b.append('[');
                double base = r.nextGaussian() * 1e6;
                for (int s = 0; s < scenarios; s++) {
                    if (s > 0) {
                        b.append(',');
                    }
                    if ((i + s) % 211 == 0) {
                        b.append("null");                                                    // a scenario that did not price
                    } else if (s % 50 == 0) {
                        b.append(Math.round(base + r.nextGaussian() * 1e4));                 // a whole number written without .0
                    } else {
                        b.append(Math.round((base + r.nextGaussian() * 1e4) * 100) / 100.0);
                    }
                }
                b.append(']');
            }
            b.append(",\"TINY\":").append(i % 13 == 0 ? "1e-07" : "0.5").append('}');
            out.add(b.toString());
        }
        return out;
    }

    static Path write(Path file, List<String> lines) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    /** {@code lines} gzipped, as one member or (members &gt; 1) as several members one after another. */
    static Path gzip(Path file, List<String> lines, int members) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            int per = (lines.size() + members - 1) / members;
            for (int m = 0; m < members; m++) {
                GZIPOutputStream gz = new GZIPOutputStream(new NonClosing(out), 1 << 16);
                for (String l : lines.subList(Math.min(lines.size(), m * per), Math.min(lines.size(), (m + 1) * per))) {
                    gz.write((l + "\n").getBytes(StandardCharsets.UTF_8));
                }
                gz.finish();
                gz.close();
            }
        }
        return file;
    }

    /** {@code lines} as zstd. */
    static Path zstd(Path file, List<String> lines) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = new com.github.luben.zstd.ZstdOutputStream(Files.newOutputStream(file))) {
            for (String l : lines) {
                out.write((l + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        return file;
    }

    private static final class NonClosing extends java.io.FilterOutputStream {
        NonClosing(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    /** The YAML of a load of {@code files} into {@code lake} (risk/scenario-pv) as {@code format}, plus {@code extra} lines. */
    static String config(String files, String lake, String format, String... extra) {
        StringBuilder y = new StringBuilder();
        y.append("source:\n  files: '").append(files).append("'\n  id: POSITION_ID\n  date: BUSINESS_DATE\n")
                .append("  promote: [LEGAL_ENTITY, DESK, TT_BOOK, TRADE_ID, QUANTITY, BASE_PV, CCY, POSITION_ID]\n  vectors: [SCENARIO_PV]\n");
        y.append("target:\n  format: ").append(format).append("\n  lake: '").append(lake).append("'\n  domain: risk\n  kind: scenario-pv\n");
        for (String e : extra) {
            y.append(e).append('\n');
        }
        return y.toString();
    }
}
