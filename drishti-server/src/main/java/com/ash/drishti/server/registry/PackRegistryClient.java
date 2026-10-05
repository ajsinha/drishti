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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.yaml.snakeyaml.Yaml;

/**
 * A signed, versioned pack registry. The registry is an {@code index.json} beside the pack archives (zip files with
 * {@code pack.yaml} at their root):
 *
 * <pre>
 * {"packs": [{"name": "desk-tools", "version": "1.2.0", "title": "…", "description": "…", "requires": ["trading"],
 *             "file": "desk-tools-1.2.0.zip", "sha256": "…", "size": 12345, "publisher": "acme", "signature": "…"}]}
 * </pre>
 *
 * Installing checks, in order: the archive's size and SHA-256 against the index; its Ed25519 signature against the
 * publisher's key, which must be among {@code trusted-keys}; that every entry stays inside the pack (no {@code ..},
 * no absolute paths) and the unpacked size is bounded; that its {@code pack.yaml} names the same pack and version.
 * Only then is it unpacked into {@code <installed-dir>/<name>}; the version it replaces is kept under
 * {@code <installed-dir>/.previous/} for a rollback.
 */
public final class PackRegistryClient {

    /** One pack version in the registry, with whether this server would trust it. */
    public record Entry(String name, String version, String title, String description, List<String> requires, String file, String sha256,
            long size, String publisher, String signature, boolean trusted) {}

    /** What an install did. */
    public record Installed(String name, String version, String publisher, String sha256, Path dir, String replaced) {}

    private static final String MARK = ".registry.json";
    private final RegistryProperties props;
    private final Path installed;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    public PackRegistryClient(RegistryProperties props, Path installedDir) {
        this.props = props;
        this.installed = installedDir.toAbsolutePath().normalize();
    }

    public boolean configured() {
        return !props.url().isEmpty();
    }

    public String url() {
        return props.url();
    }

    /** Every pack version the registry offers. */
    public List<Entry> index() {
        if (!configured()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no pack registry is configured (drishti.packs.registry.url)");
        }
        JsonNode root;
        try {
            root = json.readTree(fetch("index.json", 5L * 1024 * 1024));
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the registry's index cannot be read: " + e.getMessage());
        }
        List<Entry> out = new ArrayList<>();
        for (JsonNode p : root.path("packs")) {
            List<String> req = new ArrayList<>();
            p.path("requires").forEach(r -> req.add(r.asText()));
            String publisher = p.path("publisher").asText("");
            out.add(new Entry(p.path("name").asText(), p.path("version").asText(), p.path("title").asText(""), p.path("description").asText(""),
                    req, p.path("file").asText(), p.path("sha256").asText(""), p.path("size").asLong(), publisher, p.path("signature").asText(""),
                    props.trustedKeys().containsKey(publisher)));
        }
        return out;
    }

