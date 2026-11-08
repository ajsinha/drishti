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
package com.ash.drishti.server.tables;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.identity.bi.ContentBiStore;
import com.ash.drishti.identity.content.FileContentStore;
import com.ash.drishti.server.bi.expr.DelTablesTranslator;
import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.catalog.Catalogue;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first-start migration of stored items (docs/architecture/BI_DESIGN.md 4.3, Q16, Q17) over a real item store: every version rewritten in place
 * under the item's id and number, the draft too, the text before kept as saved states, one audit line and one event per item, a refused item
 * left as it was with the reason on its page, and a second run that finds nothing; then the layer's catalogue reads the items.
 */
class StoredTablesMigrationTest {

    @TempDir Path dir;

    static final Instant AT = Instant.parse("2026-11-03T22:00:00Z");

    static String dataset(String status) {
        return "rachana: 2\ndataset: trades\nversion: 1.0.0\nsource: { kind: trade }\ncolumns:\n  TRADE_ID: { type: string, role: key }\n  STATUS: { type: string }\n"
                + "  MTM: { type: decimal }\nwhere: \"STATUS != '" + status + "'\"\nsecurity:\n  rows:\n    - { role: risk, where: \"True\" }\n";
    }

    static final String MODEL_V1 = "rachana: 2\nmodel: book\nversion: 1.0.0\ndatasets:\n  trades: { dataset: trades, role: fact }\nmeasures:\n"
            + "  MTM: { expr: \"sum(trades.MTM)\" }\n";
    static final String MODEL_V2 = MODEL_V1 + "  Open: { expr: \"sum(trades.MTM, where=trades.STATUS != 'MATURED')\" }\n";

    static BiStore.Item item(String id, String kind, String body, int published) {
        return new BiStore.Item(id, "risk", kind, id, "", "ann", List.of(), body, published > 0 ? "published" : "draft", published, "none", "", List.of(), List.of(),
                List.of(), "", null, AT, AT, "ann", published > 0 ? AT : null, published > 0 ? "ann" : "");
    }

    @Test
    void everyVersionIsRewrittenInPlaceAndAudited() {
        BiStore store = new ContentBiStore(new FileContentStore(dir.resolve("fstore")), "n1", 0);
        store.saveItem(item("ds-1", "dataset", dataset("DEAD"), 2));
        store.addVersion(new BiStore.Version("ds-1", 1, dataset("CANCELLED"), "first", "ann", AT), 50);
        store.addVersion(new BiStore.Version("ds-1", 2, dataset("DEAD"), "second", "ann", AT), 50);
        store.saveItem(item("m-1", "model", MODEL_V2, 2));
        store.addVersion(new BiStore.Version("m-1", 1, MODEL_V1, "first", "ann", AT), 50);
        store.addVersion(new BiStore.Version("m-1", 2, MODEL_V2, "second", "ann", AT), 50);
        store.saveItem(item("bad-1", "model", "rachana: 2\nmodel: broken\ndatasets: { trades: { dataset: trades } }\nmeasures:\n  X: { expr: \"sum(\" }\n", 0));
        List<String> audit = new ArrayList<>();
        com.ash.drishti.identity.AuditLog log = new com.ash.drishti.identity.AuditLog() {
            @Override
            public void record(String actor, String action, String subject, String detail) {
                audit.add(actor + " " + action + " " + subject);
            }

            @Override
            public List<Event> recent(int limit, String subject) {
                return List.of();
            }
        };
        StoredTablesMigration m = new StoredTablesMigration(store, log, new DelTablesTranslator(), Clock.fixed(AT, ZoneOffset.UTC));
        assertThat(m.run(null, true)).extracting(StoredTablesMigration.Outcome::state).containsExactlyInAnyOrder("would migrate", "would migrate", "refused");
        assertThat(store.item("ds-1").orElseThrow().kind()).as("a dry run writes nothing").isEqualTo("dataset");

        List<StoredTablesMigration.Outcome> done = m.run(null, false);
        assertThat(done).extracting(StoredTablesMigration.Outcome::item, StoredTablesMigration.Outcome::state).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("ds-1", "migrated"), org.assertj.core.groups.Tuple.tuple("m-1", "migrated"),
                org.assertj.core.groups.Tuple.tuple("bad-1", "refused"));
        assertThat(store.item("ds-1").orElseThrow().kind()).isEqualTo("table");
        assertThat(store.version("ds-1", 1).orElseThrow().body()).contains("table: bi.trades").contains("filter: \"$.STATUS != 'CANCELLED' && $.STATUS != null\"");
        assertThat(store.version("ds-1", 2).orElseThrow().body()).contains("$.STATUS != 'DEAD'");
        assertThat(store.item("ds-1").orElseThrow().body()).contains("rachana: 3");
        assertThat(store.version("m-1", 1).orElseThrow().body()).contains("tables:").doesNotContain("Open");
        assertThat(store.version("m-1", 2).orElseThrow().body()).contains("trades.STATUS != 'MATURED' && trades.STATUS != null");
        assertThat(store.revisions("ds-1")).extracting(BiStore.Revision::body).contains(dataset("CANCELLED"), dataset("DEAD"));
        assertThat(store.item("bad-1").orElseThrow().kind()).isEqualTo("model");
        assertThat(store.events("bad-1")).anyMatch(e -> e.action().equals("tables-migrate-refused"));
        assertThat(audit).containsExactlyInAnyOrder("system tables-migrate ds-1", "system tables-migrate m-1");

        assertThat(m.run(null, false)).extracting(StoredTablesMigration.Outcome::item).containsExactly("bad-1");     // idempotent
        List<Problem> problems = new Catalogue_(store).check();
        assertThat(problems).filteredOn(Problem::isError).isEmpty();
    }

    /** The layer's catalogue over the store's published items. */
    record Catalogue_(BiStore store) {
        List<Problem> check() {
            try {
                Catalogue c = Catalogue.load(null, new BiItemTableStore(store));
                assertThat(c.tables()).extracting(t -> t.name()).containsExactly("bi.trades");
                assertThat(c.models()).extracting(x -> x.name()).containsExactly("book");
                return c.check();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }
}
