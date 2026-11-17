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
package com.ash.drishti.net;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.net.digest.CapabilityTable;
import com.ash.drishti.net.digest.Digest;
import com.ash.drishti.net.digest.ModuleAgreement;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.member.MemberState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The modules in the cluster's digest (KERNEL_AND_MODULES.md section 4.2.1): a node's modules are its content hashes, never the file's
 * bytes; the nodes that booted other content than the majority are named, per module, in the capability table's warnings; a node that
 * has not reported its modules is left out.
 */
class ModuleAgreementTest {

    static final String X = "a".repeat(64);
    static final String Y = "b".repeat(64);
    static final String B = "c".repeat(64);

    static Digest.ModuleEntry mod(String id, String hash) {
        return new Digest.ModuleEntry(id, "2.9.11.i7a", hash, "first-party", "loaded", List.of("api"), List.of());
    }

    static Digest digest(String node, List<Digest.ModuleEntry> modules, String fingerprint) {
        return new Digest(node, "http://" + node, "2.9.11", 1, List.of(), List.of(), Digest.Load.NONE, "USNY", List.of(), List.of(), Map.of(),
                List.of(), 1, Map.of(), modules, fingerprint);
    }

    static Member member(String id) {
        return new Member(id, "http://" + id, 1, 1, MemberState.ALIVE, "", "2.9.11", Member.PROTOCOL, List.of(), 0, false);
    }

    @Test
    void theNodeThatBootedOtherTradingContentIsNamedAndTheHashSaysSo() throws Exception {
        Digest a = digest("a", List.of(mod("banking", B), mod("trading", X)), "f1");
        Digest b = digest("b", List.of(mod("banking", B), mod("trading", X)), "f1");
        Digest c = digest("c", List.of(mod("banking", B), mod("trading", Y)), "f2");
        assertThat(c.hash()).as("the modules are part of what a node serves").isNotEqualTo(digest("c", List.of(mod("banking", B), mod("trading", X)), "f2").hash());

        ModuleAgreement ag = ModuleAgreement.of("a", Map.of("a", a, "b", b, "c", c));
        assertThat(ag.module("banking").orElseThrow().agree()).isTrue();
        ModuleAgreement.Module t = ag.module("trading").orElseThrow();
        assertThat(t.agree()).isFalse();
        assertThat(t.majority()).isEqualTo(X);
        assertThat(t.differing()).containsExactly("c");
        assertThat(t.words()).contains("nodes disagree on module trading", "c booted bbbbbbbb (2.9.11.i7a)", "a, b booted aaaaaaaa", "(the majority)");

        CapabilityTable table = CapabilityTable.of("a", a, List.of(member("a"), member("b"), member("c")), Map.of("b", b, "c", c), Map.of());
        assertThat(table.warnings()).filteredOn(w -> w.what().equals("modules")).singleElement()
                .satisfies(w -> assertThat(w.text()).contains("trading", "c booted bbbbbbbb"));

        // the digest travels as JSON between nodes: the modules and the fingerprint survive the trip
        ObjectMapper json = new ObjectMapper();
        Digest back = json.readValue(json.writeValueAsString(c), Digest.class);
        assertThat(back.modules()).isEqualTo(c.modules());
        assertThat(back.modulesFingerprint()).isEqualTo("f2");
        assertThat(back.hash()).isEqualTo(c.hash());
    }

    @Test
    void aMissingModuleCountsAndANodeThatDidNotReportIsLeftOut() {
        Digest a = digest("a", List.of(mod("trading", X)), "f1");
        Digest b = digest("b", List.of(), "f0");                                          // booted no trading at all
        Digest old = new Digest("old", "http://old", "2.9.10", 1, List.of(), List.of(), Digest.Load.NONE, "USNY", List.of(), List.of(),
                Map.of(), List.of(), 1);                                                    // an older node: no modules reported
        ModuleAgreement ag = ModuleAgreement.of("a", Map.of("a", a, "b", b, "old", old));
        assertThat(ag.nodes()).containsExactly("a", "b");
        ModuleAgreement.Module t = ag.module("trading").orElseThrow();
        assertThat(t.agree()).isFalse();
        assertThat(t.majority()).as("a tie: this node's").isEqualTo(X);
        assertThat(t.differing()).containsExactly("b");
        assertThat(t.groups()).containsKey(ModuleAgreement.ABSENT);
        assertThat(t.words()).contains("b (not installed)");

        // a refused or development module has no hash: its state (or development and its version) is what is compared
        assertThat(new Digest.ModuleEntry("x", null, null, null, "stale", List.of(), List.of()).identity()).isEqualTo("stale");
        assertThat(new Digest.ModuleEntry("x", "1.0", null, "development", "loaded", List.of(), List.of()).identity()).isEqualTo("development 1.0");
    }
}
