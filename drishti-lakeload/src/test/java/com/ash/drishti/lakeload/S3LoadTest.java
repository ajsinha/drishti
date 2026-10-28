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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.testkit.s3.S3Endpoint;
import com.ash.drishti.testkit.s3.S3MockServer;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;

/**
 * S3 in and out, on Adobe S3Mock in this JVM (no Docker, runs on Windows too): the input read from {@code s3://} (a glob of gzip files,
 * and one plain file read as byte ranges with ranged GETs), the table written to {@code s3://} as Delta and as Iceberg; the tables equal
 * the same load done on local disk. Credentials come from the AWS default chain (here its system properties), never from the file.
 */
class S3LoadTest {

    @TempDir
    Path tmp;

    @Test
    void readsFromS3AndWritesDeltaAndIcebergToS3() throws Exception {
        S3MockServer s3;
        try {
            s3 = S3MockServer.start(tmp.resolve("s3mock"), "input", "lake");
        } catch (Exception | LinkageError e) {
            assumeTrue(false, "S3Mock does not start here: " + e);
            return;
        }
        String oldKey = System.getProperty("aws.accessKeyId");
        String oldSecret = System.getProperty("aws.secretAccessKey");
        try (s3) {
            S3Endpoint e = s3.endpoint();
            System.setProperty("aws.accessKeyId", e.accessKey());
            System.setProperty("aws.secretAccessKey", e.secretKey());
            List<String> lines = PvData.lines(900, 50, 2, 21);
            Path local = tmp.resolve("local");
            for (int i = 0; i < 3; i++) {
                PvData.gzip(local.resolve("pv-" + i + ".jsonl.gz"), lines.subList(i * 300, (i + 1) * 300), 1);
            }
            Path plain = PvData.write(local.resolve("plain/pv.jsonl"), lines);
            try (var c = e.client()) {
                for (int i = 0; i < 3; i++) {
                    String name = "pv-" + i + ".jsonl.gz";
                    c.putObject(b -> b.bucket("input").key("pv/2026-10-28/" + name), RequestBody.fromFile(local.resolve(name)));
                }
                c.putObject(b -> b.bucket("input").key("plain/pv.jsonl"), RequestBody.fromFile(plain));
            }
            String storage = "{region: us-east-1, endpoint: '" + e.endpoint() + "', path-style: true}";
            Map<String, String> read = Map.of("s3.endpoint", e.endpoint(), "s3.region", "us-east-1", "s3.path-style", "true", "s3.access-key",
                    e.accessKey(), "s3.secret-key", e.secretKey());
            for (String format : List.of("delta", "iceberg")) {
                JsonNode want = LakeLoadTest.load(tmp, PvData.config(plain.toString(), tmp.resolve("lake-" + format).toString(), format));
                assertThat(want.path("exit").asInt()).isZero();
                Map<String, Map<String, Object>> expected = LakeLoadTest.dump(format, tmp.resolve("lake-" + format));
                for (String[] variant : List.of(new String[] {"s3://input/pv/2026-10-28/*.jsonl.gz", "gz"},
                        new String[] {"s3://input/plain/pv.jsonl", "ranges"})) {
                    String lake = "s3://lake/" + format + "-" + variant[1];
                    JsonNode s = LakeLoadTest.load(tmp, PvData.config(variant[0], lake, format), "source.storage-options=" + storage,
                            "parallel.split-mb=0.05", "parallel.readers=4");
                    assertThat(s.path("exit").asInt()).as(variant[0] + " -> " + lake + ": " + s.path("error").asText() + s.path("said").asText())
                            .isZero();
                    assertThat(s.path("accepted").asLong()).isEqualTo(900);
                    Map<String, Map<String, Object>> got = format.equals("delta") ? TableDump.delta(lake + "/risk/scenario-pv", read)
                            : TableDump.iceberg(lake, "risk", "scenario-pv", read);
                    assertThat(got).as(variant[0] + " -> " + lake).isEqualTo(expected);
                }
            }
            assertThat(Files.exists(tmp.resolve("local"))).isTrue();
        } finally {
            restore("aws.accessKeyId", oldKey);
            restore("aws.secretAccessKey", oldSecret);
        }
    }

    @Test
    void keysInTheFileAreRefused() throws Exception {
        JsonNode s = LakeLoadTest.load(tmp, PvData.config("s3://input/x.jsonl", "s3://lake/x", "delta") + "  storage-options: {access-key: AKIA}\n");
        assertThat(s.path("exit").asInt()).isEqualTo(1);
    }

    private static void restore(String k, String v) {
        if (v == null) {
            System.clearProperty(k);
        } else {
            System.setProperty(k, v);
        }
    }
}
