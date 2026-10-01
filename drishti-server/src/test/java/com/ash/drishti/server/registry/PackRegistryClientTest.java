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
package com.ash.drishti.server.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PackRegistryClientTest {

    @TempDir Path tmp;
    Path registry;
    Path installed;
    KeyPair acme;
    KeyPair stranger;
    final ObjectMapper json = new ObjectMapper();
    ArrayNode packs;

    @BeforeEach
    void setUp() throws Exception {
        registry = Files.createDirectories(tmp.resolve("registry"));
        installed = tmp.resolve("installed");
        acme = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        stranger = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        packs = json.createArrayNode();
    }

    private PackRegistryClient client(Map<String, String> trusted) {
        return new PackRegistryClient(new RegistryProperties(registry.toString(), trusted, null, null, null), installed);
    }

    private PackRegistryClient client() {
        return client(Map.of("acme", key(acme)));
    }

    private static String key(KeyPair k) {
        return Base64.getEncoder().encodeToString(k.getPublic().getEncoded());
    }

    public static byte[] zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (var f : files.entrySet()) {
                z.putNextEntry(new ZipEntry(f.getKey()));
                z.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    public static Map<String, String> pack(String name, String version) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("pack.yaml", "pack: " + name + "\nversion: " + version + "\ntitle: Widgets\nkinds: [widget]\n");
        m.put("samples/widget/W-1.json", "{\"widgetId\": \"W-1\"}");
        return m;
    }

    /** Publishes an archive: signed by {@code signer}, listed as {@code publisher}. */
    private void publish(String name, String version, byte[] archive, KeyPair signer, String publisher) throws Exception {
        String file = name + "-" + version + ".zip";
        Files.write(registry.resolve(file), archive);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(signer.getPrivate());
        s.update(archive);
        ObjectNode e = packs.addObject().put("name", name).put("version", version).put("file", file)
                .put("sha256", PackRegistryClient.sha256(archive)).put("size", archive.length).put("publisher", publisher)
                .put("signature", Base64.getEncoder().encodeToString(s.sign()));
        e.putArray("requires");
        Files.writeString(registry.resolve("index.json"), json.createObjectNode().set("packs", packs).toString());
    }

    @Test
    void installsUpgradesAndRollsBackSignedPacks() throws Exception {
        publish("widgets", "1.0.0", zip(pack("widgets", "1.0.0")), acme, "acme");
        publish("widgets", "1.1.0", zip(pack("widgets", "1.1.0")), acme, "acme");
        PackRegistryClient c = client();
        assertThat(c.index()).extracting(PackRegistryClient.Entry::trusted).containsOnly(true);
        PackRegistryClient.Installed first = c.install("widgets", "1.0.0");
        assertThat(first.replaced()).isNull();
        assertThat(installed.resolve("widgets/samples/widget/W-1.json")).exists();
        assertThat(c.installed("widgets").orElseThrow().path("version").asText()).isEqualTo("1.0.0");
        assertThat(c.install("widgets", "1.1.0").replaced()).isEqualTo("1.0.0");
        assertThat(c.installed("widgets").orElseThrow().path("version").asText()).isEqualTo("1.1.0");
        assertThat(c.rollback("widgets")).isEqualTo("1.0.0");
        assertThat(Files.readString(installed.resolve("widgets/pack.yaml"))).contains("version: 1.0.0");
        try (var s = Files.list(installed)) {
            assertThat(s.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith(".staging"));
        }
    }

    @Test
    void refusesWhatDoesNotCheckAndLeavesNothingBehind() throws Exception {
        byte[] good = zip(pack("widgets", "1.0.0"));
        publish("widgets", "1.0.0", good, acme, "acme");
        byte[] tampered = good.clone();
        tampered[tampered.length / 2] ^= 1;
        Files.write(registry.resolve("widgets-1.0.0.zip"), tampered);
        assertThatThrownBy(() -> client().install("widgets", "1.0.0")).hasMessageContaining("SHA-256");

        packs.removeAll();
        publish("widgets", "1.0.0", good, stranger, "acme");                   // listed as acme, signed by someone else
        assertThatThrownBy(() -> client().install("widgets", "1.0.0")).hasMessageContaining("signature does not verify");

        packs.removeAll();
        publish("widgets", "1.0.0", good, stranger, "stranger");
        assertThatThrownBy(() -> client().install("widgets", "1.0.0")).hasMessageContaining("not trusted");

        packs.removeAll();
        Map<String, String> evil = pack("widgets", "1.0.0");
        evil.put("../escaped.txt", "x");
        publish("widgets", "1.0.0", zip(evil), acme, "acme");
        assertThatThrownBy(() -> client().install("widgets", "1.0.0")).hasMessageContaining("outside the pack");
        assertThat(tmp.resolve("escaped.txt")).doesNotExist();

        packs.removeAll();
        publish("widgets", "1.0.0", zip(pack("gadgets", "1.0.0")), acme, "acme");
        assertThatThrownBy(() -> client().install("widgets", "1.0.0")).hasMessageContaining("its pack.yaml says gadgets");

        assertThat(installed.resolve("widgets")).doesNotExist();
        assertThatThrownBy(() -> new PackRegistryClient(new RegistryProperties("http://example.com/reg", Map.of(), null, null, null), installed).index())
                .hasMessageContaining("plain http");
    }

    @Test
    void installsWhatThePublishingToolSigned() throws Exception {
        boolean tools = new ProcessBuilder("openssl", "version").start().waitFor() == 0;
        org.junit.jupiter.api.Assumptions.assumeTrue(tools, "openssl is needed to sign like tools/packreg does");
        Path packDir = Files.createDirectories(tmp.resolve("src/widgets"));
        for (var f : pack("widgets", "2.0.0").entrySet()) {
            Files.createDirectories(packDir.resolve(f.getKey()).getParent());
            Files.writeString(packDir.resolve(f.getKey()), f.getValue());
        }
        Path tool = Path.of("..", "tools", "packreg", "packreg.py").toAbsolutePath().normalize();
        run("python3", tool.toString(), "keygen", "--out", tmp.resolve("keys/acme").toString());
        run("python3", tool.toString(), "publish", packDir.toString(), "--registry", registry.toString(), "--key", tmp.resolve("keys/acme.pem").toString(),
                "--publisher", "acme");
        String pub = run("openssl", "pkey", "-in", tmp.resolve("keys/acme.pem").toString(), "-pubout", "-outform", "DER", "-out",
                tmp.resolve("pub.der").toString()).isEmpty() ? Base64.getEncoder().encodeToString(Files.readAllBytes(tmp.resolve("pub.der"))) : "";
        PackRegistryClient c = client(Map.of("acme", pub));
        assertThat(c.install("widgets", "2.0.0").publisher()).isEqualTo("acme");
        assertThat(installed.resolve("widgets/samples/widget/W-1.json")).exists();
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new AssertionError(String.join(" ", cmd) + ": " + out);
        }
        return cmd[0].equals("openssl") ? "" : out;
    }
}
