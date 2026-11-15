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
package com.ash.drishti.server.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The thin console's server answers (docs/architecture/THIN_CONSOLE_DESIGN.md sections 3.1 to 3.4, steps S0 and S1): the stamps of
 * {@code /public/about}; the installation's answer for the console's service identity only, with its ETag and {@code 304}, and nothing of
 * any person, no folder and no secret in it; the person's answer with their packs and no folder; a pack's console contributions and only
 * the files they list.
 */
@SpringBootTest(properties = {"drishti.packs.enabled=core,gleif", "drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.enabled=false",
        "drishti.var.official.enabled=false", "drishti.var.official.on-start=false", "drishti.ccr.exposure.official.enabled=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.bi.dir=../../config/bi", "drishti.tables.root=../../config/tables",
        "drishti.bi.query.store-dir=${drishti.test.dir}/console-answers-bi-store/${random.uuid}",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/console-answers-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class ConsoleAnswersServerTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    String person(String user) {
        return "Bearer " + tokens.mint(user, List.of("viewer"), 60);
    }

    String service() {
        return "Bearer " + tokens.mint("console", List.of("service"), 60);
    }

    MvcResult call(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req).andReturn();
    }

    JsonNode ok(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = call(req);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void aboutCarriesTheStampsAndTheInstallationStampIsTheBootstrapsDigest() throws Exception {
        JsonNode stamps = ok(get("/public/about")).path("stamps");
        for (String k : List.of("installation", "resolution", "packs")) {
            assertThat(stamps.path(k).asText()).as(k).isNotBlank();
        }
        MvcResult boot = call(get("/api/v1/console/bootstrap").header("Authorization", service()));
        assertThat(boot.getResponse().getStatus()).isEqualTo(200);
        JsonNode doc = json.readTree(boot.getResponse().getContentAsString());
        assertThat(doc.path("digest").asText()).isEqualTo(stamps.path("installation").asText());
        assertThat(boot.getResponse().getHeader("ETag")).isEqualTo("\"" + doc.path("digest").asText() + "\"");
        assertThat(ok(get("/public/about")).path("stamps").path("installation").asText()).as("nothing changed: the same stamp")
                .isEqualTo(stamps.path("installation").asText());
    }

    @Test
    void theBootstrapIsForTheConsolesServiceIdentityOnlyAndAnswers304WhenUnchanged() throws Exception {
        assertThat(call(get("/api/v1/console/bootstrap")).getResponse().getStatus()).isEqualTo(401);
        assertThat(call(get("/api/v1/console/bootstrap").header("Authorization", person("ana"))).getResponse().getStatus()).isEqualTo(403);
        JsonNode doc = ok(get("/api/v1/console/bootstrap").header("Authorization", service()));
        assertThat(doc.path("schema").asInt()).isEqualTo(ConsoleAnswers.SCHEMA);
        assertThat(doc.path("distribution").path("id").asText()).isNotBlank();
        assertThat(doc.path("modules").isArray()).isTrue();
        assertThat(doc.path("engines").isArray()).isTrue();
        assertThat(doc.path("commands").path("CORE").path("pack").asText()).isEqualTo("core");
        assertThat(doc.path("commands").path("CORE").path("loaded").asBoolean()).isTrue();
        assertThat(doc.path("commands").path("LEI").path("pack").asText()).isEqualTo("gleif");
        MvcResult again = call(get("/api/v1/console/bootstrap").header("Authorization", service())
                .header("If-None-Match", "\"" + doc.path("digest").asText() + "\""));
        assertThat(again.getResponse().getStatus()).isEqualTo(304);
        assertThat(again.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void theBootstrapHoldsNoPersonNoFolderAndNoSecret() throws Exception {
        String who = "zelda-" + System.nanoTime();
        ok(get("/api/v1/me/console").header("Authorization", person(who)));            // a person asked first: nothing of them may stay
        String text = call(get("/api/v1/console/bootstrap").header("Authorization", service())).getResponse().getContentAsString();
        assertThat(text).doesNotContain(who).doesNotContain("Bearer").doesNotContain("test-secret");
        String repo = Path.of("").toAbsolutePath().normalize().getParent().getParent().toString();
        assertThat(text).as("no folder of the server").doesNotContain(repo).doesNotContain("\"dir\"");
        assertThat(text.length()).as("decisions, not a configuration dump").isLessThan(400_000);
    }

    @Test
    void thePersonsAnswerListsTheirPacksWithWhatTheConsoleDrawsAndNoFolder() throws Exception {
        MvcResult r = call(get("/api/v1/me/console").header("Authorization", person("ben")));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store").contains("private");
        JsonNode doc = json.readTree(r.getResponse().getContentAsString());
        assertThat(doc.path("person").path("user").asText()).isEqualTo("ben");
        List<String> names = new ArrayList<>();
        doc.path("packs").forEach(p -> names.add(p.path("name").asText()));
        assertThat(names).contains("core", "gleif");
        JsonNode gleif = null;
        for (JsonNode p : doc.path("packs")) {
            assertThat(p.has("dir")).as("no folder").isFalse();
            assertThat(p.path("console").path("digest").asText()).isNotBlank();
            if ("gleif".equals(p.path("name").asText())) {
                gleif = p;
            }
        }
        assertThat(gleif).isNotNull();
        assertThat(gleif.path("examples").size()).isGreaterThan(0);
        assertThat(gleif.path("mnemonics").path("LEI").asText()).isEqualTo("lei-entity");
        String stamp = doc.path("stamp").asText();
        assertThat(stamp).startsWith("p-");
        assertThat(call(get("/api/v1/me/console").header("Authorization", person("ben")).header("If-None-Match", "\"" + stamp + "\""))
                .getResponse().getStatus()).isEqualTo(304);
        for (JsonNode p : ok(get("/api/v1/packs").header("Authorization", person("ben")))) {
            assertThat(p.has("dir")).as("the packs answer names no folder").isFalse();
        }
    }

    @Test
    void aPacksConsoleAnswerListsItsGuidesAndOnlyThoseFilesAreServed() throws Exception {
        String who = person("cy");
        JsonNode a = ok(get("/api/v1/packs/gleif/console").header("Authorization", who));
        assertThat(a.path("pack").asText()).isEqualTo("gleif");
        List<String> slugs = new ArrayList<>();
        a.path("help").path("guides").forEach(g -> slugs.add(g.path("slug").asText()));
        assertThat(slugs).contains("gleif-pack");
        List<String> files = new ArrayList<>();
        a.path("files").forEach(f -> files.add(f.path("path").asText()));
        assertThat(files).contains("guides/gleif.md", "guides/images/gleif-entity.jpg");   // the picture beside the guide is allowed too
        MvcResult md = call(get("/api/v1/packs/gleif/console/files/guides/gleif.md").header("Authorization", who));
        assertThat(md.getResponse().getStatus()).isEqualTo(200);
        assertThat(md.getResponse().getContentAsString()).contains("GLEIF");
        assertThat(md.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(call(get("/api/v1/packs/gleif/console/files/pack.yaml").header("Authorization", who)).getResponse().getStatus())
                .as("a file the answer does not list").isEqualTo(404);
        assertThat(call(get("/api/v1/packs/gleif/console/files/guides/../pack.yaml").header("Authorization", who)).getResponse().getStatus())
                .isIn(400, 404);
        assertThat(call(get("/api/v1/packs/no-such-pack/console").header("Authorization", who)).getResponse().getStatus()).isEqualTo(404);
        String digest = a.path("digest").asText();
        assertThat(call(get("/api/v1/packs/gleif/console").header("Authorization", who).header("If-None-Match", "\"" + digest + "\""))
                .getResponse().getStatus()).isEqualTo(304);
    }

    @Test
    void ifNoneMatchIsReadQuotedWeakOrBare() {
        assertThat(ConsoleAnswers.matches("\"abc\"", "abc")).isTrue();
        assertThat(ConsoleAnswers.matches("W/\"abc\", \"def\"", "def")).isTrue();
        assertThat(ConsoleAnswers.matches("abc", "abc")).isTrue();
        assertThat(ConsoleAnswers.matches("*", "abc")).isTrue();
        assertThat(ConsoleAnswers.matches("\"abd\"", "abc")).isFalse();
        assertThat(ConsoleAnswers.matches(null, "abc")).isFalse();
    }
}
