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
package com.ash.drishti.net.security;

import com.ash.drishti.api.tls.PemReader;
import com.ash.drishti.net.config.ClusterSettings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * A private cluster authority and node certificates for tests, made with the JDK's own {@code keytool} (Java only, nothing to install, the
 * same on Windows and Linux; the way {@code drishti.py net certs} makes them): EC P-256 keys, a node certificate naming the node and its
 * address, for server and client authentication. Written as PEM, so tests also read them through {@code [net.tls]} ({@link ClusterTls#read}).
 */
public final class TestCerts {

    private static final String PASS = "test-pass";

    /** One node's files and its TLS material. */
    public record Issued(Path caFile, Path certFile, Path keyFile, ClusterTls tls) {
        /** The {@code [net.tls]} settings naming these files. */
        public ClusterSettings.Tls settings() {
            return new ClusterSettings.Tls(caFile.toString(), certFile.toString(), keyFile.toString(), "", "need", false);
        }
    }

    private final Path dir;
    private final Path caStore;
    private final Path caPem;

    private TestCerts(Path dir, Path caStore, Path caPem) {
        this.dir = dir;
        this.caStore = caStore;
        this.caPem = caPem;
    }

    /** A new authority named {@code name} in {@code dir} (absolute). */
    public static TestCerts authority(Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        Path store = dir.resolve(name + "-ca.p12");
        Path pem = dir.resolve(name + "-ca.pem");
        keytool("-genkeypair", "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", PASS, "-alias", "ca", "-dname", "CN=" + name,
                "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2", "-ext", "bc:c");
        keytool("-exportcert", "-keystore", store.toString(), "-storepass", PASS, "-alias", "ca", "-rfc", "-file", pem.toString());
        return new TestCerts(dir, store, pem);
    }

    /** A certificate for {@code node} at {@code host} (an IP or a name), signed by this authority. */
    public Issued issue(String node, String host) throws Exception {
        Path store = dir.resolve(node + ".p12");
        Path csr = dir.resolve(node + ".csr");
        Path cert = dir.resolve(node + ".pem");
        Path key = dir.resolve(node + ".key");
        keytool("-genkeypair", "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", PASS, "-alias", node, "-dname", "CN=" + node,
                "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2");
        keytool("-certreq", "-keystore", store.toString(), "-storepass", PASS, "-alias", node, "-file", csr.toString());
        String san = (host.matches("[0-9.]+") || host.contains(":") ? "ip:" : "dns:") + host + ",dns:" + node;
        keytool("-gencert", "-keystore", caStore.toString(), "-storepass", PASS, "-alias", "ca", "-infile", csr.toString(), "-outfile", cert.toString(),
                "-rfc", "-validity", "2", "-ext", "san=" + san, "-ext", "ku:c=digitalSignature,keyAgreement", "-ext", "eku=serverAuth,clientAuth");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            ks.load(in, PASS.toCharArray());
        }
        PrivateKey pk = (PrivateKey) ks.getKey(node, PASS.toCharArray());
        Files.writeString(key, PemReader.pem("PRIVATE KEY", pk.getEncoded()), StandardCharsets.US_ASCII);
        ClusterTls tls = ClusterTls.read(new ClusterSettings.Tls(caPem.toString(), cert.toString(), key.toString(), "", "need", false));
        return new Issued(caPem, cert, key, tls);
    }

    private static void keytool(String... args) throws IOException, InterruptedException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool").toString());
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            p.destroyForcibly();
            throw new IOException("keytool " + String.join(" ", args) + " failed: " + out);
        }
    }
}
