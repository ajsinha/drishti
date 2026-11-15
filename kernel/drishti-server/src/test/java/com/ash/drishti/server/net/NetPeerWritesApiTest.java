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
package com.ash.drishti.server.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.net.security.OnBehalfOf;
import com.ash.drishti.server.edit.EditRuntime;
import com.ash.drishti.server.placement.PlacementService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The node-to-node write endpoints answered as another node's call (the request carries the attribute the cluster-credential filter sets for a
 * verified call; the filter itself is proved on a real cluster in NetAdminWritesClusterTest): the change a node forwards to the owner of its
 * lane ({@code /net/edits/append}) is appended, and a notice of an applied change ({@code /net/edits/changed}) reaches the live feed;
 * users' state from a peer ({@code /net/state}) is applied and read back, the newest write winning; a peer's placement ({@code /net/placement})
 * is adopted only at a higher version; a scattered view's share ({@code /net/pivot/partials}) is computed for the person named by the
 * on-behalf-of token and refused without one. Each endpoint refuses a caller that is not a node with a DRS code, and a bad body with a 4xx.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=trading,counterparty-risk", "drishti.edit.file=../../config/edit/edit.yaml", "DRISHTI_EDIT_ENABLED=true",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/peerwrites-${random.uuid}.db",
        "drishti.placement.file=${drishti.test.dir}/peerwrites-${random.uuid}/placement.yaml"})
