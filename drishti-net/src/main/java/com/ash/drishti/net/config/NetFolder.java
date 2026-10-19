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
package com.ash.drishti.net.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The {@code config/net/} folder (DRISHTI_NET.md section 14), managed the way connector files are: {@code node.yaml},
 * {@code peers/<name>.yaml}, {@code gossip.yaml}, {@code routing.yaml}, {@code access.yaml}. Every file is checked against
 * its schema ({@link NetSchema}) before use; a refused file keeps the previous settings of that file in force and its
 * problems are reported. Writes are atomic (a temporary file moved over the target) and keep the previous text in
 * {@code <data dir>/net/history/}. The folder is created on the first write; without it every file has its defaults and
 * Drishti*net is off.
 */
public final class NetFolder {

    /** The files of the folder that are not peers, in the order they are shown. */
    public static final List<String> FIXED = List.of("node.yaml", "gossip.yaml", "routing.yaml", "access.yaml");
    /** A peer's logical name, as connector names. */
    public static final Pattern PEER_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);

    /** One file of the folder as listed: its id ({@code node.yaml}, {@code peers/a.yaml}), whether it exists, its version. */
    public record FileInfo(String id, boolean exists, String etag, long bytes) {}

    /** A kept earlier text. */
    public record Revision(String id, Instant at, long bytes) {}

    /** What a load found: the settings in force, each file's problems, each file's version. */
    public record Loaded(NetConfig config, Map<String, List<NetSchema.Problem>> problems, Map<String, String> etags) {
        public boolean refused(String file) {
            return problems.getOrDefault(file, List.of()).stream().anyMatch(NetSchema.Problem::error);
        }

        public List<NetSchema.Problem> all() {
            List<NetSchema.Problem> out = new ArrayList<>();
            problems.values().forEach(out::addAll);
            return out;
        }
    }

    private final Path dir;
    private final Path history;
    private final NetSchema schema;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));
    private final ReentrantLock writes = new ReentrantLock();

    /**
     * @param dir the folder ({@code drishti.net.dir}, default {@code ./config/net})
     * @param state where its history goes ({@code <data dir>/net})
     * @param schema checks and reads the files
     */
    public NetFolder(Path dir, Path state, NetSchema schema) {
        this.dir = dir.toAbsolutePath().normalize();
        this.history = state.toAbsolutePath().normalize().resolve("history");
        this.schema = schema;
    }

    public Path dir() {
        return dir;
    }

    public NetSchema schema() {
        return schema;
    }

    /** True when the id names a file of this folder ({@code node.yaml}, {@code peers/<name>.yaml}, ...). */
    public static boolean valid(String id) {
        if (id == null) {
            return false;
        }
        if (FIXED.contains(id)) {
            return true;
        }
        return id.startsWith("peers/") && id.endsWith(".yaml") && PEER_NAME.matcher(id.substring(6, id.length() - 5)).matches();
    }

    private Path path(String id) {
        if (!valid(id)) {
            throw new IllegalArgumentException("'" + id + "' is not a file of config/net/: node.yaml, gossip.yaml, routing.yaml, access.yaml or peers/<name>.yaml");
        }
        return dir.resolve(id).normalize();
    }

    /** Every file: the fixed ones (present or not) and every peer file present, peers in name order. */
    public List<FileInfo> files() {
        List<FileInfo> out = new ArrayList<>();
        for (String id : FIXED) {
            out.add(info(id));
        }
        peerIds().forEach(id -> out.add(info(id)));
        return out;
    }

    private FileInfo info(String id) {
        Path p = path(id);
        try {
            if (!Files.isRegularFile(p)) {
                return new FileInfo(id, false, "", 0);
            }
            return new FileInfo(id, true, etag(Files.readString(p, StandardCharsets.UTF_8)), Files.size(p));
        } catch (IOException e) {
            return new FileInfo(id, false, "", 0);
        }
    }

    /** The peer files present, {@code peers/<name>.yaml}, in name order; files whose name is not a peer name are left out. */
    public List<String> peerIds() {
        Path peers = dir.resolve("peers");
        if (!Files.isDirectory(peers)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(peers)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".yaml"))
                    .map(n -> "peers/" + n).filter(NetFolder::valid).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** A file's text, or empty when it does not exist. */
    public Optional<String> text(String id) {
        Path p = path(id);
        try {
            return Files.isRegularFile(p) ? Optional.of(Files.readString(p, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The settings every file says, each refused file keeping its part of {@code previous}. */
    public Loaded load(NetConfig previous) {
        NetConfig prev = previous == null ? NetConfig.OFF : previous;
        Map<String, List<NetSchema.Problem>> problems = new LinkedHashMap<>();
        Map<String, String> etags = new LinkedHashMap<>();
        NetConfig c = prev;
        NodeSettings node = read("node.yaml", problems, etags, NodeSettings.OFF, prev.node(), (m, t) -> schema.node("node.yaml", m, t));
        GossipSettings gossip = read("gossip.yaml", problems, etags, GossipSettings.DEFAULTS, prev.gossip(), (m, t) -> schema.gossip("gossip.yaml", m, t));
        RoutingSettings routing = read("routing.yaml", problems, etags, RoutingSettings.DEFAULTS, prev.routing(), (m, t) -> schema.routing("routing.yaml", m, t));
        AccessSettings access = read("access.yaml", problems, etags, AccessSettings.OPEN, prev.access(), (m, t) -> schema.access("access.yaml", m, t));
        List<SeedPeer> peers = new ArrayList<>();
        for (String id : peerIds()) {
            String name = id.substring(6, id.length() - 5);
            SeedPeer before = prev.peers().stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
            SeedPeer p = read(id, problems, etags, null, before, (m, t) -> schema.peer(id, name, m, t));
            if (p != null) {
                peers.add(p);
            }
        }
        c = new NetConfig(node, peers, gossip, routing, access);
        return new Loaded(c, problems, etags);
    }

    private <T> T read(String id, Map<String, List<NetSchema.Problem>> problems, Map<String, String> etags, T absent, T previous,
            java.util.function.BiFunction<Map<String, Object>, String, NetSchema.Parsed<T>> parse) {
        Optional<String> text;
        try {
            text = text(id);
        } catch (UncheckedIOException e) {
            problems.put(id, List.of(new NetSchema.Problem(id, "error", "", "cannot be read: " + e.getCause().getMessage(), 0)));
            return previous;
        }
        if (text.isEmpty()) {
            return absent;
        }
        etags.put(id, etag(text.get()));
        NetSchema.Parsed<T> parsed = parsed(id, text.get(), parse);
        if (!parsed.problems().isEmpty()) {
            problems.put(id, parsed.problems());
        }
        return parsed.ok() ? parsed.value() : previous;
    }

    private <T> NetSchema.Parsed<T> parsed(String id, String text, java.util.function.BiFunction<Map<String, Object>, String, NetSchema.Parsed<T>> parse) {
        Map<String, Object> m;
        try {
            m = map(text);
        } catch (JsonProcessingException e) {
            int line = e.getLocation() == null ? 0 : e.getLocation().getLineNr();
            return new NetSchema.Parsed<>(null, List.of(new NetSchema.Problem(id, "error", "", "is not YAML: " + e.getOriginalMessage(), line)));
        }
        return parse.apply(m, text);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String text) throws JsonProcessingException {
        if (text == null || text.isBlank()) {
            return new LinkedHashMap<>();
        }
        Object v = yaml.readValue(text, Object.class);
        if (v == null) {
            return new LinkedHashMap<>();
        }
        if (!(v instanceof Map<?, ?>)) {
            throw new com.fasterxml.jackson.databind.JsonMappingException(null, "the file is a mapping of settings (key: value), not a list or a single value");
        }
        return (Map<String, Object>) v;
    }

    /** Checks a draft of a file without writing it: its problems (none when it would be taken). */
    public List<NetSchema.Problem> check(String id, String text) {
        path(id);
        return switch (id) {
            case "node.yaml" -> parsed(id, text, (m, t) -> schema.node(id, m, t)).problems();
            case "gossip.yaml" -> parsed(id, text, (m, t) -> schema.gossip(id, m, t)).problems();
            case "routing.yaml" -> parsed(id, text, (m, t) -> schema.routing(id, m, t)).problems();
            case "access.yaml" -> parsed(id, text, (m, t) -> schema.access(id, m, t)).problems();
            default -> parsed(id, text, (m, t) -> schema.peer(id, id.substring(6, id.length() - 5), m, t)).problems();
        };
    }

    /** The version of a text: stable for equal text. */
    public static String etag(String text) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Writes a file atomically, keeping its previous text in the history; creates the folder on first write. {@code ifMatch}, when
     * not null, must be the file's current version ({@code ""} for a file that does not exist yet): a concurrent edit is refused.
     *
     * @throws IllegalStateException when {@code ifMatch} is stale
     */
    public void write(String id, String text, String ifMatch) {
        Path f = path(id);
        writes.lock();
        try {
            requireVersion(id, ifMatch);
            Files.createDirectories(f.getParent());
            backup(id);
            Path tmp = com.ash.drishti.common.TempFiles.createFile(f.getParent(), "net-", ".tmp");
            try {
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writes.unlock();
        }
    }

    /** Removes a file after keeping its text in the history; false when there was none. */
    public boolean delete(String id, String ifMatch) {
        Path f = path(id);
        writes.lock();
        try {
            requireVersion(id, ifMatch);
            if (!Files.isRegularFile(f)) {
                return false;
            }
            backup(id);
            Files.delete(f);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writes.unlock();
        }
    }

    private void requireVersion(String id, String ifMatch) {
        if (ifMatch == null) {
            return;
        }
        String now = text(id).map(NetFolder::etag).orElse("");
        if (!now.equals(ifMatch.replace("\"", "").trim())) {
            throw new IllegalStateException(id + " was changed since it was read (version " + (now.isEmpty() ? "none: it was deleted" : now)
                    + "); read it again and redo the change");
        }
    }

    private static String historyName(String id) {
        return id.replace(".yaml", "").replace("/", "__");
    }

    private void backup(String id) throws IOException {
        Path f = path(id);
        if (!Files.isRegularFile(f)) {
            return;
        }
        Files.createDirectories(history);
        String stamp = STAMP.format(Instant.now());
        Path to = history.resolve(historyName(id) + "." + stamp + ".yaml");
        for (int i = 1; Files.exists(to); i++) {
            to = history.resolve(historyName(id) + "." + stamp + "-" + i + ".yaml");
        }
        Files.copy(f, to);
    }

    /** The kept earlier texts of a file, newest first. */
    public List<Revision> history(String id) {
        path(id);
        List<Revision> out = new ArrayList<>();
        if (!Files.isDirectory(history)) {
            return out;
        }
        Pattern p = Pattern.compile(Pattern.quote(historyName(id)) + "\\.(\\d{8}T\\d{9}(?:-\\d+)?)\\.yaml");
        try (Stream<Path> s = Files.list(history)) {
            for (Path x : s.toList()) {
                var mt = p.matcher(x.getFileName().toString());
                if (mt.matches()) {
                    out.add(new Revision(mt.group(1), Files.getLastModifiedTime(x).toInstant(), Files.size(x)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort((a, b) -> b.id().compareTo(a.id()));
        return out;
    }

    /** The text of one kept revision, or empty. */
    public Optional<String> revisionText(String id, String rev) {
        path(id);
        if (rev == null || !rev.matches("\\d{8}T\\d{9}(-\\d+)?")) {
            return Optional.empty();
        }
        Path f = history.resolve(historyName(id) + "." + rev + ".yaml");
        try {
            return Files.isRegularFile(f) ? Optional.of(Files.readString(f, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The text of a file with one setting changed ({@code tags} to {@code [region:emea]}, {@code tls.keystore.path} to a path, a
     * role's nodes in access.yaml as {@code roles.risk.nodes}): the file is read as YAML, the setting set (a comma-separated value
     * of a list setting becomes a list, an empty value removes the setting) and the whole written back. Comments are not kept.
     */
    public String withSetting(String id, String dotted, String value) {
        return withSetting(text(id).orElse(""), id, dotted, value);
    }

    /** As {@link #withSetting(String, String, String)}, on a given text of the file rather than the one on disk. */
    @SuppressWarnings("unchecked")
    public String withSetting(String text, String id, String dotted, String value) {
        Map<String, Object> m;
        try {
            m = map(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(id + " is not YAML: " + e.getOriginalMessage(), e);
        }
        String[] parts = dotted.split("\\.");
        Map<String, Object> at = m;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = at.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                at.put(parts[i], next);
            }
            at = (Map<String, Object>) next;
        }
        String leaf = parts[parts.length - 1];
        if (value == null || value.isBlank()) {
            at.remove(leaf);
        } else if (List.of("tags", "peer-only-packs", "nodes").contains(leaf)) {
            at.put(leaf, java.util.Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        } else if (value.equals("true") || value.equals("false")) {
            at.put(leaf, Boolean.parseBoolean(value));
        } else if (value.matches("-?\\d{1,9}")) {
            at.put(leaf, Integer.parseInt(value));
        } else {
            at.put(leaf, value);
        }
        return render(id, m);
    }

    /** A file's settings as its text, with a one-line header. */
    public String render(String id, Map<String, Object> settings) {
        try {
            String body = settings.isEmpty() ? "{}\n" : yaml.writeValueAsString(settings);
            return "# config/net/" + id + " (Drishti*net, docs/admin/NET.md)\n" + body;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
