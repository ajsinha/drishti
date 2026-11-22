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
package com.ash.drishti.server.modules;

import com.ash.tools.codec.Hashes;
import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.net.digest.Digest;
import com.ash.drishti.registry.ModuleState;
import com.ash.drishti.testroots.RecordedAnswers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The recorded Admin → Modules answer ({@code GET /api/v1/admin/modules}, contract {@value ModuleClusterView#CONTRACT}, KERNEL_AND_MODULES.md
 * section 4.2.2) that the console's Admin → Modules page (mock-up C) is built and tested against: {@code
 * drishti-console/tests/answers/admin-modules.json}, made by the server's own code ({@link ModuleClusterView}, the switch rows of
 * {@link ModuleAdminController}) over the mock-up's example: three nodes, ten modules; trading booted with other content on drishti-c,
 * capitalmarkets stale, source-swapone refused, library copies in market-data and economy. {@code -Ddrishti.record=true} writes it again.
 */
class AdminModulesRecordTest {

    static final Path REPO = Path.of(com.ash.lake.tck.TestRoots.repo());
    static final String KERNEL = "2.9.11.i9";

    @Test
    void theRecordedAdminModulesAnswerIsTheServers() throws Exception {
        Map<String, Object> answer = example();
        // the contract's promises, checked on the example
        assertThat(answer.get("contract")).isEqualTo(ModuleClusterView.CONTRACT);
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) answer.get("counts");
        assertThat(counts).containsEntry("modules", 10).containsEntry("nodes", 3).containsEntry("nodesAgree", 2).containsEntry("nodesDiffer", 1)
                .containsEntry("stale", 1).containsEntry("notLoaded", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disagree = (List<Map<String, Object>>) answer.get("disagree");
        assertThat(disagree).singleElement().satisfies(d -> assertThat(d).containsEntry("module", "trading").containsEntry("differing", List.of("drishti-c")));

        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        RecordedAnswers rec = RecordedAnswers.under(REPO, "");
        rec.keep("admin-modules.json", json.writeValueAsString(answer) + "\n");
        rec.assertCurrent();
    }

    /** The mock-up's cluster as Admin → Modules on drishti-main answers it. */
    static Map<String, Object> example() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(ModuleAdminController.switchRow(on("banking", "Banking", "risk"), true));
        rows.add(ModuleAdminController.switchRow(on("capitalmarkets", "Capital markets", "risk"), false));
        rows.add(ModuleAdminController.switchRow(on("economy", "Economy", "public-sector"), true));
        rows.add(ModuleAdminController.switchRow(off("genomics", "Genomics", "genomics"), false));
        rows.add(ModuleAdminController.switchRow(on("market-data", "Market data", "risk"), true));
        rows.add(ModuleAdminController.switchRow(off("source-calypso", "Calypso", "sources"), false));
        rows.add(ModuleAdminController.switchRow(on("source-endur", "Endur", "sources"), true));
        rows.add(ModuleAdminController.switchRow(on("source-murex", "Murex", "sources"), true));
        rows.add(ModuleAdminController.switchRow(off("source-swapone", "SwapOne", "sources"), false));
        rows.add(ModuleAdminController.switchRow(on("trading", "Trading", "risk"), true));
        Map<String, Object> switches = new LinkedHashMap<>();
        switches.put("modules", rows);
        Map<String, Object> licence = new LinkedHashMap<>();             // ordered: the recording is compared byte for byte
        licence.put("customer", "example");
        licence.put("modules", List.of());
        licence.put("signed", false);
        licence.put("development", true);
        licence.put("source", "none");
        switches.put("licence", licence);
        switches.put("lenses", List.of());
        switches.put("store", new LinkedHashMap<>());
        switches.put("warnings", List.of());

        List<ModuleClusterView.NodeModules> nodes = List.of(
                new ModuleClusterView.NodeModules("drishti-main", true, true, sha("fingerprint main"), node("drishti-main", "trading")),
                new ModuleClusterView.NodeModules("drishti-b", false, true, sha("fingerprint main"), node("drishti-b", "trading")),
                new ModuleClusterView.NodeModules("drishti-c", false, true, sha("fingerprint c"), node("drishti-c", "trading on c")));
        return ModuleClusterView.fill(switches, "drishti-main", KERNEL, "/opt/drishti/modules", nodes);
    }

    /** What one node booted: every module of the example, trading of the given content. */
    static List<Digest.ModuleEntry> node(String node, String trading) {
        List<Digest.ModuleEntry> out = new ArrayList<>();
        out.add(loaded("banking", List.of("api", "compute", "console")));
        out.add(new Digest.ModuleEntry("capitalmarkets", null, null, null, "stale", List.of(), List.of(new Digest.Problem("stale", "problem",
                "capitalmarkets 2.9.11.i6: built for kernel 2.9.11.i6, this is " + KERNEL + ": install the matching module"))));
        out.add(withProblem(loaded("economy", List.of("api", "console")), new Digest.Problem("library", "warning", "economy " + KERNEL
                + " carries jackson-databind 2.17.2 (lib/jackson-databind-2.17.2.jar); the kernel's jackson-databind 2.18.2 is used instead. "
                + "Remove the jar from the module and compile against the kernel's (scope provided).")));
        out.add(new Digest.ModuleEntry("genomics", "0.3.0", sha("genomics"), "third-party", "off", List.of(), List.of()));
        out.add(withProblem(loaded("market-data", List.of("api", "console")), new Digest.Problem("library", "warning", "market-data " + KERNEL
                + " carries guava 32.1.3-jre (lib/guava-32.1.3-jre.jar); the kernel's guava 33.4.0-jre is used instead. "
                + "Remove the jar from the module and compile against the kernel's (scope provided).")));
        out.add(new Digest.ModuleEntry("source-calypso", KERNEL, sha("source-calypso"), "first-party", "off", List.of(), List.of()));
        out.add(loaded("source-endur", List.of("api")));
        out.add(loaded("source-murex", List.of("api")));
        out.add(new Digest.ModuleEntry("source-swapone", null, null, null, "refused", List.of(), List.of(new Digest.Problem("refused", "problem",
                "source-swapone-1.2.0.dmod: V2: the hash list does not match the files (lib/swapone-client.jar changed): build the package again"))));
        out.add(new Digest.ModuleEntry("trading", KERNEL, sha(trading), "first-party", "loaded",
                node.equals("drishti-c") ? List.of("api") : List.of("api", "console"), List.of()));
        return out;
    }

    static Digest.ModuleEntry loaded(String id, List<String> parts) {
        return new Digest.ModuleEntry(id, KERNEL, sha(id), "first-party", "loaded", parts, List.of());
    }

    static Digest.ModuleEntry withProblem(Digest.ModuleEntry e, Digest.Problem p) {
        return new Digest.ModuleEntry(e.id(), e.version(), e.contentHash(), e.origin(), e.state(), e.parts(), List.of(p));
    }

    static ModuleState on(String id, String title, String domain) {
        return new ModuleState(id, title, domain, true, true, true, true, "", "shipped");
    }

    static ModuleState off(String id, String title, String domain) {
        return new ModuleState(id, title, domain, true, true, false, false, "switched off by an administrator", "config/modules/" + id + ".yaml");
    }

    /** A stable example hash: the SHA-256 of the words. */
    static String sha(String words) {
        try {
            return Hashes.sha256Hex(words);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
