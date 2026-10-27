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
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.plugin.iceberg.VectorLake.Pos;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drishti's own Iceberg engine against the real AWS Glue Iceberg REST catalog (and S3 Tables), run only with {@code DRISHTI_GLUE_LIVE=1}
 * by {@code deploy/aws/iceberg/check-glue.sh} with credentials of the default AWS chain; skipped everywhere else (no AWS account in the
 * build). A Glue database is created with its S3 location, a table of P&amp;L vectors is written by {@link IcebergLoader} through Glue
 * ({@code catalog: rest}, SigV4 signing name {@code glue}), read back by the connector (a document, and {@code vectors()} bit for bit), then
 * the table and the database are dropped (unless {@code DRISHTI_GLUE_KEEP=1}). Each step's result goes to
 * {@code target/glue-live-report.txt}, which the script prints.
 *
 * <p>Environment: {@code DRISHTI_GLUE_REGION}, {@code DRISHTI_GLUE_CATALOG_ID} (the account), {@code DRISHTI_GLUE_WAREHOUSE} (an S3 folder
 * the database's tables go under); S3 Tables: {@code DRISHTI_S3TABLES_LIVE=1} and {@code DRISHTI_S3TABLES_ARN} (a table bucket).
 */
@EnabledIfEnvironmentVariable(named = "DRISHTI_GLUE_LIVE", matches = "1")
class GlueLiveTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 23);
    static final String KIND = "pnl_vector";                     // Glue and Athena take letters, digits and underscores
    private static final List<String> REPORT = new ArrayList<>();

    @TempDir
    Path tmp;

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** The connector's and loader's settings for a REST catalog signed with SigV4 under {@code signingName}. */
    static Map<String, String> settings(String uri, String warehouse, String signingName, String region, String namespace) {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("catalog", "rest");
        s.put("uri", uri);
        s.put("warehouse", warehouse);
        s.put("domain", namespace);
        s.put("engine", "native");
        s.put("catalog.rest.sigv4-enabled", "true");
        s.put("catalog.rest.signing-name", signingName);
        s.put("catalog.rest.signing-region", region);
        s.put("s3.region", region);
        return s;
    }

    private static RESTCatalog library(Map<String, String> s) {
        Map<String, String> props = new HashMap<>();
        props.put("uri", s.get("uri"));
        props.put("warehouse", s.get("warehouse"));
        props.put("rest.sigv4-enabled", "true");
        props.put("rest.signing-name", s.get("catalog.rest.signing-name"));
        props.put("rest.signing-region", s.get("catalog.rest.signing-region"));
        props.put("io-impl", "org.apache.iceberg.aws.s3.S3FileIO");
        props.put("client.region", s.get("s3.region"));
        RESTCatalog c = new RESTCatalog();
        c.initialize("drishti-live", props);
        return c;
    }

    private static void note(String what) {
        REPORT.add(Instant.now() + "  " + what);
    }

    @AfterAll
    static void report() throws Exception {
        Path out = Path.of("target", "glue-live-report.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, REPORT, StandardCharsets.UTF_8);
    }

    /** Creates, writes, reads back and drops; {@code location} is the namespace's S3 folder (null: the catalog chooses). */
    private void roundTrip(String label, Map<String, String> s, String location) throws Exception {
        String ns = s.get("domain");
        RESTCatalog lib = library(s);
        try {
            Map<String, String> nsProps = location == null ? Map.of() : Map.of("location", location);
            lib.createNamespace(Namespace.of(ns), nsProps);
            note(label + ": namespace " + ns + " created" + (location == null ? "" : " at " + location));
            List<Pos> want = VectorLake.positions(25, 7);
            List<String> lines = new ArrayList<>();
            for (Pos p : want) {
                lines.add(VectorLake.line(p, DAY, null).replace("\"kind\":\"" + VectorLake.KIND + "\"", "\"kind\":\"" + KIND + "\"")
                        .replace("\"domain\":\"risk\"", "\"domain\":\"" + ns + "\""));
            }
            Path file = tmp.resolve(label + ".jsonl");
            Files.write(file, lines, StandardCharsets.UTF_8);
            List<String> args = new ArrayList<>(List.of(file.toString()));
            s.forEach((k, v) -> {
                if (!k.equals("domain")) {
                    args.add("--set");
                    args.add(k + "=" + v);
                }
            });
            args.addAll(List.of("--spill-dir", tmp.toString()));
            IcebergLoader.load(args.toArray(new String[0]));
            note(label + ": " + want.size() + " rows written by IcebergLoader (engine native) and committed through the catalog");

            Map<String, String> read = new HashMap<>(s);
            read.put("source-name", "glue-live");
            read.put("kinds", KIND);
            read.put("layout." + KIND + ".columns", "DESK,TT_BOOK,QUANTITY");
            read.put("layout." + KIND + ".vectors", "PNL");
            read.put("id-map-disk.dir", tmp.resolve("idmaps").toString());
            read.put("doc-disk-cache.enabled", "false");
            IcebergSourcePlugin p = new IcebergSourcePlugin();
            p.start(DatedSourceContract.context(read));
            try {
                Map<String, double[]> got = new LinkedHashMap<>();
                assertThat(p.vectors(KIND, "PNL", List.of("TT_BOOK"), AsOf.of(DAY),
                        (id, v, n, t) -> got.put(id, n < 0 ? null : Arrays.copyOf(v, n)))).isTrue();
                for (Pos w : want) {
                    if (w.pnl() == null) {
                        assertThat(got.get(w.id())).isNull();
                    } else {
                        assertThat(Arrays.stream(got.get(w.id())).mapToLong(Double::doubleToRawLongBits).toArray())
                                .isEqualTo(Arrays.stream(w.pnl()).mapToLong(Double::doubleToRawLongBits).toArray());
                    }
                }
                note(label + ": vectors() read back bit for bit (" + got.size() + " rows)");
                var doc = p.fetch(EntityRef.of(KIND, want.get(3).id()), AsOf.of(DAY)).orElseThrow().data();
                assertThat(doc.get("PNL").size()).isEqualTo(VectorLake.N);
                note(label + ": a document read with its vector put back; table identity " + p.tableIdentity(KIND).orElse("?"));
            } finally {
                p.close();
            }
        } catch (Exception | AssertionError e) {
            note(label + ": FAILED: " + e);
            throw e;
        } finally {
            if (!"1".equals(System.getenv("DRISHTI_GLUE_KEEP"))) {
                try {
                    lib.dropTable(TableIdentifier.of(ns, KIND), true);
                    lib.dropNamespace(Namespace.of(ns));
                    note(label + ": table and namespace dropped");
                } catch (RuntimeException e) {
                    note(label + ": the clean-up failed (drop " + ns + "." + KIND + " by hand): " + e);
                }
            }
            lib.close();
        }
    }

    @Test
    void glueCreatesWritesReadsAndDrops() throws Exception {
        String region = env("DRISHTI_GLUE_REGION", env("AWS_REGION", "us-east-1"));
        String ns = "drishti_check_" + Long.toString(System.currentTimeMillis(), 36);
        Map<String, String> s = settings("https://glue." + region + ".amazonaws.com/iceberg", env("DRISHTI_GLUE_CATALOG_ID", ""), "glue", region, ns);
        roundTrip("glue", s, env("DRISHTI_GLUE_WAREHOUSE", "") .isBlank() ? null : env("DRISHTI_GLUE_WAREHOUSE", "") + "/" + ns);
    }

    /** S3 Tables' Iceberg REST endpoint (signing name s3tables, the warehouse a table bucket ARN); the catalog places the tables itself. */
    @Test
    @EnabledIfEnvironmentVariable(named = "DRISHTI_S3TABLES_LIVE", matches = "1")
    void s3TablesCreatesWritesReadsAndDrops() throws Exception {
        String region = env("DRISHTI_GLUE_REGION", env("AWS_REGION", "us-east-1"));
        String ns = "drishti_check_" + Long.toString(System.currentTimeMillis(), 36);
        Map<String, String> s = settings("https://s3tables." + region + ".amazonaws.com/iceberg", env("DRISHTI_S3TABLES_ARN", ""), "s3tables", region, ns);
        roundTrip("s3tables", s, null);
    }
}
