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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/**
 * The derived kinds' groupings and analytics features (docs/guides/PACK_DEVELOPER_GUIDE.md Part III): a member in several groups
 * (also-group-by), a page over everything (unlisted), the median, fields worked out over the page (then), sorted lists and the
 * group's own tree.
 */
class DerivedPageTest {

    /** Five legal entities in two ownership groups: G1 (a three-level tree, one member whose parent is outside) and G2. */
    static class Entities implements EntityReader {
        @Override
        public List<EntityRef> list(String kind, AsOf asOf, int limit) {
            return kind.equals("lei-entity") ? List.of("G1", "A", "B", "C", "G2").stream().map(i -> EntityRef.of("lei-entity", i)).toList() : List.of();
        }

        @Override
        public Map<EntityRef, EntityDocument> read(Collection<EntityRef> refs, AsOf asOf) {
            Map<EntityRef, EntityDocument> out = new LinkedHashMap<>();
            put(out, "G1", "G1", null, "US", 100, 2.0, false);
            put(out, "A", "G1", "G1", "GB", 50, 4.0, true);
            put(out, "B", "G1", "A", "GB", 30, null, false);
            put(out, "C", "G1", "OUTSIDE", "DE", 20, 1.0, true);
            put(out, "G2", "G2", null, "FR", 10, 3.0, false);
            return out;
        }

