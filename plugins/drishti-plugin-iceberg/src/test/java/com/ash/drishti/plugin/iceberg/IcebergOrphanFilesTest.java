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
package com.ash.drishti.plugin.iceberg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.testkit.DatedSourceContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Orphan files ({@link OrphanFiles}) and the retention floor of {@link IcebergMaintenance}: a file no snapshot references and older
 * than the minimum age goes, every referenced file stays however old, a young file stays (a load may still be committing), a dry run
 * deletes nothing, an age under 72 hours is refused; keep-days never drops a date within the floor and expiry keeps the floor's time
 * travel; the JSON report says before and after.
 */
class IcebergOrphanFilesTest {

    static final LocalDate AS_OF = LocalDate.of(2026, 10, 23);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    private Path table(Path root) {
        return root.resolve("risk").resolve(VectorLake.KIND);
    }

    /** Every file of the table made {@code hours} old. */
    private static void age(Path dir, long hours) throws Exception {
        FileTime old = FileTime.from(Instant.now().minus(hours, ChronoUnit.HOURS));
        try (var files = Files.walk(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Files.setLastModifiedTime(f, old);
            }
        }
    }

    private static Path plant(Path file, long hoursOld) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "left by a writer that died before its commit");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(hoursOld, ChronoUnit.HOURS)));
        return file;
    }

    private Path loaded(String engine) throws Exception {
        Path root = tmp.resolve("lake-" + engine);
        Map<LocalDate, List<VectorLake.Pos>> days = new TreeMap<>();
        for (int i = 0; i < 6; i++) {
            days.put(AS_OF.minusDays(i), VectorLake.positions(8, i));
        }
        VectorLake.loadIceberg(tmp, root, engine, days);
        return root;
    }

    private static com.ash.drishti.iceberg.Table nativeTable(Path root) {
        LayoutLake lake = LayoutLake.of(Map.of("root", root.toString(), "domain", "risk", "engine", "native"));
        return ((LayoutLake.Native.Kind) lake.load(VectorLake.KIND).orElseThrow()).t();
    }

    @ParameterizedTest
    @ValueSource(strings = {"native", "library"})
    void onlyOldUnreferencedFilesGoAndTheTableStillReadsWhole(String engine) throws Exception {
        Path root = loaded(engine);
        age(table(root), 1000);                                   // every referenced file is old: only references keep them
        Path deadData = plant(table(root).resolve("data").resolve("business_date=" + AS_OF).resolve("00000-9-dead.parquet"), 100);
        Path deadManifest = plant(table(root).resolve("metadata").resolve("dead-m0.avro"), 100);
        Path young = plant(table(root).resolve("data").resolve("business_date=" + AS_OF).resolve("00000-9-young.parquet"), 2);
        var t = nativeTable(root);
        List<OrphanFiles.Orphan> found = OrphanFiles.find(t, 72, System.currentTimeMillis());
        assertThat(found).extracting(o -> Path.of(java.net.URI.create(o.path())).getFileName().toString())
                .containsExactly("00000-9-dead.parquet", "dead-m0.avro");
        assertThat(OrphanFiles.remove(t, found)).isEqualTo(2);
        assertThat(deadData).doesNotExist();
        assertThat(deadManifest).doesNotExist();
        assertThat(young).exists();

        IcebergSourcePlugin p = new IcebergSourcePlugin();
        p.start(DatedSourceContract.context(VectorLake.settings(root, engine, tmp.resolve("cache"))));
        try {
            assertThat(p.inventory(VectorLake.KIND, 10)).hasSize(6).allSatisfy((d, n) -> assertThat(n).isEqualTo(8L));
            int[] rows = {0};
            assertThat(p.vectors(VectorLake.KIND, "PNL", List.of(), AsOf.of(AS_OF.minusDays(5)), (id, v, n, x) -> rows[0]++)).isTrue();
            assertThat(rows[0]).isEqualTo(8);
        } finally {
            p.close();
        }
    }

    @Test
    void anAgeUnderSeventyTwoHoursIsRefused() throws Exception {
        var t = nativeTable(loaded("native"));
        assertThatThrownBy(() -> OrphanFiles.find(t, 24, System.currentTimeMillis())).hasMessageContaining("under 72");
    }

    private JsonNode run(String... args) throws Exception {
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            IcebergMaintenance.main(args);
        } finally {
            System.setOut(was);
        }
        String[] lines = out.toString(StandardCharsets.UTF_8).strip().split("\n");
        return JSON.readTree(lines[lines.length - 1]);
    }

    @Test
    void aDryRunListsTheOrphansAndTheDatesAndDeletesNothing() throws Exception {
        Path root = loaded("native");
        Path dead = plant(table(root).resolve("data").resolve("dead.parquet"), 100);
        JsonNode r = run(root.toString(), "--domain", "risk", "--engine", "native", "--keep-days", "2", "--as-of", AS_OF.toString(),
                "--force-drop", "--remove-orphans", "--dry-run", "--json");
        assertThat(r.path("table").asText()).isEqualTo("risk/pnl-vector");
        assertThat(r.path("dryRun").asBoolean()).isTrue();
        assertThat(r.path("orphans").path("wouldRemove").asInt()).isEqualTo(1);
        assertThat(r.path("keepDays").path("wouldRemove")).hasSize(4);
        assertThat(r.path("before").path("dates").asInt()).isEqualTo(6);
        assertThat(r.path("after").path("dates").asInt()).isEqualTo(6);
        assertThat(dead).exists();

        JsonNode done = run(root.toString(), "--domain", "risk", "--engine", "native", "--keep-days", "2", "--as-of", AS_OF.toString(),
                "--force-drop", "--remove-orphans", "--json");
        assertThat(done.path("orphans").path("removed").asInt()).isEqualTo(1);
        assertThat(done.path("keepDays").path("removed").asInt()).isEqualTo(4);
        assertThat(done.path("after").path("dates").asInt()).isEqualTo(2);
        assertThat(done.path("after").path("latestDate").asText()).isEqualTo(AS_OF.toString());
        assertThat(dead).doesNotExist();
    }

    /** The floor: keep-days 2 with a 4-day floor keeps the 4 newest days (the 2 asked are raised), and expiry keeps 4 days of snapshots. */
    @ParameterizedTest
    @ValueSource(strings = {"native", "library"})
    void theRetentionFloorKeepsItsDatesAndItsTimeTravel(String engine) throws Exception {
        Path root = loaded(engine);
        JsonNode r = run(root.toString(), "--domain", "risk", "--engine", engine, "--keep-days", "2", "--floor-days", "4", "--as-of",
                AS_OF.toString(), "--force-drop", "--expire-hours", "0", "--json");
        assertThat(r.path("floor").path("keepsDatesFrom").asText()).isEqualTo(AS_OF.minusDays(3).toString());
        assertThat(r.path("floor").path("expireHours").asLong()).isEqualTo(96);
        assertThat(r.path("keepDays").path("removed").asInt()).isEqualTo(2);
        assertThat(r.path("after").path("dates").asInt()).isEqualTo(4);
        // the snapshots of the load are minutes old: inside the floor, none is expired although --expire-hours 0 asked for it
        assertThat(r.path("after").path("snapshots").asInt()).isGreaterThan(1);
        try (LayoutLake lake = LayoutLake.of(Map.of("root", root.toString(), "domain", "risk", "engine", engine))) {
            assertThat(lake.load(VectorLake.KIND).orElseThrow().keepDays(1, com.ash.drishti.api.LoadGuard.fromArgs(
                    new String[] {"--as-of", AS_OF.toString(), "--force-drop"}), AS_OF.minusDays(3))).isZero();
        }
    }
}
