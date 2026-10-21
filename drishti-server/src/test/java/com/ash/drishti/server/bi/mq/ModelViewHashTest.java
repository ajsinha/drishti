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
package com.ash.drishti.server.bi.mq;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.layer.DatasetDef;
import com.ash.drishti.server.bi.layer.DatasetDefParser;
import com.ash.drishti.server.bi.layer.ResolvedDataset;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * BIR-07: the model part of a run's data version (the model view's hash) is the same for the same model whatever the order its datasets and masks were
 * loaded in, and in every JVM (a {@code Set.copyOf} iterates in an order salted per JVM, which once changed the hash across a restart); a real change
 * of the model changes it.
 */
class ModelViewHashTest {

    static final String BODY = "rachana: 2\nmodel: desk-risk\nversion: 1.0.0\n";

    static DatasetDef dataset(String name, String version) {
        return DatasetDefParser.parse("""
                rachana: 2
                dataset: %s
                version: %s
                source: { connector: lake, kind: %s }
                columns:
                  desk:   { type: string, role: key }
                  name:   { type: string }
                  region: { type: string }
                  mtm:    { type: decimal, aggregate: sum }
                """.formatted(name, version, name)).dataset();
    }

    static ResolvedDataset resolved(DatasetDef d, List<String> masked) {
        List<List<DatasetDef.Rule>> layers = List.of(List.of(new DatasetDef.Rule(List.of("desk-head"), "trades.desk in user.desks")));
        return new ResolvedDataset(d, layers, List.of(), new LinkedHashSet<>(masked), List.of(d.name()));
    }

    /** The model as loaded in one order: aliases and masks in the order given. */
    static String hash(List<String> aliases, List<String> masks, String version) {
        Map<String, ResolvedDataset> m = new LinkedHashMap<>();
        for (String a : aliases) {
            m.put(a, resolved(dataset(a, a.equals("trades") ? version : "1.0.0"), a.equals("trades") ? masks : List.of()));
        }
        return ModelCatalog.hash(ModelCatalog.fingerprint(BODY, m, false));
    }

    static final List<String> ALIASES = List.of("trades", "desks", "limits", "books");
    static final List<String> MASKS = List.of("name", "region", "mtm", "desk");

    @Test
    void theSameModelLoadedInAnyOrderHashesTheSame() {
        String first = hash(ALIASES, MASKS, "1.0.0");
        List<String> aliases = new ArrayList<>(ALIASES);
        List<String> masks = new ArrayList<>(MASKS);
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 200; i++) {
            java.util.Collections.shuffle(aliases, r);
            java.util.Collections.shuffle(masks, r);
            assertThat(hash(aliases, masks, "1.0.0")).as("order %s %s", aliases, masks).isEqualTo(first);
        }
    }

    @Test
    void aRealChangeOfTheModelChangesTheHash() {
        String first = hash(ALIASES, MASKS, "1.0.0");
        assertThat(hash(ALIASES, MASKS, "1.1.0")).as("a dataset's version").isNotEqualTo(first);
        assertThat(hash(ALIASES, List.of("name", "region", "mtm"), "1.0.0")).as("a mask fewer").isNotEqualTo(first);
        assertThat(hash(List.of("trades", "desks", "limits"), MASKS, "1.0.0")).as("a dataset fewer").isNotEqualTo(first);
        Map<String, ResolvedDataset> m = new LinkedHashMap<>();
        m.put("trades", resolved(dataset("trades", "1.0.0"), MASKS));
        assertThat(ModelCatalog.hash(ModelCatalog.fingerprint(BODY, m, false)))
                .isNotEqualTo(ModelCatalog.hash(ModelCatalog.fingerprint(BODY + "title: x\n", m, false))).as("the model's text");
    }

    @Test
    void theMasksOfAResolvedDatasetKeepAStableOrder() {
        assertThat(resolved(dataset("trades", "1.0.0"), MASKS).masked()).containsExactlyElementsOf(MASKS);
    }

    /** Across simulated restarts: fresh JVMs (each with its own iteration salt) give the hash this JVM gives. */
    @Test
    void everyFreshJvmGivesTheSameHash() throws Exception {
        String here = hash(ALIASES, MASKS, "1.0.0");
        Set<String> seen = new HashSet<>();
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        for (int i = 0; i < 4; i++) {
            Process p = new ProcessBuilder(java, "-Xmx64m", "-cp", System.getProperty("java.class.path"), Boot.class.getName()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertThat(p.waitFor()).as(out).isZero();
            seen.add(out.lines().reduce((a, b) -> b).orElse(""));
        }
        assertThat(seen).containsExactly(here);
    }

    /** One boot: prints the hash. */
    public static final class Boot {
        public static void main(String[] args) {
            System.out.println(hash(ALIASES, MASKS, "1.0.0"));
        }
    }
}
