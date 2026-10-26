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
package com.ash.drishti.deltalake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ash.drishti.deltalake.write.LakeCli;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drishti's lake writer: staged rows become a partitioned Delta table the native engine reads (business date and a
 * classifier as Hive folders, the document intact), a re-run replaces only its dates (or only its partitions), append adds,
 * and the table's own partitioning is kept.
 */
class LakeWriterTest {

    @TempDir
    Path tmp;

    private Path stage(String name, int from, int count, String product, double notional) throws IOException {
        List<String> lines = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            String doc = "{\\\"TRADE_ID\\\":" + i + ",\\\"PRODUCT_TYPE\\\":\\\"" + product + "\\\",\\\"LEGS\\\":[{\\\"LEG_ID\\\":1}]}";
            lines.add("[\"" + i + "\",\"" + doc + "\",{\"NOTIONAL\":" + notional + "}]");
        }
        Path f = tmp.resolve(name);
        Files.write(f, lines, StandardCharsets.UTF_8);
        return f;
    }

    private Path manifest(Path table, String mode, String... parts) throws IOException {
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < parts.length; i += 3) {
            p.append(i == 0 ? "" : ",").append("{\"file\":\"").append(parts[i]).append("\",\"values\":{\"business_date\":\"").append(parts[i + 1])
                    .append("\",\"PRODUCT_TYPE\":\"").append(parts[i + 2]).append("\"}}");
        }
        String json = "{\"table\":\"" + table + "\",\"partitionColumns\":[\"business_date\",\"PRODUCT_TYPE\"],\"columns\":["
                + "{\"name\":\"id\",\"type\":\"string\"},{\"name\":\"doc\",\"type\":\"string\"},{\"name\":\"business_date\",\"type\":\"date\"},"
                + "{\"name\":\"PRODUCT_TYPE\",\"type\":\"string\"},{\"name\":\"NOTIONAL\",\"type\":\"double\"}],\"promoted\":[\"NOTIONAL\"],"
                + "\"mode\":\"" + mode + "\",\"batchRows\":7,\"statsColumns\":[\"id\",\"business_date\",\"PRODUCT_TYPE\",\"NOTIONAL\"],\"parts\":[" + p + "]}";
        Path m = Files.createTempFile(tmp, "manifest", ".json");
        Files.writeString(m, json);
        return m;
    }

    private int run(Path manifest, ByteArrayOutputStream out) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = LakeCli.run(new String[] {"lake", "write", "--manifest", manifest.toString()}, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        return code;
    }

    private Map<String, String> read(Path table) throws IOException {
        try (NativeEngine engine = NativeEngine.create(Map.of())) {
            return TableReader.rows(engine, table.toString(), null);
        }
    }

    @Test
    void writesPartitionsReplacesDatesOrPartitionsAndAppends() throws IOException {
        Path table = tmp.resolve("lake/trading/deal");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        run(manifest(table, "overwrite-dates",
                stage("a.rows", 0, 20, "Swap", 5.0).toString(), "2026-10-01", "Swap",
                stage("b.rows", 20, 10, "Future", 5.0).toString(), "2026-10-01", "Future",
                stage("c.rows", 30, 15, "Swap", 5.0).toString(), "2026-10-02", "Swap"), out);
        String printed = out.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("{\"partsDone\":3}") && printed.contains("\"done\":true") && printed.contains("\"rows\":45"), printed);
        assertTrue(Files.isDirectory(table.resolve("business_date=2026-10-01/PRODUCT_TYPE=Swap")));
        Map<String, String> rows = read(table);
        assertEquals(45, rows.size());
        assertTrue(rows.get("2026-10-01|3").contains("\"LEGS\":[{\"LEG_ID\":1}]"), rows.get("2026-10-01|3"));

        // a re-run of 2026-10-01 replaces that date only, in one commit
        run(manifest(table, "overwrite-dates", stage("d.rows", 100, 4, "Swap", 1.0).toString(), "2026-10-01", "Swap"), new ByteArrayOutputStream());
        rows = read(table);
        assertEquals(4 + 15, rows.size());
        assertTrue(rows.containsKey("2026-10-01|100") && !rows.containsKey("2026-10-01|0") && rows.containsKey("2026-10-02|30"));

        // overwrite-partitions: Future of 2026-10-01 is added, Swap of that day is kept
        run(manifest(table, "overwrite-partitions", stage("e.rows", 200, 3, "Future", 1.0).toString(), "2026-10-01", "Future"),
                new ByteArrayOutputStream());
        assertEquals(4 + 3 + 15, read(table).size());

        // append adds
        run(manifest(table, "append", stage("f.rows", 300, 2, "Swap", 1.0).toString(), "2026-10-02", "Swap"), new ByteArrayOutputStream());
        assertEquals(24, read(table).size());
    }

    @Test
    void aNewTableTakesTheManifestsPropertiesAndLongColumns() throws IOException {
        Path table = tmp.resolve("lake/risk-results/var-official");
        String json = Files.readString(manifest(table, "append", stage("a.rows", 0, 3, "Swap", 2.0).toString(), "2026-10-01", "Swap"))
                .replace("{\"name\":\"NOTIONAL\",\"type\":\"double\"}", "{\"name\":\"NOTIONAL\",\"type\":\"long\"}")
                .replace("\"parts\":", "\"properties\":{\"delta.logRetentionDuration\":\"interval 2557 days\","
                        + "\"delta.deletedFileRetentionDuration\":\"interval 2557 days\",\"delta.appendOnly\":\"true\"},\"parts\":");
        Path m = tmp.resolve("props.json");
        Files.writeString(m, json);
        run(m, new ByteArrayOutputStream());
        String log = Files.readString(table.resolve("_delta_log/00000000000000000000.json"));
        assertTrue(log.contains("delta.logRetentionDuration") && log.contains("interval 2557 days") && log.contains("delta.appendOnly"), log);
        assertTrue(log.contains("\\\"type\\\":\\\"long\\\""), log);
        assertEquals(3, read(table).size());
    }

    @Test
    void refusesAnotherPartitioningAndBadManifests() throws IOException {
        Path table = tmp.resolve("lake/trading/deal");
        run(manifest(table, "append", stage("a.rows", 0, 2, "Swap", 1.0).toString(), "2026-10-01", "Swap"), new ByteArrayOutputStream());
        String other = Files.readString(manifest(table, "append", stage("b.rows", 5, 1, "Swap", 1.0).toString(), "2026-10-01", "Swap"))
                .replace("\"partitionColumns\":[\"business_date\",\"PRODUCT_TYPE\"]", "\"partitionColumns\":[\"business_date\"]");
        Path m = tmp.resolve("other.json");
        Files.writeString(m, other);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = LakeCli.run(new String[] {"lake", "write", "--manifest", m.toString()}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(LakeCli.FAILED, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("is partitioned by"), err.toString(StandardCharsets.UTF_8));
        assertEquals(LakeCli.USAGE, LakeCli.run(new String[] {"lake", "write"}, System.out, new PrintStream(new ByteArrayOutputStream())));
        assertEquals(2, read(table).size());
    }
}
