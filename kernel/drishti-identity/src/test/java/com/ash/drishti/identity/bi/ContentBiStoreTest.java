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
package com.ash.drishti.identity.bi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.IdentityConfiguration;
import com.ash.drishti.identity.RoleNames;
import com.ash.drishti.identity.content.FileContentStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * BI content in the content store (docs/architecture/DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9): two in-process nodes sharing one filesystem store root (what a
 * cluster on a shared mount does), compare-and-set on item saves, and the one-time import from the identity database.
 */
class ContentBiStoreTest {

    @TempDir
    Path dir;

    static final Instant T = Instant.parse("2026-10-21T09:00:00Z");

    private ContentBiStore node(String name) {
        return new ContentBiStore(new FileContentStore(dir.resolve("fstore")), name, 0);     // each node opens the same root on its own
    }

    static BiStore.Item item(String id, String ws, String body, int published) {
        return new BiStore.Item(id, ws, "report", "Desk P&L", "", "ann", List.of("rates"), body, published > 0 ? "published" : "draft", published, "none", "",
                List.of("bi-viewer"), List.of(), List.of(), "", null, T, T, "ann", published > 0 ? T : null, published > 0 ? "ann" : "");
    }

    @Test
    void aWorkspaceAndAnItemMadeOnOneNodeAreSeenEditedAndPublishedOnTheOther() {
        ContentBiStore a = node("a");
        ContentBiStore b = node("b");
        a.saveWorkspace(new BiStore.Workspace("ws-rates", "Rates", "desk", false, "ann", true, T, "ann"));
        a.setMember(new BiStore.Member("ws-rates", "ann", "admin", T, "ann"));
        a.setMember(new BiStore.Member("ws-rates", "bob", "viewer", T, "ann"));
        a.saveItem(item("bi-1", "ws-rates", "v: 1", 0));
        a.addRevision("bi-1", "v: 1", "first", "ann", T, "save", 0, 50);

        assertThat(b.workspace("ws-rates")).get().extracting(BiStore.Workspace::reviewRequired).isEqualTo(true);
        assertThat(b.members("ws-rates")).extracting(BiStore.Member::username).containsExactly("ann", "bob");
        assertThat(b.membershipsOf("bob")).extracting(BiStore.Member::role).containsExactly("viewer");
        assertThat(b.itemsIn("ws-rates")).extracting(BiStore.Item::id).containsExactly("bi-1");

        BiStore.Item seen = b.item("bi-1").orElseThrow();                       // edited on b
        assertThat(seen.audienceRoles()).containsExactly("bi-viewer");
        b.saveItem(item("bi-1", "ws-rates", "v: 2", 0));
        b.addRevision("bi-1", "v: 2", "edit", "bob", T, "save", 0, 50);
        b.addVersion(new BiStore.Version("bi-1", 1, "v: 2", "first publish", "ann", T), 50);   // published on b
        b.item("bi-1");
        b.saveItem(item("bi-1", "ws-rates", "v: 2", 1));
        b.addEvent("bi-1", "approve", "ann", "ok", 2, T);
        b.markRevisionPublished("bi-1", 2, 1);

        assertThat(a.item("bi-1").orElseThrow().publishedVersion()).isEqualTo(1);
        assertThat(a.published()).extracting(BiStore.Item::id).containsExactly("bi-1");
        assertThat(a.version("bi-1", 1)).get().extracting(BiStore.Version::body).isEqualTo("v: 2");
        assertThat(a.revisions("bi-1")).extracting(BiStore.Revision::seq).containsExactly(2, 1);
        assertThat(a.revision("bi-1", 2).orElseThrow().publishedVersion()).isEqualTo(1);
        assertThat(a.events("bi-1")).extracting(BiStore.Event::action).containsExactly("approve");

        a.favourite("bob", "bi-1", true);
        b.opened("bob", "bi-1", T);
        a.opened("bob", "bi-1", T);
        assertThat(b.marks("bob").get("bi-1")).isEqualTo(new BiStore.Mark(true, T, 2));
        assertThat(a.views(7, T)).containsEntry("bi-1", 2);                       // one segment per node, summed
        a.setSetting("expected-values", "on");
        assertThat(b.setting("expected-values")).contains("on");

        b.removeMember("ws-rates", "bob");
        assertThat(a.membershipsOf("bob")).isEmpty();
        a.deleteWorkspace("ws-rates");
        assertThat(b.item("bi-1")).isEmpty();
        assertThat(b.revisions("bi-1")).isEmpty();
        assertThat(b.workspaces()).isEmpty();
    }

