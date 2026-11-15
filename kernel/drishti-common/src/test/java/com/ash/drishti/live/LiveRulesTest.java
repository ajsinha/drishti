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
package com.ash.drishti.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.ErrorCode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A kind's live rules from its contract (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 2.3): defaults, the block, its checks. */
class LiveRulesTest {

    private static Map<String, Object> contract(String kind, String klass, Map<String, Object> live) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("kind", kind);
        c.put("class", klass);
        c.put("key", List.of("TRADE_ID"));
        c.put("fields", List.of(Map.of("name", "TRADE_ID"), Map.of("name", "TRADE_VERSION"), Map.of("name", "SOURCE_UPDATE_TIME"),
                Map.of("name", "UPDATED_BY"), Map.of("name", "BUSINESS_DATE")));
        c.put("nested", Map.of("LEGS[]", List.of(Map.of("name", "NOTIONAL"))));
        if (live != null) {
            c.put("live", live);
        }
        return c;
    }

    @Test
    void aContractWithoutTheBlockTakesTheSafeDefaults() {
        LiveRules.Parsed p = LiveRules.parse(contract("trade", "fundamental", null), null);
        assertThat(p.ok()).isTrue();
        LiveRules r = p.rules();
        assertThat(r.allowed()).isTrue();
        assertThat(r.byArrival()).isTrue();
        assertThat(r.partial()).isEqualTo(LiveRules.Partial.WHOLE);
        assertThat(r.apply()).isEqualTo(LiveRules.Apply.OVERLAY);
        assertThat(r.absorb()).isEqualTo(LiveRules.Absorb.RESET);
        assertThat(r.validate()).isEqualTo(LiveRules.Validate.STRICT);
        assertThat(r.closedDates()).isEqualTo(LiveRules.ClosedDates.REJECT);
        assertThat(r.ttl()).isEqualTo(Duration.ofDays(2));
        assertThat(r.maxRows()).isEqualTo(2_000_000L);
    }

    @Test
    void theTradeBlockOfTheDesignReads() {
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("order", List.of("TRADE_VERSION", "SOURCE_UPDATE_TIME"));
        live.put("changed-at", "SOURCE_UPDATE_TIME");
        live.put("changed-by", "UPDATED_BY");
        live.put("as-of", "BUSINESS_DATE");
        live.put("partial", "whole");
        live.put("apply", "overlay");
        live.put("conflate", "none");
        live.put("absorb", "by-order");
        live.put("retention", Map.of("ttl", "2d", "max-rows", 2_000_000));
        live.put("validate", "strict");
        LiveRules.Parsed p = LiveRules.parse(contract("trade", "fundamental", live), null);
        assertThat(p.problems()).isEmpty();
        assertThat(p.rules().order()).containsExactly("TRADE_VERSION", "SOURCE_UPDATE_TIME");
        assertThat(p.rules().absorb()).isEqualTo(LiveRules.Absorb.BY_ORDER);
        assertThat(p.rules().changedBy()).isEqualTo("UPDATED_BY");
        assertThat(p.rules().conflate()).isEqualTo(Duration.ZERO);
    }

    @Test
    void everyFieldTheBlockNamesIsInTheContract() {
        LiveRules.Parsed p = LiveRules.parse(contract("trade", "fundamental", Map.of("order", List.of("TRADE_VERSON"), "changed-by", "LEGS[].NOTIONAL")), null);
        assertThat(p.ok()).isFalse();
        assertThat(p.problems()).hasSize(1);
        assertThat(p.problems().get(0)).contains("order names TRADE_VERSON, which is not a field of the contract").contains("DRS-1060");
        assertThat(p.rules().allowed()).as("unusable rules: no feed until fixed").isFalse();
    }

    @Test
    void wordsDurationsAndNumbersAreChecked() {
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("partial", "deltas");
        live.put("conflate", "fast");
        live.put("retention", Map.of("ttl", "2 weeks", "max-rows", "lots"));
        live.put("colour", "blue");
        LiveRules.Parsed p = LiveRules.parse(contract("trade", "fundamental", live), null);
        assertThat(String.join("\n", p.problems())).contains("partial is one of whole, merge").contains("conflate is a duration")
                .contains("retention.ttl is a duration").contains("retention.max-rows is a whole number").contains("unknown key 'colour'");
    }

    @Test
    void byOrderNeedsAnOrderAndBothIsTheSitesChoice() {
        LiveRules.Parsed p = LiveRules.parse(contract("trade", "fundamental", Map.of("absorb", "by-order", "apply", "both")), null);
        assertThat(String.join("\n", p.problems())).contains("absorb: by-order needs an order:").contains("apply: both is a site's choice");
    }

    @Test
    void aComputedKindTakesNoFeed() {
        assertThat(LiveRules.parse(contract("position", "computed", null), null).rules().allowed()).isFalse();
        LiveRules.Parsed p = LiveRules.parse(contract("position", "computed", Map.of("allowed", true)), null);
        assertThat(p.problems()).singleElement().asString().contains("a computed kind is live by derivation");
        assertThat(LiveRules.parse(contract("position", "computed", Map.of("allowed", false)), null).ok()).isTrue();
    }

    @Test
    void onlyTheListedLandedKindsRestateClosedDates() {
        Map<String, Object> live = Map.of("apply", "land", "closed-dates", "restate", "order", List.of("SOURCE_UPDATE_TIME"));
        assertThat(LiveRules.parse(contract("scenario-pv", "input", live), null).ok()).isTrue();
        assertThat(LiveRules.parse(contract("trade", "fundamental", live), null).problems()).singleElement().asString()
                .contains("restate is allowed only for pnl-vector, scenario-pv");
        assertThat(LiveRules.parse(contract("trade", "fundamental", live), Set.of("trade")).ok()).as("the site's list").isTrue();
        assertThat(LiveRules.parse(contract("scenario-pv", "input", Map.of("closed-dates", "restate")), null).problems()).singleElement()
                .asString().contains("for a landed kind");
    }

    @Test
    void aLiveKindNeedsAKey() {
        Map<String, Object> c = contract("note", "input", Map.of("allowed", true));
        c.put("key", List.of());
        assertThat(LiveRules.parse(c, null).problems()).singleElement().asString().contains("the kind has no key");
    }

    @Test
    void everyRejectReasonHasItsOwnCode() {
        assertThat(LiveReject.of("key-mismatch")).contains(LiveReject.KEY_MISMATCH);
        assertThat(LiveReject.KEY_MISMATCH.code()).isEqualTo(ErrorCode.LIVE_KEY_MISMATCH);
        assertThat(LiveReject.PATCH_WITHOUT_BASE.reason()).isEqualTo("patch-without-base");
        assertThat(java.util.Arrays.stream(LiveReject.values()).map(r -> r.code().code()).distinct()).hasSize(LiveReject.values().length);
        assertThat(LiveReject.of("nonsense")).isEmpty();
    }
}
