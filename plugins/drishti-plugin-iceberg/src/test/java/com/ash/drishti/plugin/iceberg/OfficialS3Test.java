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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.deltalake.write.LakeManifest;
import com.ash.drishti.deltalake.write.LakeWriter;
import com.ash.drishti.plugin.delta.DeltaSourcePlugin;
import com.ash.drishti.testkit.DatedSourceContract;
import com.ash.drishti.testkit.s3.S3MockServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The official results' two writers on {@code s3://} (2.9.5; the server's OfficialSink is built on them: before, an S3 root ended in
 * {@code publishError}), on S3Mock in this JVM (no Docker, no binary): Drishti's Delta writer through drishti-lakeio
 * ({@link LakeWriter#forTable}, each commit file an exclusive create) and {@link IcebergAppender} (drishti-iceberg), each appending a
 * date twice as two separate writers (two servers), the rows read back through the connector of the format and the same in both.
 */
class OfficialS3Test {

    static final LocalDate DAY = LocalDate.of(2026, 9, 30);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static S3MockServer s3;

    @TempDir
    Path tmp;

    @BeforeAll
    static void up() throws Exception {
        s3 = S3MockServer.start(Path.of("target", "s3mock-official"), "lake");
    }

    @AfterAll
    static void down() {
        s3.close();
    }

    private static Map<String, String> storage() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("s3.endpoint", s3.endpoint().endpoint());
        s.put("s3.access-key", s3.endpoint().accessKey());
        s.put("s3.secret-key", s3.endpoint().secretKey());
        s.put("s3.region", "us-east-1");
        s.put("s3.path-style", "true");
        return s;
    }

    private static List<Map<String, Object>> rows(int version) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String node : List.of("MGMT~BANK", "LL~LL-A")) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("NODE_ID", node);
            r.put("RESULT_VERSION", (double) version);
            r.put("VAR", 540.25 * version);
            out.add(r);
        }
        return out;
    }

    private static String id(Map<String, Object> r) {
        return r.get("NODE_ID") + "@" + DAY + "#v" + ((Number) r.get("RESULT_VERSION")).intValue();
    }

    private static String doc(Map<String, Object> r) throws Exception {
        Map<String, Object> d = new LinkedHashMap<>(r);
        d.put("BUSINESS_DATE", DAY.toString());
        return JSON.writeValueAsString(d);
    }

    private long delta(String table, int version) throws Exception {
        Path staged = Files.createTempFile(tmp, "official", ".rows");
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> r : rows(version)) {
            lines.add(JSON.writeValueAsString(Map.of("id", id(r), "doc", doc(r), "columns", r)));
        }
        Files.write(staged, lines, StandardCharsets.UTF_8);
        List<LakeManifest.Column> cols = List.of(new LakeManifest.Column("id", "string"), new LakeManifest.Column("doc", "string"),
                new LakeManifest.Column("business_date", "date"), new LakeManifest.Column("NODE_ID", "string"),
                new LakeManifest.Column("RESULT_VERSION", "double"), new LakeManifest.Column("VAR", "double"));
        LakeManifest m = new LakeManifest(table, List.of("business_date"), cols, List.of("NODE_ID", "RESULT_VERSION", "VAR"), "append", 10_000,
                List.of("id", "business_date"), List.of(new LakeManifest.Part(staged, 2, Map.of("business_date", DAY.toString()))),
                Map.of("delta.appendOnly", "true"));
        return LakeWriter.forTable(table, storage()).write(m, x -> { }).version();      // a new writer each time: another server
    }

    private long iceberg(String root, int version) throws Exception {
        List<IcebergAppender.Row> out = new ArrayList<>();
        for (Map<String, Object> r : rows(version)) {
            out.add(new IcebergAppender.Row(id(r), doc(r), r));
        }
        Map<String, String> s = new HashMap<>(storage());
        s.put("root", root);
        Map<String, Boolean> promoted = new LinkedHashMap<>();
        promoted.put("NODE_ID", false);
        promoted.put("RESULT_VERSION", true);
        promoted.put("VAR", true);
        return IcebergAppender.append(s, "risk-results", "var-official", DAY, out, promoted, Set.of(),
                Map.of("write.metadata.delete-after-commit.enabled", "false"));
    }

    private Map<String, Object> read(SourcePlugin p, String root) throws Exception {
        Map<String, String> s = new HashMap<>(storage());
        s.putAll(Map.of("root", root, "domain", "risk-results", "kinds", "var-official", "engine", "native", "refresh-seconds", "3600",
                "id-map-disk.enabled", "false", "doc-disk-cache.enabled", "false", "source-name", "official-s3"));
        p.start(DatedSourceContract.context(s));
        try {
            Map<String, Object> out = new TreeMap<>();
            p.documents("var-official", AsOf.of(DAY), d -> out.put(d.ref().id(), d.data().unwrap()));
            return out;
        } finally {
            p.close();
        }
    }

    @Test
    void bothFormatsAppendToS3AndReadBackTheSameRows() throws Exception {
        long d1 = delta("s3://lake/delta-results/risk-results/var-official", 1);
        long d2 = delta("s3://lake/delta-results/risk-results/var-official", 2);
        assertThat(d2).as("the second writer committed the next version").isEqualTo(d1 + 1);
        long i1 = iceberg("s3://lake/iceberg-results", 1);
        long i2 = iceberg("s3://lake/iceberg-results", 2);
        assertThat(i2).isGreaterThan(i1);
        Map<String, Object> fromDelta = read(new DeltaSourcePlugin(), "s3://lake/delta-results");
        Map<String, Object> fromIceberg = read(new IcebergSourcePlugin(), "s3://lake/iceberg-results");
        assertThat(fromDelta).hasSize(4).containsKeys("MGMT~BANK@2026-09-30#v1", "LL~LL-A@2026-09-30#v2");
        assertThat(fromIceberg).isEqualTo(fromDelta);
        List<String> log = s3.endpoint().client().listObjectsV2(r -> r.bucket("lake").prefix("delta-results/risk-results/var-official/_delta_log/"))
                .contents().stream().map(o -> o.key()).toList();
        assertThat(log).anyMatch(k -> k.endsWith("00000000000000000001.json"));
        // a commit file is an exclusive create: a second create of a version that exists is refused (the loser retries on the next)
        var storage = new com.ash.drishti.lakeio.S3Storage(com.ash.drishti.lakeio.S3Settings.from(storage()));
        try {
            assertThat(storage.createIfAbsent("s3://lake/delta-results/risk-results/var-official/_delta_log/00000000000000000001.json",
                    "{}".getBytes(StandardCharsets.UTF_8))).isFalse();
        } finally {
            storage.close();
        }
    }
}
