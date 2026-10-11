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
package com.ash.drishti.server.bi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.graph.GraphProperties;
import com.ash.drishti.graph.ReferenceCatalog;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.SourceGateway;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * A dataset started from pack kinds: kinds become tables, the packs' link graph becomes relationships (across packs), relationships are proposed from
 * the data with their match rate, and the draft is a valid dataset. The packs are two small made-up ones so the test knows every value.
 */
class PackModelsTest {

    /** Pack {@code shop} owns orders and regions; pack {@code crm} owns customers. Graph: an order's {@code customer} field is a customer. */
    private final PackRegistry registry = registry();
    private final ReferenceCatalog graph = new ReferenceCatalog(new GraphProperties(null, Map.of("customer", new GraphProperties.FieldRef("customer", "Customer")), null, null, null));
    private final Lake lake = new Lake();

    private static PackRegistry registry() {
        PackRegistry r = new PackRegistry(new MockEnvironment());
        r.replace(List.of(
                new Pack("shop", "1", "Shop", "", Path.of("target/none"), Map.of("kinds", List.of("order", "region"), "routes", Map.of("order", "orders-store"))),
                new Pack("crm", "1", "CRM", "", Path.of("target/none"), Map.of("kinds", List.of("customer", "order-line")))));
        return r;
    }

    /** What the connectors hold: orders promote columns, customers and regions promote none (their documents are read). */
    private static class Lake implements SourceGateway {
        final List<String> customerIds = List.of("C1", "C2", "C3", "C4", "C5");
        final SourcePlugin orders = new Base("orders-store", Set.of("order")) {
            @Override
            public Set<String> columnar(String kind) {
                return Set.of("customer", "region", "amount", "placed", "note");
            }

            @Override
            public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
                String[] ids = {"O1", "O2", "O3", "O4", "O5", "O6"};
                return Optional.of(new ColumnSet(ids, Map.of("amount", new double[] {10, 20, 30, 40, 50, 60}),
                        Map.of("customer", new String[] {"C1", "C2", "C2", "C3", "C4", "C5"}, "region", new String[] {"EU", "EU", "US", "US", "ASIA", "ASIA"},
                                "placed", new String[] {"2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05", "2026-10-06"},
                                "note", new String[] {"a", "b", "c", "d", "e", "f"}),
                        LocalDate.of(2026, 10, 6)));
            }
        };
        final SourcePlugin other = new Base("other", Set.of()) {};

        @Override
        public Optional<SourcePlugin> plugin(String connector, String kind) {
            return switch (kind) {
                case "order" -> Optional.of(orders);
                case "customer", "region" -> Optional.of(other);
                default -> Optional.empty();
            };
        }

        @Override
        public List<EntityRef> ids(String kind, LocalDate date, int limit) {
            return ("customer".equals(kind) ? customerIds : List.of("EU", "US", "ASIA")).stream().limit(limit).map(i -> EntityRef.of(kind, i)).toList();
        }

        @Override
        public Map<EntityRef, EntityDocument> read(Collection<EntityRef> refs, LocalDate date) {
            Map<EntityRef, EntityDocument> out = new java.util.LinkedHashMap<>();
            for (EntityRef r : refs) {
                out.put(r, new EntityDocument(r, DataNode.of(Map.of("name", "Name of " + r.id(), "tier", "gold")), new Provenance("test", 1, java.time.Instant.now(), false)));
            }
            return out;
        }

