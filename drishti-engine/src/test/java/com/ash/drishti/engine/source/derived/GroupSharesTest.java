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
package com.ash.drishti.engine.source.derived;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityReader;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A group gathered from two nodes' shares (Drishti*net, DRISHTI_NET.md section 8): exact when both list every member, combined otherwise. */
class GroupSharesTest {

    /** One node's trades. */
    record Node(Map<String, Map<String, Object>> trades) implements EntityReader {
        @Override
        public List<EntityRef> list(String kind, AsOf asOf, int limit) {
            return kind.equals("trade") ? trades.keySet().stream().map(id -> EntityRef.of("trade", id)).toList() : List.of();
        }

        @Override
        public Map<EntityRef, EntityDocument> read(Collection<EntityRef> refs, AsOf asOf) {
            Map<EntityRef, EntityDocument> out = new LinkedHashMap<>();
            for (EntityRef r : refs) {
                if (trades.containsKey(r.id())) {
                    out.put(r, new EntityDocument(r, DataNode.of(trades.get(r.id())), new Provenance("lake", 1, Instant.now(), false, null)));
                }
            }
            return out;
        }
    }

    static Map<String, Object> t(String book, double mtm, String ccy) {
        return Map.of("BOOK", book, "MTM", mtm, "CURRENCY", ccy);
    }

    static DerivedSourcePlugin node(Map<String, Map<String, Object>> trades, int maxMembers) {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("pnl.from", "trade");
        s.put("pnl.group-by", "$.BOOK");
        s.put("pnl.fields.N", "count");
        s.put("pnl.fields.MTM", "sum $.MTM");
        s.put("pnl.fields.AVG", "avg $.MTM");
        s.put("pnl.fields.LOW", "min $.MTM");
        s.put("pnl.fields.HIGH", "max $.MTM");
        s.put("pnl.fields.CCY", "distinct $.CURRENCY");
        s.put("pnl.fields.FIRST", "first $.MTM");
        s.put("pnl.rows.MTM", "$.MTM");
        s.put("pnl.max-members", String.valueOf(maxMembers));
        DerivedSourcePlugin p = new DerivedSourcePlugin();
        p.start(new DerivedSourcePluginTest.Ctx(s, new Node(trades)));
        return p;
    }

    static final EntityRef BOOK = EntityRef.of("pnl", "B-1");

    static DataNode gathered(DerivedSourcePlugin a, DerivedSourcePlugin b, java.util.function.UnaryOperator<DataNode> onB) {
        GroupShares.Share mine = a.readShare("pnl", "a", a.share(BOOK, AsOf.LATEST).orElseThrow()).orElseThrow();
        GroupShares.Share theirs = a.readShare("pnl", "b", onB.apply(b.share(BOOK, AsOf.LATEST).orElseThrow())).orElseThrow();
        return a.gather(BOOK, List.of(mine, theirs)).orElseThrow();
    }

    @Test
    void sharesThatListEveryMemberGiveWhatOneNodeWithEveryMemberGives() {
        Map<String, Map<String, Object>> onA = Map.of("T-1", t("B-1", 10, "USD"), "T-2", t("B-1", -4, "EUR"));
        Map<String, Map<String, Object>> onB = Map.of("T-2", t("B-1", -4, "EUR"), "T-3", t("B-1", 30, "GBP"), "T-9", t("B-2", 1, "USD"));
        DataNode page = gathered(node(onA, 1000), node(onB, 1000), d -> d);
        Map<String, Map<String, Object>> all = new LinkedHashMap<>(onA);
        all.putAll(onB);
        DataNode one = node(all, 1000).fetch(BOOK, AsOf.LATEST).orElseThrow().data();
        assertThat(page).isEqualTo(one);
        assertThat(page.get("memberCount").asDouble()).as("T-2, on both nodes, counted once").isEqualTo(3);
        assertThat(page.get("MTM").asDouble()).isEqualTo(36);
    }

    @Test
    void aValueTheOtherNodeMaskedStaysMasked() {
        DerivedSourcePlugin a = node(Map.of("T-1", t("B-1", 10, "USD")), 1000);
        DerivedSourcePlugin b = node(Map.of("T-3", t("B-1", 30, "GBP")), 1000);
        DataNode page = gathered(a, b, d -> mask(d, "MTM"));
        assertThat(page.get("MTM").isMasked()).isTrue();
        assertThat(page.get("HIGH").asDouble()).isEqualTo(30);
    }

    @Test
    void sharesThatListOnlyPartOfALargeGroupAreCombined() {
        Map<String, Map<String, Object>> onA = Map.of("T-1", t("B-1", 10, "USD"), "T-2", t("B-1", 20, "EUR"));
        Map<String, Map<String, Object>> onB = Map.of("T-3", t("B-1", 30, "GBP"), "T-4", t("B-1", 40, "USD"), "T-5", t("B-1", 50, "USD"));
        DataNode page = gathered(node(onA, 1), node(onB, 1), d -> d);
        assertThat(page.get("memberCount").asDouble()).isEqualTo(5);
        assertThat(page.get("N").asDouble()).isEqualTo(5);
        assertThat(page.get("MTM").asDouble()).isEqualTo(150);
        assertThat(page.get("AVG").asDouble()).isEqualTo(30);                // (15 * 2 + 40 * 3) / 5
        assertThat(page.get("LOW").asDouble()).isEqualTo(10);
        assertThat(page.get("HIGH").asDouble()).isEqualTo(50);
        assertThat(page.get("CCY").toString()).contains("EUR").contains("GBP").contains("USD");
        assertThat(page.get("FIRST").asDouble()).as("the member that sorts first: T-1").isEqualTo(10);
        assertThat(page.get("members").size()).as("listed members are cut to max-members").isEqualTo(1);
    }

    /** The share as a node masking {@code field} sends it: every value of that name masked, at any depth. */
    static DataNode mask(DataNode d, String field) {
        if (d instanceof DataNode.Obj o) {
            Map<String, Object> out = new LinkedHashMap<>();
            o.fields().forEach((k, v) -> out.put(k, k.equals(field) ? DataNode.masked() : mask(v, field)));
            return DataNode.of(out);
        }
        return d;
    }
}
