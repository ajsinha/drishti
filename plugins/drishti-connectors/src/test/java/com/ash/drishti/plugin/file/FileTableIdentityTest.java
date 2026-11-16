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
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.common.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The file connector's table identity (Drishti*net's replicas and shards): its folder and the fingerprint of the kind's files. */
class FileTableIdentityTest {

    @TempDir
    Path dir;

    private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

    @AfterEach
    void stop() {
        schedulers.forEach(ScheduledExecutorService::shutdownNow);
    }

    private FileSourcePlugin started(Path root) {
        JsonCodec codec = new JsonCodec();
        FileSourcePlugin p = new FileSourcePlugin();
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor();
        schedulers.add(s);
        p.start(new SourceContext() {
            public Map<String, String> settings() {
                return Map.of("root", root.toString(), "rescan-seconds", "3600");
            }

            public DataNode parseJson(InputStream in) throws IOException {
                return codec.read(in);
            }

            public ScheduledExecutorService scheduler() {
                return s;
            }
        });
        return p;
    }

    private static void lake(Path root) throws IOException {
        Files.createDirectories(root.resolve("2026-10-16"));
        Files.createDirectories(root.resolve("2026-10-15"));
        Files.createDirectories(root.resolve("netting-set"));
        write(root.resolve("2026-10-16/trade.jsonl"), "{\"id\":\"T-1\",\"notional\":1}\n{\"id\":\"T-2\",\"notional\":2}\n");
        write(root.resolve("2026-10-15/trade.jsonl"), "{\"id\":\"T-1\",\"notional\":1}\n");
        write(root.resolve("netting-set/NS-1.json"), "{\"id\":\"NS-1\"}");
    }

    private static void write(Path f, String text) throws IOException {
        Files.writeString(f, text);
        Files.setLastModifiedTime(f, FileTime.fromMillis(1_760_000_000_000L));
    }

    @Test
    void twoReadersOfOneFolderSayTheSameAndEachKindHasItsOwn() throws Exception {
        Path lake = dir.resolve("lake");
        lake(lake);
        FileSourcePlugin a = started(lake);
        FileSourcePlugin b = started(lake);
        assertThat(a.tableIdentity("trade")).isPresent().isEqualTo(b.tableIdentity("trade"));
        assertThat(a.tableIdentity("trade").orElseThrow()).startsWith("file:" + lake.toAbsolutePath().normalize().toString().replace('\\', '/') + "#");
        assertThat(a.tableIdentity("netting-set")).isPresent().isEqualTo(b.tableIdentity("netting-set")).isNotEqualTo(a.tableIdentity("trade"));
        assertThat(a.tableIdentity("counterparty")).as("no file of the kind: no identity (a shard, always asked)").isEmpty();
    }

    @Test
    void filesThatDifferMakeAnotherIdentityAndARescanSeesIt() throws Exception {
        Path lake = dir.resolve("lake");
        lake(lake);
        FileSourcePlugin a = started(lake);
        String before = a.tableIdentity("trade").orElseThrow();
        String nettingBefore = a.tableIdentity("netting-set").orElseThrow();
        write(lake.resolve("2026-10-16/trade.jsonl"), "{\"id\":\"T-1\",\"notional\":1}\n{\"id\":\"T-2\",\"notional\":2}\n{\"id\":\"T-3\"}\n");
        a.rescan();
        assertThat(a.tableIdentity("trade").orElseThrow()).isNotEqualTo(before);
        assertThat(a.tableIdentity("netting-set")).as("another kind's files did not change").contains(nettingBefore);
        // another folder with the same files is another table (its folder is in the identity)
        Path other = dir.resolve("other");
        lake(other);
        assertThat(started(other).tableIdentity("netting-set").orElseThrow()).isNotEqualTo(nettingBefore);
    }

    @Test
    void onlyAKindsOwnFilesCount() {
        assertThat(FileSet.kindOf(Path.of("trade.jsonl"))).isEqualTo("trade");
        assertThat(FileSet.kindOf(Path.of("2026-10-16", "trade.jsonl"))).isEqualTo("trade");
        assertThat(FileSet.kindOf(Path.of("trade", "T-1.json"))).isEqualTo("trade");
        assertThat(FileSet.kindOf(Path.of("2026-10-16", "trade", "T-1.csv"))).isEqualTo("trade");
        assertThat(FileSet.kindOf(Path.of("README.md"))).isNull();
        assertThat(FileSet.kindOf(Path.of("2026-10-16", "notes.txt"))).isNull();
        assertThat(FileSet.kindOf(Path.of("trade", "deep", "T-1.json"))).isNull();
    }
}
