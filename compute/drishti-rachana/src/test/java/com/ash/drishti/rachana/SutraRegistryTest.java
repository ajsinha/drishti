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
package com.ash.drishti.rachana;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.model.Sutra;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SutraRegistryTest {

    @TempDir
    Path dir;

    private static String sutra(String name, int version, String kind) {
        return "rachana: 1\nsutra: " + name + "\nversion: " + version + "\nmatch: { kind: " + kind + " }\npanels:\n  - { id: p, kind: links }\n";
    }

    private SutraRegistry registry(boolean hot) {
        return new SutraRegistry(new RachanaProperties(List.of(dir.toString()), hot, Duration.ofMillis(50), null, null, null, null, null, null, null), new com.ash.drishti.rachana.el.ElCompiler());
    }

    @Test
    void loadsVersionsAndKeepsLastGoodOnError() throws Exception {
        Files.createDirectories(dir.resolve("rates"));
        Files.writeString(dir.resolve("rates/a.v1.sutra.yaml"), sutra("alpha", 1, "trade"));
        Files.writeString(dir.resolve("rates/a.v2.sutra.yaml"), sutra("alpha", 2, "trade"));
        Files.writeString(dir.resolve("b.sutra.yaml"), sutra("beta", 1, "curve"));
        try (SutraRegistry r = registry(false)) {
            assertThat(r.latest("alpha")).get().extracting(s -> s.version()).isEqualTo(2);
            assertThat(r.versions("alpha")).containsExactly(1, 2);
            assertThat(r.get("alpha", 1)).isPresent();
            assertThat(r.forKind("curve")).extracting(s -> s.name()).containsExactly("beta");
            assertThat(r.latest("alpha").orElseThrow().domain()).isEqualTo("rates");

            List<Set<String>> changes = new CopyOnWriteArrayList<>();
            r.onChange(changes::add);
            Files.writeString(dir.resolve("b.sutra.yaml"), "rachana: 1\nsutra: beta\nversion: 1\nmatch: { kind: curve }\npanels: 7\n");
            r.reload();
            assertThat(r.latest("beta")).isPresent();
            assertThat(r.problems()).hasSize(1);
            assertThat(changes).isEmpty();

            Files.writeString(dir.resolve("dup.sutra.yaml"), sutra("alpha", 2, "trade"));
            r.reload();
            assertThat(r.problems().values().stream().flatMap(List::stream)).extracting(SutraProblem::code).contains("DRS-2028");
        }
    }

    @Test
    void hotReloadPicksUpNewFiles() throws Exception {
        try (SutraRegistry r = registry(true)) {
            List<Set<String>> changes = new CopyOnWriteArrayList<>();
            r.onChange(changes::add);
            Files.writeString(dir.resolve("g.sutra.yaml"), sutra("gamma", 1, "trade"));
            long deadline = System.currentTimeMillis() + 10_000;
            while (r.latest("gamma").isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(r.latest("gamma")).isPresent();
            assertThat(changes).anySatisfy(c -> assertThat(c).contains("gamma@1"));
        }
    }

    @Test
    void filesThatAreNotYamlSutrasAreReportedNotRead(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("old.v1.sutra.md"), "# old\n```sutra\nsutra: old\nversion: 1\nmatch: { kind: trade }\n```\n");
        Files.writeString(dir.resolve("plain.yaml"), "rachana: 1\nsutra: plain\nversion: 1\nmatch: { kind: trade }\n");
        Files.writeString(dir.resolve("good.v1.sutra.yaml"), "rachana: 1\nsutra: good\nversion: 1\nmatch: { kind: trade }\n");
        try (SutraRegistry r = new SutraRegistry(new RachanaProperties(List.of(dir.toString()), false, Duration.ofMillis(50), null, null, null, null, null, null, null),
                new com.ash.drishti.rachana.el.ElCompiler())) {
            assertThat(r.all()).extracting(x -> x.name()).containsExactly("good");
            assertThat(r.problems().get(dir.resolve("old.v1.sutra.md").toString())).singleElement()
                    .satisfies(p -> assertThat(p.message()).contains("tools/rachana/md_to_yaml.py"));
            assertThat(r.problems().get(dir.resolve("plain.yaml").toString())).singleElement()
                    .satisfies(p -> assertThat(p.message()).contains("rename plain.yaml"));
        }
    }

    /** The QA reproduction (GRAM-01): a bind with thousands of nested parentheses. */
    static String deep(String name, int depth) {
        return "rachana: 1\nsutra: " + name + "\nversion: 1\nmatch: { kind: trade }\npanels:\n  - { id: a, kind: kv, columns: [{label: x, bind: '"
                + "(".repeat(depth) + "$.mtm" + ")".repeat(depth) + "'}] }\n";
    }

    private static void await(java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    @Test
    void aDeeplyNestedSutraIsAProblemNotAFailedStart() throws Exception {
        Files.writeString(dir.resolve("qa-deep.v1.sutra.yaml"), deep("qa-deep", 3000));
        Files.writeString(dir.resolve("good.v1.sutra.yaml"), sutra("good", 1, "trade"));
        try (SutraRegistry r = registry(false)) {
            assertThat(r.all()).extracting(Sutra::name).containsExactly("good");
            assertThat(r.problems().get(dir.resolve("qa-deep.v1.sutra.yaml").toString())).singleElement().satisfies(p -> {
                assertThat(p.code()).isEqualTo("DRS-2101");
                assertThat(p.message()).contains("nested deeper than 200");
                assertThat(p.location().line()).isPositive();
            });
        }
    }

    @Test
    void hotReloadSurvivesADeeplyNestedSutraAndKeepsLoadingLaterOnes() throws Exception {
        try (SutraRegistry r = registry(true)) {
            Files.writeString(dir.resolve("qa-deep.v1.sutra.yaml"), deep("qa-deep", 3000));
            await(() -> !r.problems().isEmpty());
            assertThat(r.problems()).containsKey(dir.resolve("qa-deep.v1.sutra.yaml").toString());
            Files.writeString(dir.resolve("probe.v1.sutra.yaml"), sutra("qa-probe", 1, "trade"));
            await(() -> r.latest("qa-probe").isPresent());
            assertThat(r.latest("qa-probe")).isPresent();
            assertThat(r.hotReload()).isIn("WATCHING", "POLLING");   // POLLING where the inotify watch limit is reached
        }
    }

    /** The per-file guard: even a failure the checks do not foresee (an overflow, with the limits lifted) is one file's problem. */
    @Test
    void anyFailureLoadingOneFileIsThatFilesProblem() throws Exception {
        Files.writeString(dir.resolve("qa-deep.v1.sutra.yaml"), deep("qa-deep", 100_000));
        Files.writeString(dir.resolve("good.v1.sutra.yaml"), sutra("good", 1, "trade"));
        var unbounded = new com.ash.drishti.rachana.el.ElCompiler(100,
                new com.ash.drishti.rachana.el.ElLimits(Integer.MAX_VALUE, Integer.MAX_VALUE));
        try (SutraRegistry r = new SutraRegistry(new RachanaProperties(List.of(dir.toString()), true, Duration.ofMillis(50), null, null, null,
                null, null, null, null), unbounded)) {
            assertThat(r.all()).extracting(Sutra::name).containsExactly("good");
            assertThat(r.problems().get(dir.resolve("qa-deep.v1.sutra.yaml").toString())).singleElement()
                    .satisfies(p -> assertThat(p.code()).isEqualTo("DRS-2032"));
            Files.writeString(dir.resolve("deeper.v1.sutra.yaml"), deep("qa-deeper", 100_000));   // the watcher meets one too
            await(() -> r.problems().size() == 2);
            Files.writeString(dir.resolve("probe.v1.sutra.yaml"), sutra("qa-probe", 1, "trade"));
            await(() -> r.latest("qa-probe").isPresent());
            assertThat(r.problems()).hasSize(2);
            assertThat(r.latest("qa-probe")).isPresent();
            assertThat(r.hotReload()).isIn("WATCHING", "POLLING");   // POLLING where the inotify watch limit is reached
        }
    }

    /** Hot reload by polling (drishti.rachana.watch: poll, and the fallback when file events are unavailable): a new file is loaded. */
    @Test
    void pollingPicksUpANewSutraWithoutFileEvents() throws Exception {
        try (SutraRegistry r = new SutraRegistry(new RachanaProperties(List.of(dir.toString()), true, Duration.ofMillis(50), null, null, null,
                null, null, null, null, "poll", Duration.ofMillis(100)), new com.ash.drishti.rachana.el.ElCompiler())) {
            assertThat(r.hotReload()).isEqualTo("POLLING");
            Files.writeString(dir.resolve("polled.v1.sutra.yaml"), sutra("qa-polled", 1, "trade"));
            await(() -> r.latest("qa-polled").isPresent());
            assertThat(r.latest("qa-polled")).isPresent();
        }
        assertThat(new RachanaProperties(null, null, null, null, null, null, null, null, null, null).watch()).isEqualTo("auto");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RachanaProperties(null, null, null, null, null, null, null, null, null, null,
                "inotify", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
