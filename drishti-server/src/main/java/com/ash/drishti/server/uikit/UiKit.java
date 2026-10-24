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
package com.ash.drishti.server.uikit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Component;

/**
 * The Drishti UI kit (SUTRA_NEXT.md, "The Drishti UI kit"): platform UI vocabulary the server always loads, whatever packs are enabled.
 * Its fragments ({@code <kit>/fragments}) are read with the Sutras ({@code SutraBlocks}); this class serves the rest: the assets
 * ({@code <kit>/assets/icons|images|logos}) and the brand ({@code <kit>/brand.yaml}). A site overrides both under its own folder
 * ({@code drishti.ui-kit.site-dir}, default {@code <data folder>/ui-kit}): its {@code brand.yaml} keys win over the kit's, map by map,
 * and a file under its {@code assets/} wins over the kit's file of the same path. Every asset has a content hash (SHA-256, cached by
 * size and modification time), so a URL that carries it can be cached for ever. An asset path never leaves its folder: a {@code ..},
 * a hidden name, a backslash or a link out is refused. Thread-safe.
 */
@Component
public class UiKit {

    private static final Logger LOG = LoggerFactory.getLogger(UiKit.class);
    /** The folders of assets. */
    public static final List<String> ASSET_KINDS = List.of("icons", "images", "logos");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** One asset file: its path under assets/, the file, its content hash, its media type and where it came from (kit or site). */
    public record Asset(String path, Path file, String hash, String contentType, String origin, long size) {}

    private record Stamp(long size, long modified, String hash) {}

    private final Path dir;
    private final Path site;
    private final Map<Path, Stamp> hashes = new ConcurrentHashMap<>();

    public UiKit(@Value("${drishti.ui-kit.dir:./config/ui-kit}") String dir,
            @Value("${drishti.ui-kit.site-dir:${drishti.data.dir:./data}/ui-kit}") String site,
            @Value("${drishti.packs.dir:./config/packs}") String packsDir) {
        this.dir = com.ash.drishti.rachana.UiKitDir.resolve(dir, packsDir);     // the same folder the Sutras read the fragments from
        this.site = Path.of(site).toAbsolutePath().normalize();
    }

    /** The kit folder ({@code drishti.ui-kit.dir}). */
    public Path dir() {
        return dir;
    }

    /** The site's override folder ({@code drishti.ui-kit.site-dir}). */
    public Path siteDir() {
        return site;
    }

    /** The brand: the kit's {@code brand.yaml} with the site's keys over it (map by map), and {@code overridden} when a site file was read. */
    public Map<String, Object> brand() {
        Map<String, Object> out = read(dir.resolve("brand.yaml"));
        Path override = site.resolve("brand.yaml");
        boolean overridden = Files.isRegularFile(override);
        if (overridden) {
            merge(out, read(override));
        }
        out.put("overridden", overridden);
        return out;
    }

    /** Every asset, the site's first where both have a path, in path order. */
    public List<Asset> assets() {
        Map<String, Asset> out = new TreeMap<>();
        for (Path root : List.of(site.resolve("assets"), dir.resolve("assets"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(root)) {
                for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                    String rel = root.relativize(f).toString().replace('\\', '/');
                    if (!out.containsKey(rel) && safe(rel) && !f.getFileName().toString().startsWith("README")) {
                        asset(rel).ifPresent(a -> out.put(rel, a));
                    }
                }
            } catch (IOException | UncheckedIOException e) {
                LOG.warn("cannot list the UI kit assets under {}: {}", root, e.getMessage());
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * The asset at a path under assets/ ({@code logos/drishti-logo.png}): the site's file if it has one, else the kit's; empty when
     * there is none or the path is not a plain relative path inside the folder.
     */
    public Optional<Asset> asset(String rel) {
        if (!safe(rel)) {
            return Optional.empty();
        }
        for (Path root : List.of(site.resolve("assets"), dir.resolve("assets"))) {
            Path f = root.resolve(rel).normalize();
            if (!f.startsWith(root) || !Files.isRegularFile(f)) {
                continue;
            }
            try {
                if (!f.toRealPath().startsWith(root.toRealPath())) {          // a link that points out of the folder
                    continue;
                }
                long size = Files.size(f);
                long modified = Files.getLastModifiedTime(f).toMillis();
                Stamp st = hashes.get(f);
                if (st == null || st.size() != size || st.modified() != modified) {
                    st = new Stamp(size, modified, sha256(f));
                    hashes.put(f, st);
                }
                String type = MediaTypeFactory.getMediaType(f.getFileName().toString()).map(Object::toString).orElse("application/octet-stream");
                return Optional.of(new Asset(rel, f, st.hash(), type, root.startsWith(site) ? "site" : "kit", size));
            } catch (IOException e) {
                LOG.warn("cannot read the UI kit asset {}: {}", f, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** The versioned URL of an asset: its path and the first 16 hex digits of its hash (a new file is a new URL). */
    public static String url(Asset a) {
        return "/api/v1/ui-kit/assets/" + a.path() + "?v=" + a.hash().substring(0, 16);
    }

    /** Whether a path is a plain relative path: no {@code ..}, {@code .}, hidden name, absolute start, backslash, colon or NUL. */
    static boolean safe(String rel) {
        if (rel == null || rel.isBlank() || rel.length() > 512 || rel.startsWith("/") || rel.contains("\\") || rel.contains("\0") || rel.contains(":")) {
            return false;
        }
        for (String seg : rel.split("/", -1)) {
            if (seg.isEmpty() || seg.startsWith(".")) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> read(Path f) {
        if (!Files.isRegularFile(f)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> m = YAML.readValue(f.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
            return m == null ? new LinkedHashMap<>() : m;
        } catch (IOException e) {
            LOG.warn("cannot read the UI kit brand {}: {}", f, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static void merge(Map<String, Object> into, Map<String, Object> over) {
        over.forEach((k, v) -> {
            if (v instanceof Map<?, ?> vm && into.get(k) instanceof Map<?, ?> im) {
                Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) im);
                merge(copy, (Map<String, Object>) vm);
                into.put(k, copy);
            } else {
                into.put(k, v);
            }
        });
    }

    private static String sha256(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
