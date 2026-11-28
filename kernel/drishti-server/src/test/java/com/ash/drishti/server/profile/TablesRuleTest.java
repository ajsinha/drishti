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
package com.ash.drishti.server.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.lake.config.Lakes;
import com.ash.lake.tck.TestRoots;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rule T1: a lake with a table that has no uri is refused under production; the shipped lake files have an explicit entry for every table. */
class TablesRuleTest {

    @Test
    void aTableResolvedByTheLayoutIsRefusedAndAnExplicitOneIsNot() {
        Lakes lakes = Lakes.of(
                Lakes.parse("lake: bank\nformat: delta\nroot: /data/bank\ntables:\n  a.b: {uri: 's3://x/y/z'}\n  c.d: {}\n  e.f:\n", "bank", k -> null),
                Lakes.parse("lake: ok\nformat: delta\nroot: /data/ok\ntables:\n  a.b: {uri: '${lake.root}/a/b'}\n", "ok", k -> null));
        List<String> r = TablesRule.refusals(lakes);
        assertThat(r).hasSize(1);
        assertThat(r.get(0)).startsWith("lake bank has 2 tables").contains("c.d, e.f").contains("lake entries --write");
    }

    @Test
    void everyShippedLakeFileIsExplicit() throws Exception {
        Path dir = Path.of(TestRoots.repo()).resolve("config/lakes");
        assertThat(Files.isDirectory(dir)).isTrue();
        Lakes lakes = Lakes.read(dir, k -> k.equals("drishti.data.dir") || k.equals("lake.data.dir") ? "/tmp/drishti-data" : null);
        assertThat(lakes.all()).isNotEmpty();
        assertThat(TablesRule.refusals(lakes)).as("config/lakes: every table has a uri (drishti.py lake entries --write)").isEmpty();
    }
}
