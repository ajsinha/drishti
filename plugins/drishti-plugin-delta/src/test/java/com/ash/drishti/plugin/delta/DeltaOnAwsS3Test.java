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
package com.ash.drishti.plugin.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.lakeconfig.ConnectorLake;
import com.ash.drishti.lakeconfig.Lake;
import com.ash.drishti.lakeconfig.Lakes;
import com.ash.drishti.testkit.DatedSourceContract;
import com.ash.drishti.testkit.s3.S3Endpoint;
import com.ash.drishti.testkit.s3.S3MockServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The Delta Lake on S3 connector as {@code config/connectors.examples/delta-s3/} ships it: {@code root: s3://<bucket>/<prefix>} (not
 * {@code s3a://}), {@code engine: native}, {@code s3.region}, and no keys in the file, so the credentials come from the AWS default chain
 * (here its system properties; on ECS the task role). The fixture lake is uploaded to S3Mock in this JVM (no Docker) and the dated
 * contract runs against it; only {@code s3.endpoint}, the stand-in's address, is added to the template's settings. The live run against
 * AWS is {@code deploy/aws/delta-s3/delta_s3.py check}.
 */
class DeltaOnAwsS3Test extends DatedSourceContract {

    @TempDir
    static Path tmp;
    static S3MockServer s3;
    static DeltaSourcePlugin plugin;
    static String oldKey;
    static String oldSecret;

    /**
     * The settings the plugin is given for the shipped template, {@code ${VAR:default}} resolved from {@code vars} (else the default): the
     * connector file names lake {@code s3}, and its root, storage and tables come from the shipped lake file
     * ({@code config/lakes.examples/delta-s3/s3.yaml}), expanded as the server's source registry expands them.
     */
    static Map<String, String> template(Map<String, String> vars) throws Exception {
        Map<String, String> own = connectorSettings(vars);
        Lake lake = Lakes.parse(Files.readString(Path.of("../../config/lakes.examples/delta-s3/s3.yaml")), "s3", vars::get);
        return new HashMap<>(ConnectorLake.expand("risk-store", "delta", List.of(), own, () -> Lakes.of(lake), w -> { }));
    }

    /** The connector file's own settings, {@code ${VAR:default}} resolved from {@code vars} (else the default). */
    static Map<String, String> connectorSettings(Map<String, String> vars) throws Exception {
        Path file = Path.of("../../config/connectors.examples/delta-s3/risk-store.yaml");
        Map<String, String> out = new HashMap<>();
        boolean in = false;
        for (String line : Files.readAllLines(file)) {
            if (line.startsWith("settings:")) {
                in = true;
                continue;
            }
            if (in && line.startsWith("  ") && !line.trim().startsWith("#") && line.contains(": ")) {
                String k = line.trim().substring(0, line.trim().indexOf(": "));
                String v = line.trim().substring(line.trim().indexOf(": ") + 2).replaceAll("\\s+#.*$", "");
                Matcher m = Pattern.compile("\\$\\{([A-Za-z0-9_.]+):([^}]*)}").matcher(v);
                StringBuilder sb = new StringBuilder();
                while (m.find()) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(vars.getOrDefault(m.group(1), m.group(2))));
                }
                m.appendTail(sb);
                out.put(k, sb.toString());
            } else if (in && !line.startsWith(" ") && !line.isBlank()) {
                in = false;
            }
        }
        return out;
    }

    @Test
    void theTemplateNamesAnS3RootTheNativeEngineAndNoKeys() throws Exception {
        Map<String, String> s = template(Map.of());
        assertThat(s.get("root")).isEqualTo("s3://drishti-lake/delta");
        assertThat(s).containsEntry("engine", "native").containsEntry("domain", "risk").containsKey("s3.region");
        assertThat(s.keySet()).noneMatch(k -> k.contains("access-key") || k.contains("secret"));
        assertThat(connectorSettings(Map.of())).containsEntry("lake", "s3").doesNotContainKey("root");   // the lake file holds the root
        assertThat(template(Map.of("DRISHTI_DELTA_S3_BUCKET", "acme-lake", "DRISHTI_DELTA_S3_PREFIX", "lake/delta")).get("root"))
                .isEqualTo("s3://acme-lake/lake/delta");
    }

    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        if (plugin != null) {
            return plugin;
        }
        try {
            s3 = S3MockServer.start(tmp.resolve("s3mock"), "drishti-lake");
        } catch (Exception | LinkageError e) {
            assumeTrue(false, "S3Mock does not start here: " + e);
        }
        S3Endpoint e = s3.endpoint();
        try (S3Client c = e.client()) {
            Path src = Path.of("src/test/resources/lake");
            try (var files = Files.walk(src)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    c.putObject(b -> b.bucket("drishti-lake").key("delta/" + src.relativize(f).toString().replace('\\', '/')), RequestBody.fromFile(f));
                }
            }
        }
        oldKey = System.getProperty("aws.accessKeyId");
        oldSecret = System.getProperty("aws.secretAccessKey");
        System.setProperty("aws.accessKeyId", e.accessKey());              // the default chain, as a task role would give them
        System.setProperty("aws.secretAccessKey", e.secretKey());
        Map<String, String> s = template(Map.of("AWS_REGION", "us-east-1"));
        s.keySet().removeIf(k -> k.startsWith("layout."));                 // the risk kinds' layouts: the fixture lake has its own tables
        s.put("domain", "desk");                                            // the fixture's domain in place of the risk one
        s.put("mode.counterparty", "effective");
        s.put("source-name", "delta-s3");
        s.put("s3.endpoint", e.endpoint());                                 // the stand-in's address: the only setting not from the template
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(context(s));
        plugin = p;
        return p;
    }

    @AfterAll
    static void stop() {
        if (plugin != null) {
            plugin.close();
        }
        if (s3 != null) {
            s3.close();
        }
        restore("aws.accessKeyId", oldKey);
        restore("aws.secretAccessKey", oldSecret);
    }

    private static void restore(String key, String old) {
        if (old == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, old);
        }
    }
}
