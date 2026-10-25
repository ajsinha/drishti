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
package com.ash.drishti.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.compute.approval.ApprovalItem;
import com.ash.drishti.compute.approval.ApprovalRequest;
import com.ash.drishti.compute.approval.ApprovalStore;
import com.ash.drishti.compute.approval.Approvals;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteDataSource;

/**
 * Step C3's rules (docs/architecture/PYTHON_COMPUTE.md section 6), on memory and on the Drishti database's SQLite schema: unapproved
 * requirements file one request; the person who asked can neither approve nor reject it (four eyes); an approval needs a reason; a revoked
 * approval stops covering its items; a new version or hash is a new item; every step is audited.
 */
class ApprovalsTest {

    @TempDir
    Path dir;

    private final List<String> audit = new CopyOnWriteArrayList<>();

    private ApprovalStore store(String kind) throws Exception {
        if ("memory".equals(kind)) {
            return new ApprovalStore.Memory();
        }
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("drishti.db"));
        String sql = Files.readString(Path.of("..", "drishti-identity", "src", "main", "resources", "db", "schema-sqlite.sql")).replaceAll("--[^\\n]*", "");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            for (String stmt : sql.split(";")) {
                if (!stmt.isBlank()) {
                    s.execute(stmt);
                }
            }
        }
        return new ApprovalStore.Jdbc(ds);
    }

    private Approvals approvals(ApprovalStore store) {
        return new Approvals(store, (actor, action, subject, detail) -> audit.add(actor + " " + action + " " + subject), Clock.systemUTC(), 0);
    }

    private static final ApprovalItem NUMPY = new ApprovalItem("python", "numpy", "2.1.3", "a".repeat(64), "shipped", true);
    private static final ApprovalItem SDK = new ApprovalItem("python", "drishti-compute", "0.1.0", "b".repeat(64), "shipped", false);

    @ParameterizedTest
    @ValueSource(strings = {"memory", "sqlite"})
    void unapprovedRequirementsWaitAndTheRequesterCannotApproveThem(String kind) throws Exception {
        Approvals a = approvals(store(kind));
        Approvals.Status s = a.request("connector:var-engine", List.of(NUMPY, SDK), "dev1");
        assertThat(s.approved()).isFalse();
        assertThat(s.words()).contains("waiting for approval of 2 requirements").contains(s.request().id());
        assertThat(a.request("connector:var-engine", List.of(NUMPY, SDK), "dev1").request().id()).isEqualTo(s.request().id());   // one request
        String id = s.request().id();
        assertThatThrownBy(() -> a.approve(id, "dev1", "looks fine")).hasMessageContaining("four eyes");
        assertThatThrownBy(() -> a.reject(id, "DEV1", "no")).hasMessageContaining("four eyes");
        assertThatThrownBy(() -> a.approve(id, "admin2", " ")).hasMessageContaining("reason");
        ApprovalRequest done = a.approve(id, "admin2", "scanned, licences fine");
        assertThat(done.state()).isEqualTo("approved");
        assertThat(a.status("connector:var-engine", List.of(NUMPY, SDK)).approved()).isTrue();
        assertThat(a.status("connector:other", List.of(SDK)).approved()).isTrue();                     // approved items serve any subject
        assertThat(audit).contains("dev1 compute-request connector:var-engine", "dev1 compute-approve-refused connector:var-engine",
                "admin2 compute-approve connector:var-engine");
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "sqlite"})
    void aRevokedApprovalNoLongerCoversItsItemsAndANewHashNeedsItsOwn(String kind) throws Exception {
        Approvals a = approvals(store(kind));
        String id = a.request("pool:risk", List.of(NUMPY), "compute.yaml").request().id();
        a.approve(id, "admin1", "ok");
        ApprovalItem tampered = new ApprovalItem("python", "numpy", "2.1.3", "c".repeat(64), "shipped", true);
        assertThat(a.status("pool:risk", List.of(tampered)).approved()).isFalse();
        assertThatThrownBy(() -> a.revoke(id, "admin1", "")).hasMessageContaining("why");
        a.revoke(id, "admin1", "CVE-2026-0001");
        assertThat(a.status("pool:risk", List.of(NUMPY)).approved()).isFalse();
        assertThat(a.get(id).orElseThrow().state()).isEqualTo("revoked");
        assertThat(a.get(id).orElseThrow().revokeReason()).isEqualTo("CVE-2026-0001");
        assertThat(audit).contains("admin1 compute-revoke pool:risk");
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "sqlite"})
    void aRejectedSetStaysOffAndADifferentSetSupersedesAPendingOne(String kind) throws Exception {
        Approvals a = approvals(store(kind));
        String first = a.request("connector:c", List.of(NUMPY), "dev1").request().id();
        String second = a.request("connector:c", List.of(NUMPY, SDK), "dev1").request().id();
        assertThat(second).isNotEqualTo(first);
        assertThat(a.get(first).orElseThrow().state()).isEqualTo("superseded");
        a.reject(second, "admin1", "numpy 2.1.3 has a known issue");
        Approvals.Status s = a.status("connector:c", List.of(NUMPY, SDK));
        assertThat(s.state()).isEqualTo("rejected");
        assertThat(s.words()).contains("rejected by admin1");
    }
}
