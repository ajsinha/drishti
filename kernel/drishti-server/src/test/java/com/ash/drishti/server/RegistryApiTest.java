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

import com.ash.tools.codec.Hashes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.registry.PackRegistryClientTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Admin → Packs → Registry: a signed pack is listed, installed and queued to load; an unsigned one is refused. */
@SpringBootTest(properties = {"drishti.sources.connectors-dir=${drishti.test.dir}/connectors/finance-lake-off", "drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=finance"})
@AutoConfigureMockMvc
class RegistryApiTest {

    static {
        com.ash.drishti.server.ConnectorFixtures.copy("finance-lake-off");
    }

    static Path root;
    static KeyPair acme;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        root = TestRun.temp("drishti-registry");
        acme = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path reg = Files.createDirectories(root.resolve("registry"));
        byte[] archive = PackRegistryClientTest.zip(PackRegistryClientTest.pack("widgets", "1.0.0"));
        Files.write(reg.resolve("widgets-1.0.0.zip"), archive);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(acme.getPrivate());
        s.update(archive);
        String sha = Hashes.sha256Hex(archive);
        byte[] newer = PackRegistryClientTest.zip(PackRegistryClientTest.pack("widgets", "1.1.0"));
        Files.write(reg.resolve("widgets-1.1.0.zip"), newer);
        s.initSign(acme.getPrivate());
        s.update(newer);
        String newerSignature = Base64.getEncoder().encodeToString(s.sign());
        String newerSha = Hashes.sha256Hex(newer);
        s.initSign(acme.getPrivate());
        s.update(archive);
        String signature = Base64.getEncoder().encodeToString(s.sign());
        Files.writeString(reg.resolve("index.json"), "{\"packs\": [{\"name\": \"widgets\", \"version\": \"1.1.0\", \"title\": \"Widgets\", "
                + "\"file\": \"widgets-1.1.0.zip\", \"sha256\": \"" + newerSha + "\", \"size\": " + newer.length + ", \"publisher\": \"acme\", "
                + "\"signature\": \"" + newerSignature + "\"}, {\"name\": \"widgets\", \"version\": \"1.0.0\", \"title\": \"Widgets\", "
                + "\"file\": \"widgets-1.0.0.zip\", \"sha256\": \"" + sha + "\", \"size\": " + archive.length + ", \"publisher\": \"acme\", "
                + "\"signature\": \"" + signature + "\"}, {\"name\": \"rogue\", \"version\": \"1.0.0\", "
                + "\"file\": \"rogue-1.0.0.zip\", \"sha256\": \"00\", \"size\": 1, \"publisher\": \"nobody\", \"signature\": \"AA==\"}]}");
        r.add("drishti.packs.registry.url", reg::toString);
        r.add("drishti.packs.registry.trusted-keys.acme", () -> Base64.getEncoder().encodeToString(acme.getPublic().getEncoded()));
        r.add("drishti.packs.installed-dir", () -> root.resolve("installed").toString());
        r.add("drishti.packs.overlay", () -> root.resolve("added.yaml").toString());
    }

    @Autowired MockMvc mvc;

    @Test
    void aSignedPackIsInstalledAndLoadedAndAnUntrustedOneIsRefused() throws Exception {
        mvc.perform(get("/api/v1/admin/registry")).andExpect(status().isOk())
                .andExpect(jsonPath("$.packs[?(@.name=='widgets')].trusted").value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$.packs[?(@.name=='rogue')].trusted").value(org.hamcrest.Matchers.hasItem(false)));
        mvc.perform(post("/api/v1/admin/registry/rogue/1.0.0/install")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not trusted")));
        mvc.perform(post("/api/v1/admin/registry/widgets/1.0.0/install")).andExpect(status().isOk())
                .andExpect(jsonPath("$.installed").value("1.0.0"))
                .andExpect(jsonPath("$.added").value(org.hamcrest.Matchers.hasItem("widgets")));
        assertThat(root.resolve("installed/widgets/pack.yaml")).exists();
        assertThat(Files.readString(root.resolve("added.yaml"))).contains("- widgets");
        mvc.perform(get("/api/v1/admin/registry")).andExpect(jsonPath("$.packs[?(@.name=='widgets')].installedVersion").value(org.hamcrest.Matchers.hasItem("1.0.0")));
        mvc.perform(get("/api/v1/admin/packs")).andExpect(jsonPath("$[?(@.name=='widgets')].loaded").value(org.hamcrest.Matchers.hasItem(true)));   // installed and in use at once, no restart
    }

    @Test
    void aPackGoesBackToTheVersionInstalledBeforeAndNothingFurther() throws Exception {
        mvc.perform(post("/api/v1/admin/registry/widgets/1.0.0/install")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/registry/widgets/1.1.0/install")).andExpect(status().isOk()).andExpect(jsonPath("$.installed").value("1.1.0"))
                .andExpect(jsonPath("$.replaced").value("1.0.0"));
        assertThat(Files.readString(root.resolve("installed/widgets/pack.yaml"))).contains("1.1.0");
        mvc.perform(get("/api/v1/admin/registry")).andExpect(jsonPath("$.packs[?(@.name=='widgets')].installedVersion").value(org.hamcrest.Matchers.hasItem("1.1.0")));
        // back to 1.0.0: the files, the listing and the loaded pack all say so
        mvc.perform(post("/api/v1/admin/registry/widgets/rollback")).andExpect(status().isOk()).andExpect(jsonPath("$.installed").value("1.0.0"));
        assertThat(Files.readString(root.resolve("installed/widgets/pack.yaml"))).contains("1.0.0").doesNotContain("1.1.0");
        mvc.perform(get("/api/v1/admin/registry")).andExpect(jsonPath("$.packs[?(@.name=='widgets')].installedVersion").value(org.hamcrest.Matchers.hasItem("1.0.0")));
        mvc.perform(get("/api/v1/admin/packs")).andExpect(jsonPath("$[?(@.name=='widgets')].loaded").value(org.hamcrest.Matchers.hasItem(true)));
        // every earlier copy kept is a step back (each install keeps the one it replaced); when none is left the rollback is refused with a code
        int steps = 0;
        while (steps < 10 && mvc.perform(post("/api/v1/admin/registry/widgets/rollback")).andReturn().getResponse().getStatus() == 200) {
            steps++;
        }
        assertThat(steps).as("the steps back before the earliest").isLessThan(10);
        mvc.perform(post("/api/v1/admin/registry/widgets/rollback")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("DRS-")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no earlier version")));
        mvc.perform(post("/api/v1/admin/registry/widgets/1.0.0/install")).andExpect(status().isOk());       // the pack is usable again after the refusal
        mvc.perform(post("/api/v1/admin/registry/never-installed/rollback")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("DRS-")));
        assertThat(Files.readString(root.resolve("installed/widgets/pack.yaml"))).contains("1.0.0");
    }
}
