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
package com.ash.drishti.server.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.content.FileContentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Sutra proposals in the content store (CONTENT_STORE.md section 9.5): two nodes on one root, one decision wins, the folder imported once. */
class ProposalStoreContentTest {

    @Test
    void twoNodesShareProposalsAndOnlyOneDecisionWins() throws Exception {
        Path dir = com.ash.drishti.server.TestRun.root().resolve(Path.of("content-tests", "governance-" + UUID.randomUUID()));
        Path folder = dir.resolve("governance");
        ProposalStore old = new ProposalStore(folder);
        Proposal before = old.create("risk", 1, "text", "", "old one", "ann");
        Path root = dir.resolve("fstore");

        ProposalStore a = new ProposalStore(folder, new FileContentStore(root));
        ProposalStore b = new ProposalStore(folder, new FileContentStore(root));
        assertThat(b.find(before.id())).as("imported from the folder").isPresent();
        Proposal p = a.create("risk", 2, "text 2", "text", "new", "ann");
        assertThat(p.id()).isEqualTo("P-000002");
        assertThat(b.list(Proposal.PENDING, null)).extracting(Proposal::id).containsExactly(p.id(), before.id());

        Proposal current = b.require(p.id());
        b.decide(p.id(), x -> { }, () -> { }, Proposal.APPROVED, "bob", "ok");
        assertThat(a.require(p.id()).status()).isEqualTo(Proposal.APPROVED);
        assertThatThrownBy(() -> a.decide(p.id(), x -> { }, () -> { }, Proposal.REJECTED, "cy", "no"))
                .isInstanceOf(DrishtiException.class).matches(e -> ((DrishtiException) e).errorCode() == ErrorCode.PROPOSAL_CONFLICT);
        assertThat(current.pending()).isTrue();
        assertThat(Files.isRegularFile(folder.resolve(before.id() + ".json"))).as("the folder is kept").isTrue();
        assertThat(new ProposalStore(folder, new FileContentStore(root)).list(null, null)).hasSize(2);
    }
}
