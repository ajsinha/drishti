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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.alerts.AlertEngine;
import com.ash.drishti.server.loads.ExpectationService;
import com.ash.drishti.server.loads.LoadNotifier;
import com.ash.drishti.server.loads.LoadService;
import com.ash.drishti.server.loads.LoadStore;
import com.ash.drishti.server.loads.LoadsConfig;
import com.ash.drishti.server.loads.LoadsProperties;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * "A batch landed": who may announce it (an administrator, or a token with loads:write whose user's roles open the kind), that it is
 * recorded once per pack, kind, date and batch, what runs on a ready load (refresh, verify, alerts, notices), and the expectations that
 * flag late data and clear when it lands, driven by a fake clock.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance", "drishti.loads.scheduler=false", "drishti.loads.dir=target/test-data/loads-${random.uuid}",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.sources.plugins.demo.settings.ticking=false", "drishti.identity.iterations=1000",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class LoadsApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN = "drishti-dev-admin";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier signer;
    @Autowired AlertEngine alerts;
    @Autowired PackRegistry packs;
    @Autowired LoadStore store;
    @Autowired LoadsConfig.Overrides configs;
    @Autowired LoadsProperties props;
    @Autowired SourceRegistry sources;
    @Autowired SourceRouter router;
    @Autowired ViewPipeline pipeline;
    @Autowired LoadNotifier notifier;
    @Autowired BusinessDates dates;
    @Autowired AuditLog audit;

    private String as(String user, String... roles) {
        return "Bearer " + signer.mint(user, List.of(roles), 300);
    }

    private String token(String user, String role, String scopes) throws Exception {
        var res = mvc.perform(post("/api/v1/me/tokens").header("Authorization", as(user, role)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"etl\",\"days\":30" + (scopes.isEmpty() ? "" : ",\"scopes\":[" + scopes + "]") + "}")).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(201);
        return "Bearer " + JSON.readTree(res.getContentAsString()).path("secret").asText();
    }

    private ResultActions announce(String bearer, String pack, String body) throws Exception {
        return mvc.perform(post("/api/v1/packs/" + pack + "/loads").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static String batchBody(String date, String batch) {
        return "{\"kind\":\"trade\",\"businessDate\":\"" + date + "\",\"status\":\"ready\",\"rows\":48213,\"rejected\":12,\"source\":\"etl-eod\",\"batchId\":\"" + batch + "\"}";
    }

    @Test
    void aReadyLoadRefreshesVerifiesTellsAndIsRecordedOnceAndALaterFailureIsRecordedAsSuch() throws Exception {
        String batch = "b-" + UUID.randomUUID();
        ResultActions first = announce(as(ADMIN, "admin"), "finance", batchBody("2026-09-29", batch));
        first.andExpect(status().isCreated()).andExpect(jsonPath("$.duplicate").value(false)).andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.verified").value("verified")).andExpect(jsonPath("$.attempt").value(1));
        JsonNode load = body(first);
        List<String> steps = load.path("steps").findValuesAsText("name");
        assertThat(steps).containsExactly("record", "refresh", "verify", "alerts", "smoke", "notices");
        assertThat(load.path("summary").asText()).startsWith("trade for 2026-09-29 loaded: 48,213 rows, 12 rejected; ").endsWith(" fired");
        assertThat(load.path("notices").asInt()).isGreaterThanOrEqualTo(1);       // the dev admin holds the default notify role

        // the same announcement again changes nothing: 200, the same record, no second run
        announce(as(ADMIN, "admin"), "finance", batchBody("2026-09-29", batch)).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.id").value(load.path("id").asText()));
        assertThat(store.list("finance", "trade", java.time.LocalDate.parse("2026-09-29"), null, 100).stream().filter(r -> batch.equals(r.batchId()))).hasSize(1);

        // the same batch failing afterwards is a second record, counted as an attempt; the first stays in the history
        announce(as(ADMIN, "admin"), "finance", "{\"kind\":\"trade\",\"asOf\":\"2026-09-29\",\"status\":\"failed\",\"batchId\":\"" + batch + "\",\"note\":\"disk full\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.attempt").value(2)).andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.summary").value("trade load for 2026-09-29 FAILED: disk full"))
                .andExpect(jsonPath("$.steps[?(@.name=='refresh')]").isEmpty());
        // a different batch of the same date is a reload
        announce(as(ADMIN, "admin"), "finance", batchBody("2026-09-29", batch + "-2")).andExpect(status().isCreated()).andExpect(jsonPath("$.reload").value(true));

        mvc.perform(get("/api/v1/admin/loads/finance?kind=trade&date=2026-09-29").header("Authorization", as(ADMIN, "admin"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.loads.length()").value(3));
        mvc.perform(get("/api/v1/me/inbox").header("Authorization", as(ADMIN, "admin"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='load')].title").value(org.hamcrest.Matchers.hasItem(containsString("trade for 2026-09-29"))));
        mvc.perform(get("/api/v1/admin/audit").header("Authorization", as(ADMIN, "admin")))
                .andExpect(jsonPath("$[*].action").value(org.hamcrest.Matchers.hasItems("data-load-ready", "data-load-failed")));
    }

    @Test
    void badInputIsRefusedWithTheRegistryCodes() throws Exception {
        String admin = as(ADMIN, "admin");
        announce(admin, "no-such-pack", batchBody("2026-09-29", "x")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-5011"));
        announce(admin, "finance", "{\"kind\":\"nope\",\"businessDate\":\"2026-09-29\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-5012"));
        announce(admin, "finance", "{\"kind\":\"trade\",\"businessDate\":\"29/09/2026\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4003"));
        announce(admin, "finance", "{\"kind\":\"trade\",\"businessDate\":\"2999-01-01\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4003"));
        announce(admin, "finance", "{\"kind\":\"trade\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-5001"));
        announce(admin, "finance", "{\"kind\":\"trade\",\"businessDate\":\"2026-09-29\",\"status\":\"maybe\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-5001"));
        announce(admin, "finance", "{\"kind\":\"trade\",\"businessDate\":\"2026-09-29\",\"rows\":-1}").andExpect(status().isBadRequest());
    }

    @Test
    void anAdminOrATokenWithLoadsWriteMayAnnounceAndNobodyElse() throws Exception {
        String body = batchBody("2026-09-28", "scope-" + UUID.randomUUID());
        announce(as("ada", "author"), "finance", body).andExpect(status().isForbidden());                               // a session of a non-admin
        String read = token(ADMIN, "admin", "");
        announce(read, "finance", body).andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("this token lacks the scope loads:write"));
        String scoped = token(ADMIN, "admin", "\"loads:write\"");
        announce(scoped, "finance", body).andExpect(status().isCreated());
        // a token's user must still be able to open the kind: a role that opens fx-spot only may announce fx-spot, not trade
        String admin = as(ADMIN, "admin");
        mvc.perform(put("/api/v1/admin/role-definitions/fx-loader").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"FX spot only\",\"kinds\":[\"fx-spot\"]}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"fx-etl\",\"roles\":[\"fx-loader\"],\"password\":\"scoped-pass-12\"}")).andExpect(status().isCreated());
        String fx = token("fx-etl", "fx-loader", "\"loads:write\"");
        announce(fx, "finance", body).andExpect(status().isForbidden());
        announce(fx, "finance", "{\"kind\":\"fx-spot\",\"businessDate\":\"2026-09-28\",\"batchId\":\"fx-1\"}").andExpect(status().isCreated());
        // the audit log names the token write
        mvc.perform(get("/api/v1/admin/audit").header("Authorization", as(ADMIN, "admin"))).andExpect(jsonPath("$[*].action").value(org.hamcrest.Matchers.hasItem("token-write")));
        // the override of the expectations is never open to a token
        mvc.perform(put("/api/v1/admin/loads/finance/config").header("Authorization", scoped).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aReadyLoadEvaluatesTheAlertRulesOnTheKindForTheDate() throws Exception {
        String admin = as(ADMIN, "admin");
        mvc.perform(put("/api/v1/me/alerts/rules/Negative MTM").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"trade\",\"id\":\"IRS-48213\",\"when\":\"$.mtm < 0\",\"severity\":\"warn\"}")).andExpect(status().isOk());
        long deadline = System.currentTimeMillis() + 5000;
        while (alerts.events(ADMIN, 10).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        int before = alerts.events(ADMIN, 100).size();
        JsonNode load = body(announce(admin, "finance", batchBody("2026-09-26", "alerts-" + UUID.randomUUID())).andExpect(status().isCreated()));
        assertThat(load.path("alerts").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(load.path("steps").findValuesAsText("detail")).anyMatch(d -> d.contains("alert") && d.contains("fired"));
        assertThat(alerts.events(ADMIN, 100).size()).isGreaterThan(before);
        mvc.perform(delete("/api/v1/me/alerts/rules/Negative MTM").header("Authorization", admin));
    }

    /** A clock the test moves. */
    private static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(String iso) {
            this.now = Instant.parse(iso);
        }

        void set(String iso) {
            now = Instant.parse(iso);
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    @Test
    void anExpectedLoadIsPendingThenLateThenClearedWhenItLandsAndNonBusinessDaysExpectNothing() throws Exception {
        String admin = as(ADMIN, "admin");
        mvc.perform(put("/api/v1/admin/loads/finance/config").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"notify\":{\"roles\":[\"admin\"]},\"smoke\":0,\"expect\":{\"fx-spot\":{\"by\":\"19:00\",\"zone\":\"America/New_York\",\"calendar\":\"USNY\"}}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.origin").value("override")).andExpect(jsonPath("$.config.expect.fx-spot.by").value("19:00"));
        try {
            mvc.perform(put("/api/v1/admin/loads/finance/config").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expect\":{\"fx-spot\":{\"by\":\"7pm\"}}}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("by must be a time")));
            mvc.perform(put("/api/v1/admin/loads/finance/config").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expect\":{\"nope\":{\"by\":\"19:00\"}}}")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-5012"));

            TestClock clock = new TestClock("2026-10-06T21:00:00Z");              // Tuesday 17:00 in New York
            LoadService loads = new LoadService(packs, store, configs, props, sources, router, pipeline, alerts, notifier, dates, audit, clock);
            ExpectationService expectations = new ExpectationService(loads, notifier, dates, clock);
            var finance = loads.pack("finance");
            assertThat(expectations.states(finance, clock.instant())).anyMatch(s -> s.businessDate().toString().equals("2026-10-06") && s.state().equals("pending"));
            assertThat(expectations.check(clock.instant())).noneMatch(s -> s.businessDate().toString().equals("2026-10-06"));

            clock.set("2026-10-06T23:30:00Z");                                       // 19:30 in New York: past the deadline
            var late = expectations.check(clock.instant());
            assertThat(late).anyMatch(s -> s.businessDate().toString().equals("2026-10-06") && s.state().equals("late"));
            assertThat(expectations.attention(clock.instant())).anyMatch(s -> s.line().equals("data late: finance/fx-spot 2026-10-06"));
            assertThat(expectations.check(clock.instant())).as("told once").isEmpty();
            mvc.perform(get("/api/v1/me/inbox").header("Authorization", admin)).andExpect(jsonPath("$[?(@.type=='load-late')].title")
                    .value(org.hamcrest.Matchers.hasItem(containsString("fx-spot for 2026-10-06 is LATE"))));
            mvc.perform(get("/api/v1/admin/health").header("Authorization", admin)).andExpect(jsonPath("$.status").value("DEGRADED"))
                    .andExpect(jsonPath("$.dataLate.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

            clock.set("2026-10-06T23:40:00Z");
            loads.announce("finance", new LoadService.Announcement("fx-spot", "2026-10-06", null, "ready", 10L, 0L, "test", "late-1", null), ADMIN);
            assertThat(expectations.states(finance, clock.instant())).anyMatch(s -> s.businessDate().toString().equals("2026-10-06") && s.state().equals("landed-late"));
            assertThat(expectations.attention(clock.instant())).noneMatch(s -> s.businessDate().toString().equals("2026-10-06"));
            assertThat(store.lateFlags("finance")).doesNotContainKey("fx-spot|2026-10-06");

            clock.set("2026-10-10T16:00:00Z");                                       // Saturday: nothing is due; the days before are
            assertThat(expectations.states(finance, clock.instant())).noneMatch(s -> s.businessDate().toString().equals("2026-10-10"));
            assertThat(expectations.states(finance, clock.instant())).anyMatch(s -> s.businessDate().toString().equals("2026-10-09") && s.state().equals("missing"));
        } finally {
            mvc.perform(delete("/api/v1/admin/loads/finance/config").header("Authorization", admin)).andExpect(status().isOk());
        }
    }
}