@AutoConfigureMockMvc(addFilters = false)
class NetPeerWritesApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Principal TIA = new Principal("tia", List.of("trader"));

    @Autowired MockMvc mvc;
    @Autowired EditRuntime edits;
    @Autowired com.ash.drishti.identity.PreferenceStore preferences;
    @Autowired PlacementService placement;

    /** A request as a verified node's call (peer true) or as a plain caller's (peer false). */
    private MvcResult call(String method, String url, Object body, boolean peer, Principal who, Object claims) throws Exception {
        MockHttpServletRequestBuilder b = "GET".equals(method) ? get(url) : post(url);
        b.requestAttr(Principal.ATTRIBUTE, who);
        if (peer) {
            b.requestAttr(NetCallFilter.PEER, "n-other-node");
        }
        if (claims != null) {
            b.requestAttr(NetCallFilter.CLAIMS, claims);
        }
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : JSON.writeValueAsString(body));
        }
        return mvc.perform(b).andReturn();
    }

    private static String text(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(text(r)).isEqualTo(200);
        return JSON.readTree(text(r));
    }

    private void refused(MvcResult r, int status, String why) throws Exception {
        assertThat(r.getResponse().getStatus()).as(text(r)).isEqualTo(status);
        assertThat(text(r)).contains("DRS-");
        if (why != null) {
            assertThat(text(r)).contains(why);
        }
    }

    @Test
    void aChangeForwardedToTheLaneOwnerIsAppendedAndANoticeReachesTheFeed() throws Exception {
        String id = "chg-" + UUID.randomUUID();
        Map<String, Object> change = new java.util.LinkedHashMap<>();
        change.put("id", id);
        change.put("key", "portfolio-trades:BOOK-RATES-2");
        change.put("kind", "portfolio-trades");
        change.put("entity", "BOOK-RATES-2");
        change.put("businessDate", "2026-11-12");
        change.put("items", List.of(Map.of("path", "$.notional", "from", 1000, "to", 2000)));
        change.put("target", "overlay");
        change.put("reason", "forwarded");
        change.put("by", "tia");
        change.put("at", "2026-11-14T10:00:00Z");
        change.put("approvals", 0);
        change.put("override", false);
        JsonNode done = ok(call("POST", "/api/v1/net/edits/append", change, true, TIA, null));
        assertThat(done.path("id").asText()).isEqualTo(id);
        assertThat(done.path("node").asText()).isEqualTo(edits.node());
        assertThat(done.path("seq").asLong()).isPositive();
        assertThat(edits.log().get(id)).as("the change is in this node's log").isPresent();
        assertThat(edits.log().get(id).orElseThrow().change().by()).isEqualTo("tia");
        ok(call("GET", "/api/v1/changes/" + id, null, false, TIA, null));       // and the change API names it

        // only as the person who made it, only from a node
        refused(call("POST", "/api/v1/net/edits/append", change, true, new Principal("mallory", List.of("trader")), null), 403, null);
        refused(call("POST", "/api/v1/net/edits/append", change, false, TIA, null), 403, "between nodes only");
        assertThat(edits.log().get("chg-never")).isEmpty();
        MvcResult junk = call("POST", "/api/v1/net/edits/append", "{\"id\":", true, TIA, null);
        assertThat(junk.getResponse().getStatus()).isBetween(400, 499);

        // a notice of an applied change: told to the live feed, so a viewer of the page reads it again
        List<Map<String, Object>> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        AutoCloseable sub = edits.feed().listen(e -> heard.add(((com.ash.drishti.server.edit.EditFeed.Event) e).body()));
        try {
            assertThat(ok(call("POST", "/api/v1/net/edits/changed", Map.of("kind", "portfolio-trades", "entity", "BOOK-RATES-2", "type", "applied", "id", id),
                    true, TIA, null)).path("ok").asBoolean()).isTrue();
            assertThat(heard).anySatisfy(n -> assertThat(n).containsEntry("id", id).containsEntry("entity", "BOOK-RATES-2"));
            // the same notice again: told again, no harm
            assertThat(ok(call("POST", "/api/v1/net/edits/changed", Map.of("kind", "portfolio-trades", "entity", "BOOK-RATES-2", "type", "applied", "id", id),
                    true, TIA, null)).path("ok").asBoolean()).isTrue();
        } finally {
            sub.close();
        }
        refused(call("POST", "/api/v1/net/edits/changed", Map.of("kind", "x", "entity", "y"), false, TIA, null), 403, "between nodes only");
    }

    @Test
    void aPeersUserStateIsAppliedTheNewestWriteWinsAndNoOneButANodeMaySendIt() throws Exception {
        long t = System.currentTimeMillis();
        Map<String, Object> change = Map.of("user", "rita", "namespace", "layouts", "key", "peer-write.trade", "value", Map.of("rows", List.of("DESK")),
                "at", t, "origin", "n-other-node", "catchUp", false);
        assertThat(ok(call("POST", "/api/v1/net/state", Map.of("changes", List.of(change)), true, TIA, null)).path("applied").asInt()).isEqualTo(1);
        assertThat(preferences.get("rita", "layouts", "peer-write.trade")).hasValueSatisfying(v -> assertThat(v.toString()).contains("DESK"));
        // an older write loses; a newer one wins
        Map<String, Object> older = Map.of("user", "rita", "namespace", "layouts", "key", "peer-write.trade", "value", Map.of("rows", List.of("OLD")),
                "at", t - 5_000, "origin", "n-other-node", "catchUp", false);
        assertThat(ok(call("POST", "/api/v1/net/state", Map.of("changes", List.of(older)), true, TIA, null)).path("applied").asInt()).isZero();
        assertThat(preferences.get("rita", "layouts", "peer-write.trade").orElseThrow().toString()).doesNotContain("OLD");
        Map<String, Object> newer = Map.of("user", "rita", "namespace", "layouts", "key", "peer-write.trade", "value", Map.of("rows", List.of("BOOK")),
                "at", t + 5_000, "origin", "n-other-node", "catchUp", false);
        assertThat(ok(call("POST", "/api/v1/net/state", Map.of("changes", List.of(newer)), true, TIA, null)).path("applied").asInt()).isEqualTo(1);
        assertThat(preferences.get("rita", "layouts", "peer-write.trade").orElseThrow().toString()).contains("BOOK");
        // the same one again is no news (idempotent); a delete (null value) removes it
        assertThat(ok(call("POST", "/api/v1/net/state", Map.of("changes", List.of(newer)), true, TIA, null)).path("applied").asInt()).isEqualTo(1);
        Map<String, Object> delete = new java.util.HashMap<>(newer);
        delete.put("value", null);
        delete.put("at", t + 9_000);
        assertThat(ok(call("POST", "/api/v1/net/state", Map.of("changes", List.of(delete)), true, TIA, null)).path("applied").asInt()).isEqualTo(1);
        assertThat(preferences.get("rita", "layouts", "peer-write.trade")).isEmpty();
        // a namespace that is not copied (alerts act) is skipped, never applied
        Map<String, Object> alert = Map.of("user", "rita", "namespace", "alerts", "key", "big", "value", Map.of("when", "mtm > 1m"), "at", t, "origin", "n-other-node",
                "catchUp", false);
        assertThat(ok(call("POST", "/api/v1/net/state", Map.of("changes", List.of(alert)), true, TIA, null)).path("applied").asInt()).isZero();
        assertThat(preferences.get("rita", "alerts", "big")).isEmpty();
        refused(call("POST", "/api/v1/net/state", Map.of("changes", List.of(change)), false, TIA, null), 401, "only a node of the cluster");
        refused(call("POST", "/api/v1/net/state", "{\"changes\":\"nope\"}", true, TIA, null), 400, "not a list");
    }

    @Test
    void aPeersPlacementIsAdoptedOnlyAtAHigherVersion() throws Exception {
        long now = placement.current().version();
        String yaml = "version: " + (now + 7) + "\nmodules:\n  adopted-sample:\n    parts:\n      api: { roles: [web] }\n";
        JsonNode got = ok(call("GET", "/api/v1/net/placement", null, true, TIA, null));
        assertThat(got.path("version").asLong()).isEqualTo(now);
        JsonNode adopted = ok(call("POST", "/api/v1/net/placement", Map.of("text", yaml), true, TIA, null));
        assertThat(adopted.path("adopted").asBoolean()).isTrue();
        assertThat(adopted.path("version").asLong()).isEqualTo(now + 7);
        assertThat(placement.current().version()).isEqualTo(now + 7);
        assertThat(placement.current().toYaml()).contains("adopted-sample");
        assertThat(ok(call("GET", "/api/v1/net/placement", null, true, TIA, null)).path("text").asText()).contains("adopted-sample");
        // the same version again, and a lower one: not adopted, nothing changes
        assertThat(ok(call("POST", "/api/v1/net/placement", Map.of("text", yaml), true, TIA, null)).path("adopted").asBoolean()).isFalse();
        String lower = "version: " + (now + 3) + "\nmodules:\n  lower-sample:\n    parts:\n      api: { roles: [web] }\n";
        assertThat(ok(call("POST", "/api/v1/net/placement", Map.of("text", lower), true, TIA, null)).path("adopted").asBoolean()).isFalse();
        assertThat(placement.current().toYaml()).doesNotContain("lower-sample");
        refused(call("POST", "/api/v1/net/placement", Map.of("text", yaml), false, TIA, null), 403, "cluster's nodes only");
        MvcResult bad = call("POST", "/api/v1/net/placement", Map.of("text", "version: [not, a, number\nmodules: {"), true, TIA, null);
        assertThat(bad.getResponse().getStatus()).as(text(bad)).isBetween(400, 499);
        assertThat(placement.current().version()).as("a refused placement changed nothing").isEqualTo(now + 7);
    }

    private static java.util.Set<String> cells(JsonNode partials) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        partials.path("sets").get(0).forEach(c -> out.add(c.path("key") + "=" + c.path("members") + c.path("states")));
        return out;
    }

    @Test
    void aScatteredViewsShareIsComputedForTheNamedPersonAndRefusedWithoutAnOnBehalfOfToken() throws Exception {
        OnBehalfOf.Claims claims = new OnBehalfOf.Claims("tia", List.of("trader"), "", "n-other-node", "r-1", "n-this", System.currentTimeMillis() + 30_000);
        Map<String, Object> plan = new java.util.LinkedHashMap<>();
        plan.put("source", "trade");
        plan.put("filters", Map.of());
        plan.put("dims", List.of("COUNTERPARTY"));
        plan.put("functions", List.of("count()"));
        plan.put("sets", List.of(Map.of("grouping", List.of("COUNTERPARTY"), "at", Map.of(), "exclude", Map.of(), "marks", Map.of())));
        plan.put("shard", List.of(0, 1));
        plan.put("maxGroups", 0);
        MvcResult r = call("POST", "/api/v1/net/pivot/partials", Map.of("plan", plan), true, TIA, claims);
        JsonNode body = ok(r);
        assertThat(body.path("sets")).as(body.toString()).hasSize(1);
        assertThat(body.path("sets").get(0).size()).as("one cell per counterparty").isGreaterThan(1);
        assertThat(body.path("sets").get(0).get(0).path("states").get(0).asText()).as("a partial state, encoded").isNotBlank();
        // the same share again: the same answer
        JsonNode again = ok(call("POST", "/api/v1/net/pivot/partials", Map.of("plan", plan), true, TIA, claims));
        assertThat(cells(again)).isEqualTo(cells(body));
        // refusals: no token for a person, a request with no plan, a source nobody has
        refused(call("POST", "/api/v1/net/pivot/partials", Map.of("plan", plan), true, TIA, null), 401, "on-behalf-of token");
        MvcResult noPlan = call("POST", "/api/v1/net/pivot/partials", Map.of(), true, TIA, claims);
        assertThat(noPlan.getResponse().getStatus()).as(text(noPlan)).isBetween(400, 499);
        assertThat(text(noPlan)).contains("DRS-");
        Map<String, Object> other = new java.util.LinkedHashMap<>(plan);
        other.put("source", "no-such-source");
        MvcResult none = call("POST", "/api/v1/net/pivot/partials", Map.of("plan", other), true, TIA, claims);
        assertThat(none.getResponse().getStatus()).as(text(none)).isBetween(400, 499);
        assertThat(text(none)).contains("DRS-");
    }
}