    @Test
    void twoNodesSavingTheSameItemGiveOneConflict() {
        ContentBiStore a = node("a");
        ContentBiStore b = node("b");
        a.saveItem(item("bi-2", "ws", "v: 1", 0));
        a.item("bi-2");
        b.item("bi-2");
        a.saveItem(item("bi-2", "ws", "from a", 0));
        assertThatThrownBy(() -> b.saveItem(item("bi-2", "ws", "from b", 0)))
                .isInstanceOfSatisfying(DrishtiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONTENT_CONFLICT));
        assertThat(b.item("bi-2").orElseThrow().body()).isEqualTo("from a");
        b.saveItem(item("bi-2", "ws", "from b, after a reload", 0));             // reloaded: saves
        assertThat(a.item("bi-2").orElseThrow().body()).isEqualTo("from b, after a reload");
    }

    @Test
    void twoNodesCreatingTheSameItemGiveOneConflictAndRevisionsNeverShareANumber() throws Exception {
        ContentBiStore a = node("a");
        ContentBiStore b = node("b");
        a.item("bi-3");
        b.item("bi-3");
        a.saveItem(item("bi-3", "ws", "a", 0));
        assertThatThrownBy(() -> b.saveItem(item("bi-3", "ws", "b", 0))).isInstanceOf(DrishtiException.class);
        Thread[] t = new Thread[8];
        for (int i = 0; i < t.length; i++) {
            ContentBiStore s = i % 2 == 0 ? a : b;
            int n = i;
            t[i] = Thread.ofVirtual().start(() -> s.addRevision("bi-3", "r" + n, "", "ann", T, "save", 0, 50));
        }
        for (Thread x : t) {
            x.join();
        }
        assertThat(a.revisions("bi-3")).extracting(BiStore.Revision::seq).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
    }

    private AnnotationConfigApplicationContext open(String kind) {
        AnnotationConfigApplicationContext c = new AnnotationConfigApplicationContext();
        Map<String, Object> p = new HashMap<>();
        p.put("drishti.identity.database-url", "jdbc:sqlite:" + dir.resolve("drishti.db"));
        p.put("drishti.identity.users-file", dir.resolve("users.json").toString());
        p.put("drishti.identity.audit-file", dir.resolve("audit.jsonl").toString());
        p.put("drishti.identity.preferences-dir", dir.resolve("preferences").toString());
        p.put("drishti.identity.iterations", "1000");
        p.put("drishti.data.dir", dir.resolve("data").toString());
        p.put("drishti.store.root", dir.resolve("fstore").toString());
        p.put("drishti.builder.designs.dir", dir.resolve("designs").toString());
        p.put("drishti.bi.store", kind);
        p.put("drishti.bi.store-list-ttl", "0s");
        c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", p));
        c.registerBean(RoleNames.class, () -> RoleNames.of(Set.of("admin", "trader")));
        c.register(IdentityConfiguration.class);
        c.refresh();
        return c;
    }

    @Test
    void theIdentityDatabaseIsImportedOnceAndADeletedItemDoesNotComeBack() {
        try (AnnotationConfigApplicationContext c = open("jpa")) {
            BiStore jpa = c.getBean(BiStore.class);
            assertThat(jpa).isInstanceOf(JpaBiStore.class);
            jpa.saveWorkspace(new BiStore.Workspace("ws-old", "Old", "", false, "ann", false, T, "ann"));
            jpa.setMember(new BiStore.Member("ws-old", "ann", "admin", T, "ann"));
            jpa.saveItem(item("bi-old", "ws-old", "v: 1", 1));
            jpa.saveItem(item("bi-gone", "ws-old", "v: 1", 0));
            jpa.addVersion(new BiStore.Version("bi-old", 1, "v: 1", "first", "ann", T), 50);
            jpa.addRevision("bi-old", "v: 1", "first", "ann", T, "save", 1, 50);
            jpa.addEvent("bi-old", "approve", "ann", "", 1, T);
            jpa.favourite("bob", "bi-old", true);
            jpa.opened("bob", "bi-old", T);
            jpa.setSetting("s1", "v1");
        }
        try (AnnotationConfigApplicationContext c = open("content")) {
            BiStore s = c.getBean(BiStore.class);
            assertThat(s).isInstanceOf(ContentBiStore.class);
            assertThat(s.items()).extracting(BiStore.Item::id).containsExactlyInAnyOrder("bi-old", "bi-gone");
            assertThat(s.members("ws-old")).hasSize(1);
            assertThat(s.versions("bi-old")).hasSize(1);
            assertThat(s.revisions("bi-old")).hasSize(1);
            assertThat(s.events("bi-old")).hasSize(1);
            assertThat(s.marks("bob").get("bi-old")).isEqualTo(new BiStore.Mark(true, T, 1));
            assertThat(s.views(1, T)).containsEntry("bi-old", 1);
            assertThat(s.setting("s1")).contains("v1");
            s.deleteItem("bi-gone");
        }
        try (AnnotationConfigApplicationContext c = open("content")) {
            BiStore s = c.getBean(BiStore.class);
            assertThat(s.items()).extracting(BiStore.Item::id).containsExactly("bi-old");     // the marker: a deleted item stays deleted
            assertThat(s.views(1, T)).containsEntry("bi-old", 1);                               // and nothing is counted twice
        }
        try (AnnotationConfigApplicationContext c = open("jpa")) {
            assertThat(c.getBean(BiStore.class).items()).hasSize(2);                           // the rows are kept
        }
    }
}