        private static void put(Map<EntityRef, EntityDocument> out, String id, String group, String parent, String country, double gdp,
                Double growth, boolean late) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("LEI", id);
            d.put("NAME", "Entity " + id);
            d.put("LEI_GROUP", group);
            d.put("DIRECT_PARENT", parent);
            d.put("LEI_COUNTRY", country);
            d.put("GDP", gdp);
            d.put("GROWTH", growth);
            d.put("RENEWAL_LATE", late);
            EntityRef ref = EntityRef.of("lei-entity", id);
            out.put(ref, new EntityDocument(ref, DataNode.of(d), new Provenance("lake", 1, Instant.now(), false)));
        }
    }

    static Map<String, String> settings() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("grp.from", "lei-entity");
        s.put("grp.group-by", "$.LEI_GROUP");
        s.put("grp.id-field", "GROUP_ID");
        s.put("grp.members", "MEMBER_LEIS");
        s.put("grp.rows-field", "ROWS");
        s.put("grp.max-members", "100000");
        s.put("grp.also-group-by.all", "'ALL'");
        s.put("grp.unlisted", "ALL");
        s.put("grp.fields.MEMBERS", "count");
        s.put("grp.fields.LATE_RENEWALS", "sum $.RENEWAL_LATE ? 1 : 0");
        s.put("grp.fields.COUNTRY_LIST", "distinct $.LEI_COUNTRY");
        s.put("grp.fields.PARENT_NAME", "distinct $.LEI == $.LEI_GROUP ? $.NAME : null");
        s.put("grp.fields.GDP", "sum $.GDP");
        s.put("grp.fields.GROWTH_WEIGHTED", "sum $.GROWTH == null ? null : $.GDP * $.GROWTH");
        s.put("grp.fields.GROWTH_WEIGHT", "sum $.GROWTH == null ? null : $.GDP");
        s.put("grp.fields.GROWTH_MEDIAN", "median $.GROWTH");
        s.put("grp.rows.LEI", "$.LEI");
        s.put("grp.rows.NAME", "$.NAME");
        s.put("grp.rows.PARENT", "$.DIRECT_PARENT");
        s.put("grp.rows.COUNTRY", "$.LEI_COUNTRY");
        s.put("grp.rows.GDP", "$.GDP");
        s.put("grp.tree.id", "LEI");
        s.put("grp.tree.parent", "PARENT");
        s.put("grp.then.NAME", "$.GROUP_ID == 'ALL' ? 'Every group' : first($.PARENT_NAME)");
        s.put("grp.then.COUNTRIES", "size($.COUNTRY_LIST)");
        s.put("grp.then.GROWTH", "$.GROWTH_WEIGHT > 0 ? $.GROWTH_WEIGHTED / $.GROWTH_WEIGHT : null");
        s.put("grp.then.BY_COUNTRY", "group($.ROWS, 'COUNTRY')");
        s.put("grp.then.TOP", "$.ROWS");
        s.put("grp.sort.BY_COUNTRY", "value desc 2");
        s.put("grp.sort.TOP", "GDP desc 2");
        return s;
    }

    private static DerivedSourcePlugin started(Map<String, String> s) {
        DerivedSourcePlugin p = new DerivedSourcePlugin();
        p.start(new DerivedSourcePluginTest.Ctx(s, new Entities()));
        return p;
    }

    @Test
    void aMemberJoinsEveryGroupItsKeysName() throws Exception {
        DerivedSourcePlugin p = started(settings());
        assertThat(p.search("grp", "", 10)).extracting(h -> h.ref().id()).containsExactly("ALL", "G1", "G2");
        DataNode all = p.fetch(EntityRef.of("grp", "ALL")).orElseThrow().data();
        assertThat(all.get("MEMBERS").asDouble()).isEqualTo(5);
        assertThat(all.get("NAME").asText()).isEqualTo("Every group");
        assertThat(all.get("COUNTRIES").asDouble()).isEqualTo(4);
        assertThat(all.get("BY_COUNTRY").size()).isEqualTo(2);                       // worked out from the rows, sorted, the top two
        assertThat(all.get("BY_COUNTRY").get(0).get("label").asText()).isEqualTo("GB");
        assertThat(all.get("MEMBER_LEIS").size()).isZero();                           // unlisted: no ids and no rows kept
        assertThat(all.get("ROWS").size()).isZero();
    }

    @Test
    void thenFieldsAreWorkedOutOverThePage() throws Exception {
        DataNode g1 = started(settings()).fetch(EntityRef.of("grp", "G1")).orElseThrow().data();
        assertThat(g1.get("NAME").asText()).isEqualTo("Entity G1");
        assertThat(g1.get("MEMBERS").asDouble()).isEqualTo(4);
        assertThat(g1.get("LATE_RENEWALS").asDouble()).isEqualTo(2);
        assertThat(g1.get("GDP").asDouble()).isEqualTo(200);
        // GDP-weighted growth over the members that have one (B has none): (100*2 + 50*4 + 20*1) / 170
        assertThat(g1.get("GROWTH").asDouble()).isCloseTo(420.0 / 170, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(g1.get("GROWTH_MEDIAN").asDouble()).isEqualTo(2.0);               // of 1, 2 and 4
        assertThat(g1.get("TOP").size()).isEqualTo(2);
        assertThat(g1.get("TOP").get(0).get("LEI").asText()).isEqualTo("G1");
        assertThat(g1.get("TOP").get(1).get("LEI").asText()).isEqualTo("A");
    }

    @Test
    void theRowsBecomeATreeUnderTheKey() throws Exception {
        DataNode g1 = started(settings()).fetch(EntityRef.of("grp", "G1")).orElseThrow().data();
        assertThat(g1.get("LEVELS").asDouble()).isEqualTo(2);
        Map<String, Double> level = new LinkedHashMap<>();
        for (int i = 0; i < g1.get("ROWS").size(); i++) {
            level.put(g1.get("ROWS").get(i).get("LEI").asText(), g1.get("ROWS").get(i).get("LEVEL").asDouble());
        }
        assertThat(level).containsEntry("G1", 0.0).containsEntry("A", 1.0).containsEntry("B", 2.0).containsEntry("C", 1.0);
        DataNode root = g1.get("TREE").get(0);
        assertThat(root.get("LEI").asText()).isEqualTo("G1");
        List<String> kids = new java.util.ArrayList<>();
        for (int i = 0; i < root.get("CHILDREN").size(); i++) {
            kids.add(root.get("CHILDREN").get(i).get("LEI").asText());
        }
        assertThat(kids).containsExactly("A", "C");                                   // C's parent is outside the group: under the key
        assertThat(root.get("CHILDREN").get(0).get("CHILDREN").get(0).get("LEI").asText()).isEqualTo("B");
    }

    @Test
    void aMistakeInTheSettingsFailsTheStart() {
        Map<String, String> s = settings();
        s.put("grp.sort.TOP", "GDP sideways");
        assertThatThrownBy(() -> started(s)).hasMessageContaining("grp.sort.TOP");
        Map<String, String> t = settings();
        t.remove("grp.tree.parent");
        assertThatThrownBy(() -> started(t)).hasMessageContaining("grp.tree");
        Map<String, String> u = settings();
        u.put("grp.then.BAD", "$.ROWS[");
        assertThatThrownBy(() -> started(u)).hasMessageContaining("grp.then.BAD");
        Map<String, String> v = settings();
        v.put("grp.fields.X", "middle $.GDP");
        assertThatThrownBy(() -> started(v)).hasMessageContaining("median");
    }

    @Test
    void aSearchFindsAGroupByItsNameWhenTheKindNamesSearchFields() throws Exception {
        Map<String, String> s = settings();
        s.put("grp.search-fields", "NAME");
        DerivedSourcePlugin p = started(s);
        assertThat(p.search("grp", "entity g2", 10)).extracting(h -> h.ref().id()).containsExactly("G2");
        assertThat(p.search("grp", "entity g2", 10).get(0).title()).isEqualTo("Entity G2");
        assertThat(p.search("grp", "", 10)).hasSize(3);
        assertThat(p.search("grp", "*", 10)).hasSize(3);                                  // * lists every page
        assertThat(started(settings()).search("grp", "entity", 10)).isEmpty();          // without search-fields: ids only
    }

    @Test
    void theMedianOfAnEvenCountIsTheMeanOfTheMiddleTwo() {
        assertThat(DerivedKind.median(List.of(4.0, 1.0, 3.0, 2.0))).isEqualTo(2.5);
        assertThat(DerivedKind.median(List.of())).isNull();
    }
}
