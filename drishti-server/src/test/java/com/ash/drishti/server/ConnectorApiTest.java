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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.server.connectors.ConnectorManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Admin -> Connectors over HTTP, on the real file plugin: list, plugins, create, test, edit with versions, disable, history, delete, audit. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false"})
@AutoConfigureMockMvc
class ConnectorApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired MockMvc mvc;
    @Autowired ConnectorManager manager;
    @Autowired ConnectorFiles files;
    @Autowired AuditLog audit;

    @org.junit.jupiter.api.BeforeEach
    void freshHistory() throws Exception {
        Path h = files.dir().resolve(".history");
        if (Files.isDirectory(h)) {
            try (var s = Files.list(h)) {
                for (Path p : s.filter(x -> x.getFileName().toString().startsWith("api-")).toList()) {
                    Files.delete(p);
                }
            }
        }
    }

    @AfterEach
    void clean() throws Exception {
        for (String n : new String[] {"api-docs", "api-other", "api-bad"}) {
            if (files.exists(n) && manager.template(n).isEmpty()) {
                files.delete(n);
            }
        }
        manager.scan();
    }

    private static String body(String root, String extra) {
        return "{\"plugin\":\"file\",\"kinds\":[\"item\"],\"description\":\"test docs\",\"settings\":{\"root\":" + JSON.valueToTree(root) + extra + "}}";
    }

    private String etagOf(String name) throws Exception {
        return mvc.perform(get("/api/v1/admin/connectors/" + name)).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
    }

    @Test
    void createTestEditDisableHistoryAndDeleteAreAuditedAndLive() throws Exception {
        Path dir = Files.createTempDirectory("api-docs");
        Files.writeString(Files.createDirectories(dir.resolve("2026-10-05")).resolve("item.jsonl"), "{\"id\":\"A\"}\n{\"id\":\"B\"}\n");

        // create
        mvc.perform(put("/api/v1/admin/connectors/api-docs").contentType(MediaType.APPLICATION_JSON).content(body(dir.toString(), "")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.origin").value("file")).andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.plugin").value("file")).andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.text").value(containsString("plugin: file")));
        assertThat(files.exists("api-docs")).isTrue();
        mvc.perform(get("/api/v1/admin/connectors")).andExpect(status().isOk())
                .andExpect(jsonPath("$.connectors[?(@.name=='api-docs')].origin").value(hasItem("file")))
                .andExpect(jsonPath("$.directory").value(files.dir().toString()))
                .andExpect(jsonPath("$.connectors[?(@.name=='api-docs')].state").value(hasItem("RUNNING")));

        // test: a throwaway instance on the saved settings and on a draft
        mvc.perform(post("/api/v1/admin/connectors/api-docs/test")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.kinds[0].kind").value("item"));
        mvc.perform(post("/api/v1/admin/connectors/api-docs/test").contentType(MediaType.APPLICATION_JSON).content(body(dir.resolve("nope").toString(), "")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").isBoolean());

        // edit needs the version; a stale one is a conflict
        String v1 = etagOf("api-docs");
        mvc.perform(put("/api/v1/admin/connectors/api-docs").contentType(MediaType.APPLICATION_JSON).content(body(dir.toString(), ",\"lookback-days\":\"5\"")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("If-Match")));
        mvc.perform(put("/api/v1/admin/connectors/api-docs").header("If-Match", v1).contentType(MediaType.APPLICATION_JSON)
                .content(body(dir.toString(), ",\"lookback-days\":\"5\""))).andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(hasItem("lookback-days"))).andExpect(jsonPath("$.settings['lookback-days']").value("5"));
        mvc.perform(put("/api/v1/admin/connectors/api-docs").header("If-Match", v1).contentType(MediaType.APPLICATION_JSON)
                .content(body(dir.toString(), ",\"lookback-days\":\"6\""))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("changed since you read it")));

        // the YAML tab: typed text is stored as typed, comments and all
        String v2 = etagOf("api-docs");
        String typed = "# kept as typed\nplugin: file\nkinds: [item]\nsettings:\n  root: " + dir + "\n";
        mvc.perform(put("/api/v1/admin/connectors/api-docs").header("If-Match", v2).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(java.util.Map.of("text", typed)))).andExpect(status().isOk());
        assertThat(files.text("api-docs")).isEqualTo(typed);

        // history: every earlier text is kept
        JsonNode detail = JSON.readTree(mvc.perform(get("/api/v1/admin/connectors/api-docs")).andReturn().getResponse().getContentAsString());
        assertThat(detail.path("history")).hasSize(2);
        String oldest = detail.path("history").get(1).path("id").asText();
        mvc.perform(get("/api/v1/admin/connectors/api-docs/history/" + oldest)).andExpect(status().isOk()).andExpect(jsonPath("$.text").value(containsString("test docs")));
        mvc.perform(post("/api/v1/admin/connectors/api-docs/restore/" + oldest).header("If-Match", etagOf("api-docs"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("test docs"));

        // disable / enable
        mvc.perform(post("/api/v1/admin/connectors/api-docs/enabled").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DISABLED")).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/v1/admin/connectors/api-docs/enabled").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RUNNING"));

        // delete
        mvc.perform(delete("/api/v1/admin/connectors/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true));
        assertThat(files.exists("api-docs")).isFalse();
        mvc.perform(get("/api/v1/admin/connectors/api-docs")).andExpect(status().isNotFound());

        assertThat(audit.recent(100, "api-docs")).extracting(AuditLog.Event::action)
                .contains("connector-saved", "connector-tested", "connector-restored", "connector-disabled", "connector-enabled", "connector-deleted");
        // audit rows name settings, never values
        assertThat(audit.recent(100, "api-docs").stream().map(AuditLog.Event::detail).toList().toString()).doesNotContain(dir.toString());
    }

    @Test
    void invalidConnectorsAreRefusedWithTheReason() throws Exception {
        String root = "/tmp";
        String[][] cases = {
                {"Api-Bad", body(root, ""), "not a connector name"},
                {"plugins", body(root, ""), "reserved"},
                {"api-bad", "{\"plugin\":\"nosuch\"}", "no plugin named 'nosuch'"},
                {"api-bad", "{\"plugin\":\"file\",\"settings\":{}}", "settings.root: required"},
                {"api-bad", body(root, ",\"password\":\"hunter2\""), "never written into a connector file"},
                {"api-bad", "{\"text\":\"name: other\\nplugin: file\\nsettings: {root: /tmp}\\n\"}", "file name is the connector's name"},
                {"api-bad", "{\"text\":\"plugin: file\\nbogus: 1\\n\"}", "unknown key 'bogus'"},
                {"api-bad", "{\"text\":\"plugin: [\"}", "not valid YAML"},
                {"api-bad", "{\"description\":\"no plugin\"}", "'plugin' is required"}};
        for (String[] c : cases) {
            mvc.perform(put("/api/v1/admin/connectors/" + c[0]).contentType(MediaType.APPLICATION_JSON).content(c[1]))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(containsString(c[2])));
        }
        assertThat(files.exists("api-bad")).isFalse();
        mvc.perform(post("/api/v1/admin/connectors/api-bad/validate").contentType(MediaType.APPLICATION_JSON).content(body(root, ",\"rooot\":\"x\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.problems[*].level").value(hasItem("warning")))
                .andExpect(jsonPath("$.problems[*].message").value(hasItem(containsString("'rooot' is not a setting the file plugin reads"))));
    }

    @Test
    void thePluginsEndpointCarriesTheFormDefinition() throws Exception {
        mvc.perform(get("/api/v1/admin/connectors/plugins")).andExpect(status().isOk())
                .andExpect(jsonPath("$.plugins[*].name").value(hasItems("jdbc", "delta", "file", "kafka", "redis", "mongodb", "rabbitmq", "activemq")))
                .andExpect(jsonPath("$.plugins[?(@.name=='jdbc')].tls").value(hasItem(true)))
                .andExpect(jsonPath("$.plugins[?(@.name=='jdbc')].settings[?(@.name=='password')].secret").value(hasItem(true)))
                .andExpect(jsonPath("$.plugins[?(@.name=='jdbc')].settings[?(@.name=='url')].required").value(hasItem(true)))
                .andExpect(jsonPath("$.plugins[?(@.name=='jdbc')].settings[?(@.name=='tls.ca-file')].group").value(hasItem("tls")))
                .andExpect(jsonPath("$.plugins[?(@.name=='file')].tls").value(hasItem(false)));
    }

    @Test
    void healthShowsTheConnectorFolderAndABadFile() throws Exception {
        Files.createDirectories(files.dir());
        Files.writeString(files.dir().resolve("api-bad.yaml"), "plugin: file\nunknown-key: 1\n");
        manager.scan();
        try {
            mvc.perform(get("/api/v1/admin/health")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.connectors.badFiles").value(hasItem("api-bad")))
                    .andExpect(jsonPath("$.connectors.problems['api-bad']").value(containsString("unknown key")))
                    .andExpect(jsonPath("$.connectors.watch").isNotEmpty())
                    .andExpect(jsonPath("$.status").value("DEGRADED"));
            mvc.perform(get("/api/v1/admin/connectors")).andExpect(jsonPath("$.connectors[?(@.name=='api-bad')].problems[0]").value(hasItem(containsString("unknown key"))));
        } finally {
            Files.delete(files.dir().resolve("api-bad.yaml"));
            manager.scan();
        }
        mvc.perform(get("/api/v1/admin/health")).andExpect(jsonPath("$.connectors.badFiles").isEmpty());
    }

    @Test
    void connectorsTheShippedPacksNameAreFilesAtTheSiteNow() throws Exception {
        // the shipped packs' inline connector definitions were written out as files at start, and run from them
        mvc.perform(get("/api/v1/admin/connectors")).andExpect(status().isOk())
                .andExpect(jsonPath("$.connectors[?(@.origin=='file' && @.template!=null)]").isNotEmpty());
        mvc.perform(get("/api/v1/admin/connectors/plugins")).andExpect(status().isOk());
    }

    @Test
    void aPacksConnectorIsResetToItsDefaultOrDisabledNeverDeleted() throws Exception {
        String name = manager.names().stream().filter(n -> manager.template(n).isPresent() && files.exists(n) && !manager.usedBy(n).isEmpty()).findFirst().orElseThrow();
        mvc.perform(delete("/api/v1/admin/connectors/" + name)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("is named by pack")));
        assertThat(files.exists(name)).isTrue();
        String before = files.text(name);
        // disabling a connector packs use asks first
        mvc.perform(post("/api/v1/admin/connectors/" + name + "/enabled").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("confirm=true")));
        mvc.perform(post("/api/v1/admin/connectors/" + name + "/enabled?confirm=true").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/connectors/" + name + "/reset")).andExpect(status().isOk());
        assertThat(manager.files().read(name).isEnabled()).isTrue();
        assertThat(files.read(name).settings()).isEqualTo(files.parse(name, before).settings());
    }
}
