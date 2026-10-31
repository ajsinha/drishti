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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Drishti UI kit (RACHANA_REFERENCE.md) with NO pack enabled: its fragments load and draw anyway; {@code GET /api/v1/ui-kit} lists them,
 * the assets with versioned URLs and the brand, where the site's {@code brand.yaml} wins key by key; an asset has a strong ETag, a
 * 304 for it, a year's {@code immutable} cache on a content-hash URL, and a path out of the folder is refused.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.packs.enabled=", "drishti.security.roles.reader.kinds[0]=*"})
@AutoConfigureMockMvc
class UiKitApiTest {

    static final Path SITE = TestRun.root().resolve(Path.of("ui-kit-site"));

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    private final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void site(DynamicPropertyRegistry r) throws Exception {
        Files.createDirectories(SITE.resolve("assets/logos"));
        Files.writeString(SITE.resolve("brand.yaml"), "name: Acme Risk\nlogos: { full: logos/acme.svg }\nthemes: { light: { accent: \"#123456\" } }\n");
        Files.writeString(SITE.resolve("assets/logos/acme.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"4\" height=\"4\"/>");
        Files.writeString(SITE.getParent().resolve("outside.txt"), "not an asset");
        r.add("drishti.ui-kit.site-dir", SITE::toString);
    }

    private String bearer() {
        return "Bearer " + tokens.mint("u-reader", List.of("reader"), 300);
    }

    private JsonNode read(String url) throws Exception {
        return json.readTree(mvc.perform(get(url).header("Authorization", bearer())).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void theKitLoadsWithNoPackAndTheSummaryListsFragmentsAssetsAndTheOverriddenBrand() throws Exception {
        JsonNode kit = read("/api/v1/ui-kit");
        assertThat(kit.path("fragments").size()).isGreaterThanOrEqualTo(14);
        assertThat(kit.path("packFragments").size()).isZero();
        assertThat(kit.findValuesAsText("ref")).contains("series-line@1", "key-figure@1", "source-footer@1");
        kit.path("fragments").forEach(f -> {
            assertThat(f.path("origin").asText()).isEqualTo("kit");
            assertThat(f.path("doc").path("summary").asText()).isNotBlank();
            assertThat(f.path("source").asText()).startsWith("ui-kit/fragments/");
        });
        assertThat(kit.path("assets").findValuesAsText("path")).contains("logos/drishti-logo.png", "icons/favicon.svg", "logos/acme.svg")
                .doesNotContain("images/README.txt");
        JsonNode brand = kit.path("brand");
        assertThat(brand.path("overridden").asBoolean()).isTrue();
        assertThat(brand.path("name").asText()).isEqualTo("Acme Risk");                      // the site's wins
        assertThat(brand.path("tagline").asText()).isEqualTo("Any data. Any domain. One grammar.");   // the kit's kept
        assertThat(brand.path("logos").path("full").asText()).isEqualTo("logos/acme.svg");
        assertThat(brand.path("logos").path("mark").asText()).isEqualTo("logos/drishti-mark.svg");
        assertThat(brand.path("themes").path("light").path("accent").asText()).isEqualTo("#123456");
        assertThat(brand.path("themes").path("light").path("ink").asText()).isEqualTo("#172440");
        assertThat(brand.path("themes").path("terminal").path("accent").asText()).isEqualTo("#e8a33a");
        assertThat(brand.path("urls").path("logos.full").asText()).startsWith("/api/v1/ui-kit/assets/logos/acme.svg?v=");

        // the alias still answers, and a kit fragment draws with no pack loaded
        assertThat(read("/api/v1/sutras/library").path("count").asInt()).isGreaterThanOrEqualTo(14);
        assertThat(read("/api/v1/sutras/library/series-line/1").path("origin").asText()).isEqualTo("kit");
    }

    @Test
    void anAssetHasAStrongEtagA304AndALongCacheOnItsContentHashUrl() throws Exception {
        JsonNode logo = null;
        for (JsonNode a : read("/api/v1/ui-kit").path("assets")) {
            if (a.path("path").asText().equals("logos/drishti-logo.png")) {
                logo = a;
            }
        }
        assertThat(logo).isNotNull();
        String url = logo.path("url").asText();
        MockHttpServletResponse r = mvc.perform(get(url).header("Authorization", bearer())).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(r.getContentType()).isEqualTo("image/png");
        assertThat(r.getHeader("ETag")).isEqualTo("\"" + logo.path("hash").asText() + "\"");
        assertThat(r.getHeader("Cache-Control")).contains("max-age=31536000").contains("immutable");
        assertThat(r.getContentAsByteArray()).isEqualTo(Files.readAllBytes(Path.of("../config/ui-kit/assets/logos/drishti-logo.png")));

        MockHttpServletResponse plain = mvc.perform(get("/api/v1/ui-kit/assets/logos/drishti-logo.png").header("Authorization", bearer()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(plain.getHeader("Cache-Control")).doesNotContain("immutable").contains("max-age=300");

        mvc.perform(get(url).header("Authorization", bearer()).header("If-None-Match", r.getHeader("ETag"))).andExpect(status().isNotModified());
        mvc.perform(get(url).header("Authorization", bearer()).header("If-None-Match", "\"other\"")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ui-kit/assets/logos/acme.svg").header("Authorization", bearer())).andExpect(status().isOk());   // the site's own
    }

    @Test
    void aPathOutOfTheAssetsFolderIsRefused() throws Exception {
        for (String bad : List.of("/api/v1/ui-kit/assets/../brand.yaml", "/api/v1/ui-kit/assets/%2e%2e/brand.yaml",
                "/api/v1/ui-kit/assets/logos/%2e%2e/%2e%2e/brand.yaml", "/api/v1/ui-kit/assets/..%2f..%2foutside.txt",
                "/api/v1/ui-kit/assets/logos/..%5c..%5cbrand.yaml", "/api/v1/ui-kit/assets/nothing.png")) {
            int code = mvc.perform(get(bad).header("Authorization", bearer())).andReturn().getResponse().getStatus();
            assertThat(code).as(bad).isBetween(400, 404);
        }
    }
}
