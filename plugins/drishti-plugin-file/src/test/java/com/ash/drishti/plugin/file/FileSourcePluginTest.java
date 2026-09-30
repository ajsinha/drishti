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
package com.ash.drishti.plugin.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.common.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSourcePluginTest {

    @TempDir
    Path root;

    private FileSourcePlugin started() {
        JsonCodec codec = new JsonCodec();
        FileSourcePlugin p = new FileSourcePlugin();
        p.start(new SourceContext() {
            public Map<String, String> settings() {
                return Map.of("root", root.toString(), "source-name", "eod-futures");
            }

            public DataNode parseJson(InputStream in) throws IOException {
                return codec.read(in);
            }

            public ScheduledExecutorService scheduler() {
                return Executors.newSingleThreadScheduledExecutor();
            }
        });
        return p;
    }

    @Test
    void readsJsonAndCsvWithGenerationFromMtime() throws Exception {
        Files.createDirectories(root.resolve("trade"));
        Files.writeString(root.resolve("trade/T-1.json"), "{\"tradeId\":\"T-1\",\"mtm\":5}");
        Files.writeString(root.resolve("trade/T-2.csv"), "date,settle,note\n2026-09-30,71.15,\"a, b\"\n2026-09-29,70.62,\n");
        FileSourcePlugin p = started();
        var j = p.fetch(EntityRef.of("trade", "T-1")).orElseThrow();
        assertThat(j.data().get("mtm").asDouble()).isEqualTo(5);
        assertThat(j.provenance().source()).isEqualTo("eod-futures");
        assertThat(j.provenance().generation()).isPositive();
        var c = p.fetch(EntityRef.of("trade", "T-2")).orElseThrow().data();
        assertThat(c.at("rows[0].settle").asDouble()).isEqualTo(71.15);
        assertThat(c.at("rows[0].note").asText()).isEqualTo("a, b");
        assertThat(c.at("rows[1].note").isNull()).isTrue();
        assertThat(p.search("trade", "T-", 10)).hasSize(2);
    }

    @Test
    void refusesPathTraversal() throws Exception {
        FileSourcePlugin p = started();
        assertThat(p.resolve(EntityRef.of("trade", "../../etc/passwd"), ".json")).isNull();
        assertThat(p.fetch(EntityRef.of("..", "x"))).isEmpty();
    }
}
