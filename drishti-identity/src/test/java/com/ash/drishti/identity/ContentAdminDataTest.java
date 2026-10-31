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
package com.ash.drishti.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.identity.content.FileContentStore;
import com.ash.drishti.identity.db.EmbedAppEntity;
import com.ash.drishti.identity.db.IdentityRepositories;
import com.ash.drishti.identity.db.PackStateEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** Embed apps, pack switches and the audit trail in the content store (DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9): two nodes on one filesystem root. */
class ContentAdminDataTest {

    @TempDir
    Path tmp;

    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        Path dir = tmp.toAbsolutePath();
        root = dir.resolve("fstore");
    }

    private ContentStore node() {
        return new FileContentStore(root);
    }

    private static final AuditLog NO_AUDIT = new AuditLog() {
        @Override
        public void record(String actor, String action, String subject, String detail) {}

        @Override
        public List<Event> recent(int limit, String subject) {
            return List.of();
        }
    };

    @SuppressWarnings("unchecked")
    private static TransactionTemplate tx() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        return tx;
    }

    @Test
    void theAuditTrailMergesBothNodesAndShowsTampering() {
        ContentAuditLog a = new ContentAuditLog(node(), "node-a");
        ContentAuditLog b = new ContentAuditLog(node(), "node-b");
        a.record("ann", "login", "ann", "1");
        b.record("bob", "login", "bob", "2");
        a.record("ann", "user-created", "bob", "3");
        b.record("bob", "logout", "bob", "4");
        assertThat(new ContentAuditLog(node(), "node-c").recent(10, null)).extracting(AuditLog.Event::detail).containsExactly("4", "3", "2", "1");
        assertThat(a.recent(10, "bob")).extracting(AuditLog.Event::detail).containsExactly("4", "3", "2");
        assertThat(a.verify(10)).isEmpty();

        ContentStore raw = node();
        String seg = raw.list("audit/").stream().filter(k -> k.contains("node-a")).findFirst().orElseThrow();
        String text = raw.get(seg).orElseThrow().text();
        raw.putLatest(seg, text.replace("user-created", "user-deleted").getBytes(StandardCharsets.UTF_8));
        assertThat(b.verify(10)).anyMatch(p -> p.contains("node-a#2"));
        raw.putLatest(seg, text.substring(0, text.indexOf('\n') + 1).getBytes(StandardCharsets.UTF_8));
        assertThat(b.verify(10)).anyMatch(p -> p.contains("lost its newest lines"));
    }

    @Test
    void theAuditTableIsImportedThroughTheLog() {
        IdentityRepositories.Audit repo = mock(IdentityRepositories.Audit.class);
        List<com.ash.drishti.identity.db.AuditEventEntity> rows = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            com.ash.drishti.identity.db.AuditEventEntity e = new com.ash.drishti.identity.db.AuditEventEntity();
            e.id = (long) i;
            e.at = Instant.parse("2026-10-0" + i + "T10:00:00Z");
            e.actor = "ann";
            e.action = "login";
            e.subject = "ann";
            e.detail = "#" + i;
            rows.add(e);
        }
        when(repo.findByIdGreaterThanOrderByIdAsc(any(), any())).thenAnswer(inv -> rows.stream().filter(e -> e.id > (Long) inv.getArgument(0)).toList());
        JpaAuditLog jpa = new JpaAuditLog(repo);
        ContentAuditLog c = new ContentAuditLog(node(), "node-a");
        assertThat(jpa.copyTo(c)).isEqualTo(3);
        jpa.useContent(c);
        jpa.record("ann", "logout", "ann", "now");
        assertThat(jpa.recent(10, null)).extracting(AuditLog.Event::detail).containsExactly("now", "#3", "#2", "#1");
        assertThat(node().list("audit/2026-10-01/")).hasSize(1);
        assertThat(jpa.verify(10)).isEmpty();
    }

    @Test
    void aPackSwitchOnOneNodeIsSeenOnTheOtherAfterTheImport() {
        IdentityRepositories.PackStates states = mock(IdentityRepositories.PackStates.class);
        PackStateEntity off = new PackStateEntity();
        off.name = "logistics";
        off.enabled = false;
        off.updatedAt = Instant.now();
        off.updatedBy = "admin";
        when(states.findAll()).thenReturn(List.of(off));
        PackStateStore a = new PackStateStore(states, tx(), NO_AUDIT, node());
        PackStateStore b = new PackStateStore(states, tx(), NO_AUDIT, node());
        assertThat(a.enabled("logistics")).as("imported from the table").isFalse();
        a.set("logistics", true, "admin");
        b.set("finance", false, "admin");
        a.refresh();
        b.refresh();
        assertThat(b.enabled("logistics")).isTrue();
        assertThat(a.enabled("finance")).isFalse();
        when(states.findAll()).thenReturn(List.of());
        assertThat(new PackStateStore(states, tx(), NO_AUDIT, node()).enabled("finance")).as("the import runs once").isFalse();
    }

    @Test
    void anEmbedAppEditedOnTwoNodesGivesOneConflict() {
        EmbedAppRows.Content a = new EmbedAppRows.Content(node());
        EmbedAppRows.Content b = new EmbedAppRows.Content(node());
        EmbedAppEntity e = new EmbedAppEntity();
        e.id = "portal";
        e.name = "Portal";
        e.createdAt = Instant.now();
        a.save(e);
        EmbedAppEntity onA = a.findById("portal").orElseThrow();
        EmbedAppEntity onB = b.findById("portal").orElseThrow();
        onB.name = "Portal B";
        b.save(onB);
        onA.name = "Portal A";
        assertThatThrownBy(() -> a.save(onA)).isInstanceOf(DrishtiException.class)
                .matches(x -> ((DrishtiException) x).errorCode() == ErrorCode.CONTENT_CONFLICT);
        assertThat(a.findById("portal").orElseThrow().name).isEqualTo("Portal B");
        assertThat(b.findAll()).hasSize(1);

        IdentityRepositories.EmbedApps table = mock(IdentityRepositories.EmbedApps.class);
        EmbedAppEntity old = new EmbedAppEntity();
        old.id = "legacy";
        old.name = "Legacy";
        when(table.findAll()).thenReturn(List.of(old, e));
        assertThat(b.importFrom(table)).isEqualTo(1);
        assertThat(a.importFrom(table)).isZero();
        assertThat(a.findById("legacy")).isPresent();
        assertThat(a.findById("portal").orElseThrow().name).as("never overwritten").isEqualTo("Portal B");
    }
}