    /** The version installed from the registry, if any. */
    public Optional<JsonNode> installed(String name) {
        Path mark = installed.resolve(name).resolve(MARK);
        try {
            return Files.isRegularFile(mark) ? Optional.of(json.readTree(mark.toFile())) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Downloads, verifies and unpacks a pack version; nothing on disk changes unless every check passes. */
    public Installed install(String name, String version) {
        Entry e = index().stream().filter(x -> x.name().equals(name) && x.version().equals(version)).findFirst()
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the registry has no " + name + " " + version));
        if (!name.matches("[a-z0-9][a-z0-9-]{0,63}") || !e.file().matches("[A-Za-z0-9._-]+\\.zip")) {
            throw refused(name, "the index names an unsafe pack or file name");
        }
        String key = props.trustedKeys().get(e.publisher());
        if (key == null) {
            throw refused(name, "its publisher '" + e.publisher() + "' is not trusted on this server (drishti.packs.registry.trusted-keys)");
        }
        byte[] archive;
        try {
            archive = fetch(e.file(), props.maxArchiveMb() * 1024L * 1024);
        } catch (IOException ex) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "cannot download " + e.file() + ": " + ex.getMessage());
        }
        String sha = sha256(archive);
        if (!sha.equalsIgnoreCase(e.sha256())) {
            throw refused(name, "its SHA-256 is " + sha + ", the index says " + e.sha256());
        }
        if (!verify(archive, e.signature(), key)) {
            throw refused(name, "its signature does not verify with " + e.publisher() + "'s key");
        }
        Path staging = unpack(name, archive);
        try {
            checkManifest(staging, name, version);
            ObjectNode mark = json.createObjectNode().put("name", name).put("version", version).put("publisher", e.publisher()).put("sha256", sha)
                    .put("registry", props.url()).put("installedAt", Instant.now().toString());
            Files.writeString(staging.resolve(MARK), mark.toPrettyString());
            String replaced = installed(name).map(m -> m.path("version").asText()).orElse(null);
            Path target = installed.resolve(name);
            if (Files.exists(target)) {
                Path keep = installed.resolve(".previous").resolve(name).resolve((replaced == null ? "unknown" : replaced) + "-" + System.currentTimeMillis());
                Files.createDirectories(keep.getParent());
                Files.move(target, keep, StandardCopyOption.ATOMIC_MOVE);
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            return new Installed(name, version, e.publisher(), sha, target, replaced);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            deleteQuietly(staging);
        }
    }

    /** Puts back the version installed before the current one (newest kept); returns its version. */
    public String rollback(String name) {
        Path keep = installed.resolve(".previous").resolve(name);
        try {
            Path last;
            try (var s = Files.list(keep)) {
                last = s.max(java.util.Comparator.comparing(p -> p.getFileName().toString().replaceAll(".*-", ""))).orElse(null);
            }
            if (last == null) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "there is no earlier version of '" + name + "' to go back to");
            }
            Path target = installed.resolve(name);
            Path out = installed.resolve(".previous").resolve(".discarded-" + name + "-" + System.currentTimeMillis());
            if (Files.exists(target)) {
                Files.move(target, out, StandardCopyOption.ATOMIC_MOVE);
            }
            Files.move(last, target, StandardCopyOption.ATOMIC_MOVE);
            deleteQuietly(out);
            return installed(name).map(m -> m.path("version").asText()).orElse("the shipped version");
        } catch (java.nio.file.NoSuchFileException ex) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "there is no earlier version of '" + name + "' to go back to");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Puts a pack directory back as it was (after an install the server could not start with). */
    public void undo(Installed done) {
        try {
            if (done.replaced() != null || Files.isDirectory(installed.resolve(".previous").resolve(done.name()))) {
                rollback(done.name());
            } else {
                deleteQuietly(installed.resolve(done.name()));
            }
        } catch (RuntimeException e) {
            deleteQuietly(installed.resolve(done.name()));
        }
    }

    // -- checks ------------------------------------------------------------------------------------------------------

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(byte[] data, String signature, String publicKey) {
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.trim())));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(data);
            return s.verify(Base64.getDecoder().decode(signature.trim()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Unpacks into a staging folder beside the installed packs, refusing entries that leave it or too much data. */
    private Path unpack(String name, byte[] archive) {
        long budget = props.maxUnpackedMb() * 1024L * 1024;
        Path staging;
        try {
            Files.createDirectories(installed);
            staging = Files.createTempDirectory(installed, ".staging-" + name + "-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        long total = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry z; (z = zip.getNextEntry()) != null;) {
                if (++count > 10_000) {
                    throw refused(name, "it has more than 10000 files");
                }
                String n = z.getName().replace('\\', '/');
                Path out = staging.resolve(n).normalize();
                if (n.startsWith("/") || n.contains("..") || !out.startsWith(staging)) {
                    throw refused(name, "it has a file outside the pack: " + n);
                }
                if (z.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (var w = Files.newOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    for (int r; (r = zip.read(buf)) > 0;) {
                        total += r;
                        if (total > budget) {
                            throw refused(name, "it unpacks to more than " + props.maxUnpackedMb() + " MB");
                        }
                        w.write(buf, 0, r);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(staging);
            throw e instanceof DrishtiException de ? de : refused(name, "it is not a readable zip archive: " + e.getMessage());
        }
        return staging;
    }

    @SuppressWarnings("unchecked")
    private static void checkManifest(Path dir, String name, String version) throws IOException {
        Path manifest = dir.resolve("pack.yaml");
        if (!Files.isRegularFile(manifest)) {
            throw refused(name, "it has no pack.yaml at its root");
        }
        Object y;
        try (InputStream in = Files.newInputStream(manifest)) {
            y = new Yaml().load(in);
        }
        Map<String, Object> m = y instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        if (!name.equals(String.valueOf(m.get("pack"))) || !version.equals(String.valueOf(m.get("version")))) {
            throw refused(name, "its pack.yaml says " + m.get("pack") + " " + m.get("version") + ", the index says " + name + " " + version);
        }
    }

    private byte[] fetch(String file, long max) throws IOException {
        String base = props.url().endsWith("/") ? props.url() : props.url() + "/";
        if (base.startsWith("https://") || (base.startsWith("http://") && props.allowHttp())) {
            try {
                HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(base).resolve(file)).timeout(Duration.ofSeconds(60)).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() != 200) {
                    throw new IOException(file + " answered " + r.statusCode());
                }
                try (InputStream in = r.body()) {
                    return bounded(in, max, file);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
        if (base.startsWith("http://")) {
            throw new IOException("plain http registries are refused (drishti.packs.registry.allow-http for tests only)");
        }
        Path root = base.startsWith("file:") ? Path.of(URI.create(base)) : Path.of(props.url());
        Path p = root.resolve(file).normalize();
        if (!p.startsWith(root.normalize())) {
            throw new IOException(file + " is outside the registry");
        }
        try (InputStream in = Files.newInputStream(p)) {
            return bounded(in, max, file);
        }
    }

    private static byte[] bounded(InputStream in, long max, String file) throws IOException {
        byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
        if (data.length > max) {
            throw new IOException(file + " is larger than " + (max / 1024 / 1024) + " MB");
        }
        return data;
    }

    private static DrishtiException refused(String name, String why) {
        return new DrishtiException(ErrorCode.BAD_REQUEST, "refused to install '" + name + "': " + why);
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // best effort: a staging folder left behind is harmless and named .staging-*
                }
            });
        } catch (IOException e) {
            // best effort
        }
    }
}