        @Override
        public LocalDate currentDate() {
            return LocalDate.of(2026, 10, 6);
        }
    }

    private abstract static class Base implements SourcePlugin {
        private final String name;
        private final Set<String> kinds;

        Base(String name, Set<String> kinds) {
            this.name = name;
            this.kinds = kinds;
        }

        @Override
        public PluginManifest manifest() {
            return new PluginManifest(name, "1", kinds, new SourceCapabilities(false, false, false, true));
        }

        @Override
        public void start(SourceContext context) {}

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }
    }

    private PackModels models(Set<String> open) {
        return new PackModels(registry, graph, lake, QueryProperties.defaults("target/none"), open::contains);
    }

    @Test
    void everyKindTheReaderMayOpenIsATableWithTypedColumns() {
        List<PackModels.TableInfo> offer = models(Set.of("order", "customer", "region")).offer();
        assertThat(offer).extracting(PackModels.TableInfo::kind).containsExactly("customer", "order", "region");
        PackModels.TableInfo orders = offer.stream().filter(t -> t.kind().equals("order")).findFirst().orElseThrow();
        assertThat(orders.table()).isEqualTo("order");
        assertThat(orders.connector()).isEqualTo("orders-store");
        assertThat(orders.data()).isTrue();
        assertThat(orders.columns()).extracting(PackModels.ColumnInfo::name).contains("customer", "amount", "placed");
        assertThat(orders.columns().stream().filter(c -> c.name().equals("amount")).findFirst().orElseThrow().type()).isEqualTo("decimal");
        assertThat(orders.columns().stream().filter(c -> c.name().equals("placed")).findFirst().orElseThrow().type()).isEqualTo("date");
        assertThat(models(Set.of("customer")).offer()).extracting(PackModels.TableInfo::kind).containsExactly("customer");           // a kind the reader may not open is not offered
    }

    @Test
    void thePacksLinkGraphBecomesACrossPackRelationship() {
        PackModels.Draft d = models(Set.of("order", "customer", "region")).start(List.of("order", "customer"), "shop-model");
        assertThat(d.relationships()).hasSize(1);
        PackModels.Link l = d.relationships().get(0);
        assertThat(l.fromTable() + "." + l.fromColumn()).isEqualTo("order.customer");
        assertThat(l.toTable() + "." + l.toColumn()).isEqualTo("customer.id");
        assertThat(l.crossPack()).isTrue();
        assertThat(l.cardinality()).isEqualTo("many-to-one");
        assertThat(l.basis()).contains("link graph");
        assertThat(d.yaml()).contains("dataset: shop-model").contains("from: order.customer").contains("to: customer.id").contains("kind: order");
    }

    @Test
    void theDraftIsAValidDatasetWithStarterMeasures() {
        PackModels.Draft d = models(Set.of("order", "customer")).start(List.of("order", "customer"), "shop-model");
        DatasetParser.Result r = DatasetParser.parse(d.yaml());
        assertThat(r.problems().stream().filter(DatasetParser.Problem::error).toList()).isEmpty();
        assertThat(r.dataset().tables()).containsOnlyKeys("order", "customer");
        assertThat(r.dataset().measures()).containsKeys("Count of order", "Total order amount");
        assertThat(r.dataset().tables().get("order").columns().get("amount").type()).isEqualTo("decimal");
        assertThat(r.dataset().relationships()).hasSize(1);
    }

    @Test
    void relationshipsAreProposedFromTheDataWithTheirMatchRate() {
        // region has no declared link from order, but every distinct region of the orders is a region id: 3 of 3
        PackModels.Draft d = models(Set.of("order", "customer", "region")).start(List.of("order", "region"), "x");
        assertThat(d.relationships()).isEmpty();
        assertThat(d.proposals()).hasSize(1);
        PackModels.Link p = d.proposals().get(0);
        assertThat(p.fromTable() + "." + p.fromColumn()).isEqualTo("order.region");
        assertThat(p.toTable()).isEqualTo("region");
        assertThat(p.matchRate()).isEqualTo(1.0);
        assertThat(p.matched()).isEqualTo(3);
        assertThat(p.sampled()).isEqualTo(3);
        assertThat(p.cardinality()).isEqualTo("many-to-one");
        assertThat(p.basis()).startsWith("inferred: 3 of 3");
    }

    @Test
    void aColumnThatOnlySometimesMatchesIsProposedOnlyAboveTheThreshold() {
        // order.customer values C1..C5 against a customer table that only has C1..C3 (3 of 5 = 0.6, under 0.8)
        Lake thin = new Lake() {
            @Override
            public List<EntityRef> ids(String kind, LocalDate date, int limit) {
                return "customer".equals(kind) ? List.of(EntityRef.of(kind, "C1"), EntityRef.of(kind, "C2"), EntityRef.of(kind, "C3")) : super.ids(kind, date, limit);
            }
        };
        ReferenceCatalog none = new ReferenceCatalog(new GraphProperties(null, null, null, null, null));
        PackModels m = new PackModels(registry, none, thin, QueryProperties.defaults("target/none"), k -> true);
        assertThat(m.start(List.of("order", "customer"), "x").proposals()).extracting(PackModels.Link::fromColumn).doesNotContain("customer");
        PackModels loose = new PackModels(registry, none, thin, QueryProperties.defaults("target/none").withCacheEntries(1), k -> true);
        assertThat(loose.proposals(List.of("order", "customer"))).isEmpty();
    }

    @Test
    void aUniqueColumnIsProposedOneToOne() {
        Lake unique = new Lake();
        PackModels m = new PackModels(registry, new ReferenceCatalog(new GraphProperties(null, null, null, null, null)), unique, QueryProperties.defaults("target/none"), k -> true);
        // order.note is unique per order but matches nothing; order.customer repeats (C2 twice) so it is many-to-one when proposed
        List<PackModels.Link> p = m.proposals(List.of("order", "customer"));
        assertThat(p).extracting(PackModels.Link::fromColumn).contains("customer");
        assertThat(p.stream().filter(l -> l.fromColumn().equals("customer")).findFirst().orElseThrow().cardinality()).isEqualTo("many-to-one");
    }

    @Test
    void namesAreMadeSafeAndKindsOfTheSameNameAreTold() {
        assertThat(PackModels.columnName("counterparty.id")).isEqualTo("counterpartyId");
        assertThat(PackModels.columnName("risk.dv01")).isEqualTo("riskDv01");
        assertThat(PackModels.columnName("id")).isEqualTo("idValue");                     // id is every table's own column
        assertThat(PackModels.columnName("3d")).isEqualTo("c3d");
        Set<String> taken = new java.util.HashSet<>();
        assertThat(PackModels.tableName("netting-set", "cpr", taken)).isEqualTo("netting_set");
        assertThat(PackModels.tableName("netting-set", "other-pack", taken)).isEqualTo("other_pack_netting_set");
    }

    @Test
    void aKindTheReaderMayNotOpenCannotBeStartedFrom() {
        assertThatThrownBy(() -> models(Set.of("order")).start(List.of("customer"), "x")).isInstanceOf(DrishtiException.class).hasMessageContaining("not a kind you may open");
        assertThatThrownBy(() -> models(Set.of("order")).start(List.of(), "x")).isInstanceOf(DrishtiException.class);
    }

    @Test
    void aKindWithNoDataSaysItsTypesAreGuesses() {
        ArrayList<String> kinds = new ArrayList<>(List.of("customer"));
        PackModels.Draft d = models(Set.of("customer")).start(kinds, "x");
        assertThat(d.warnings()).anyMatch(w -> w.contains("customer") && w.contains("no columns are known") || w.contains("guesses"));
    }
}
