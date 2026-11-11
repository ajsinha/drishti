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
package com.ash.drishti.packs.bindings;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.kernel.api.data.BindingState;
import com.ash.drishti.packs.bindings.BindingResolver.Origin;
import com.ash.drishti.packs.bindings.BindingResolver.Source;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The resolution order (site → distribution default → convention → demo → unbound), the states, and the functions an unserved need switches off. */
class BindingResolverTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    /** Facts a test sets by hand. */
    static class Fake implements BindingResolver.Facts {
        final Map<String, List<Source>> sources = new HashMap<>();
        final Map<String, String> routes = new HashMap<>();
        final Map<String, LocalDate> latest = new HashMap<>();
        final Map<String, String> mismatch = new HashMap<>();
        boolean demo = true;

        @Override
        public Set<String> kinds() {
            return Set.of("trade", "scenario-pv", "pnl-vector", "var-limit", "position", "desk-pnl");
        }

        @Override
        public List<Source> sources(String kind) {
            return sources.getOrDefault(kind, List.of());
        }

        @Override
        public Set<String> connectors() {
            return Set.of("trade-endur", "risk-store", "demo", "lake-a", "var-positions");
        }

        @Override
        public Optional<String> defaultRoute(String kind) {
            return Optional.ofNullable(routes.get(kind));
        }

        @Override
        public Optional<LocalDate> latest(String kind) {
            return Optional.ofNullable(latest.get(kind));
        }

        @Override
        public Optional<String> cadence(String kind) {
            return Optional.of("daily");
        }

        @Override
        public Optional<String> mismatch(String kind) {
            return Optional.ofNullable(mismatch.get(kind));
        }

        @Override
        public Optional<String> hint(String kind) {
            return Optional.of("risk." + kind);
        }

        @Override
        public boolean demoEnabled() {
            return demo;
        }

        @Override
        public LocalDate today() {
            return TODAY;
        }
    }

    private static Source real(String name) {
        return new Source(name, "delta", "risklake", false, false);
    }

    private static Source demo() {
        return new Source("demo", "demo", "demo", true, false);
    }

    private static DataNeeds needs() {
        List<DataNeeds.Need> n = new java.util.ArrayList<>();
        Map<String, String> computed = new HashMap<>();
        DataNeeds.read(Map.of("module", "banking", "needs", List.of(
                Map.of("kind", "trade", "use", List.of("read"), "required", true, "used-by", List.of("TRD", "VRES")),
                Map.of("kind", "scenario-pv", "use", List.of("read"), "any-of", "A", "used-by", List.of("VRES", "POS")),
                Map.of("kind", "pnl-vector", "use", List.of("read"), "any-of", "A", "used-by", List.of("VRES", "POS")),
                Map.of("kind", "var-limit", "use", List.of("read"), "required", false, "used-by", List.of("VLIM")),
                Map.of("kind", "desk-pnl", "use", List.of("read"), "required", Map.of("for", List.of("DPNL")), "demo", false, "used-by", List.of("DPNL"))),
                "computed", List.of(Map.of("kind", "position"))), n, computed);
        return new DataNeeds(n, computed, List.of());
    }

    @Test
    void firstHitWinsInTheDesignedOrder() {
        Fake f = new Fake();
        f.sources.put("trade", List.of(real("trade-endur"), real("lake-a")));
        f.sources.put("scenario-pv", List.of(real("risk-store"), demo()));
        f.sources.put("pnl-vector", List.of(real("lake-a")));
        f.sources.put("var-limit", List.of(demo()));
        f.routes.put("scenario-pv", "risk-store");
        Bindings site = Bindings.parse(Map.of("bindings", Map.of("trade", Map.of("read", List.of("trade-endur")))));

        BindingResolver.Result r = BindingResolver.resolve(site, needs(), f);
        assertThat(r.kinds().get("trade").origin()).isEqualTo(Origin.SITE);
        assertThat(r.kinds().get("trade").state()).isEqualTo(BindingState.BOUND);
        assertThat(r.kinds().get("trade").read()).containsExactly("trade-endur");
        assertThat(r.kinds().get("scenario-pv").origin()).as("the distribution's default: the pack's route").isEqualTo(Origin.DEFAULT);
        assertThat(r.kinds().get("pnl-vector").state()).as("a lake holds it, nobody bound it").isEqualTo(BindingState.AUTO);
        assertThat(r.kinds().get("pnl-vector").reason()).contains("confirm");
        assertThat(r.kinds().get("var-limit").state()).isEqualTo(BindingState.DEMO);
        assertThat(r.kinds().get("desk-pnl").state()).isEqualTo(BindingState.UNBOUND);
        assertThat(r.kinds().get("position").state()).isEqualTo(BindingState.COMPUTED);
        assertThat(r.off()).containsOnlyKeys("DPNL");
    }

    @Test
    void anUnboundRequiredNeedSwitchesOffOnlyItsFunctions() {
        Fake f = new Fake();
        f.sources.put("trade", List.of(real("trade-endur")));
        BindingResolver.Result r = BindingResolver.resolve(Bindings.NONE, needs(), f);
        assertThat(r.off()).containsOnlyKeys("VRES", "POS", "DPNL");          // the any-of group has no member; trade is served
        assertThat(r.off().get("VRES")).contains("scenario-pv or pnl-vector").contains("ask your data administrator");
        assertThat(r.off()).doesNotContainKey("TRD").doesNotContainKey("VLIM");  // optional var-limit switches nothing off

        f.sources.put("pnl-vector", List.of(real("lake-a")));
        assertThat(BindingResolver.resolve(Bindings.NONE, needs(), f).off()).as("one member of the group serves it").containsOnlyKeys("DPNL");
    }

    @Test
    void aKindTheSiteDoesNotOfferSwitchesNothingOff() {
        Fake f = new Fake() {
            @Override
            public Set<String> kinds() {
                return Set.of("trade", "var-limit", "position");          // the packs of scenario-pv, pnl-vector and desk-pnl are not loaded
            }
        };
        f.sources.put("trade", List.of(real("trade-endur")));
        BindingResolver.Result r = BindingResolver.resolve(Bindings.NONE, needs(), f);
        assertThat(r.off()).isEmpty();
        assertThat(r.kinds().get("desk-pnl").state()).as("Health still says so").isEqualTo(BindingState.UNBOUND);
        Bindings site = Bindings.NONE.with("desk-pnl", Bindings.binding("desk-pnl", Map.of("read", List.of("lake-a"))));
        assertThat(BindingResolver.resolve(site, needs(), f).off()).as("a kind the site binds is offered").containsOnlyKeys("DPNL");
    }

    @Test
    void demoOnlyWhileDemoIsOnAndTheNeedAllowsIt() {
        Fake f = new Fake();
        f.sources.put("var-limit", List.of(demo()));
        f.sources.put("desk-pnl", List.of(demo()));
        assertThat(BindingResolver.resolve(Bindings.NONE, needs(), f).kinds().get("desk-pnl").state()).as("its need says demo: false")
                .isEqualTo(BindingState.UNBOUND);
        f.demo = false;
        BindingResolver.Resolution vl = BindingResolver.resolve(Bindings.NONE, needs(), f).kinds().get("var-limit");
        assertThat(vl.state()).isEqualTo(BindingState.UNBOUND);
        assertThat(vl.reason()).contains("demo is off");
    }

    @Test
    void staleMismatchAndABindingToWhatIsNotRunning() {
        Fake f = new Fake();
        f.sources.put("trade", List.of(real("trade-endur")));
        f.sources.put("scenario-pv", List.of(real("risk-store")));
        f.latest.put("trade", TODAY.minusDays(6));
        f.mismatch.put("scenario-pv", "BASE_PV missing");
        Bindings site = Bindings.parse(Map.of("bindings", Map.of("pnl-vector", Map.of("read", List.of("lake-a")),
                "var-limit", Map.of("read", List.of("nowhere")), "position", Map.of("read", List.of("risk-store")))));
        BindingResolver.Result r = BindingResolver.resolve(site, needs(), f);
        assertThat(r.kinds().get("trade").state()).isEqualTo(BindingState.STALE);
        assertThat(r.kinds().get("trade").state().usable()).isTrue();
        assertThat(r.kinds().get("scenario-pv").state()).isEqualTo(BindingState.MISMATCH);
        assertThat(r.kinds().get("pnl-vector").state()).isEqualTo(BindingState.UNBOUND);
        assertThat(r.kinds().get("pnl-vector").reason()).contains("none of which is running");
        assertThat(r.off()).containsKeys("VRES", "POS");                       // a mismatch is unbound for its needs
        assertThat(r.problems()).anyMatch(p -> p.contains("'nowhere'")).anyMatch(p -> p.contains("position is computed"));
        assertThat(r.kinds().get("var-limit").origin()).as("a binding to connectors no file defines is set aside").isNotEqualTo(Origin.SITE);
        assertThat(r.problems()).anyMatch(p -> p.contains("set aside"));
    }
}
