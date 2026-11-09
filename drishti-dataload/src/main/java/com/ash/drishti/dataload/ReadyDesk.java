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
package com.ash.drishti.dataload;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.FileDates;
import com.ash.drishti.lakeload.FileRows;
import com.ash.drishti.lakeload.LakeLoad;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin, Data loads, Upload and {@code drishti.py dataload ready} (DATA_LAKE_GUIDE.md section 3.5): a data file taken into the staging folder
 * or picked from the inbox folder, its SHA-256 and rows computed on the server, its {@code .ready} built from one form, checked
 * ({@link ReadyChecks}) and pushed, the data file first and the marker last. A file belongs to the person who brought it: nobody else can
 * see or push it, whatever their role.
 */
public final class ReadyDesk {

    private static final Logger LOG = LoggerFactory.getLogger(ReadyDesk.class);
    static final String STAGING = "staging";
    /** The file names taken, and what a name may look like (no folder, no leading dot). */
    static final List<String> ACCEPTED = List.of(".jsonl", ".gz", ".zip", ".tar", ".tar.gz");
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]*");

    /** Faults between the steps of a push (tests). */
    interface Steps {
        void after(String step) throws IOException;

        Steps NONE = s -> { };
    }

    /**
     * {@code drishti.dataload.upload.*}.
     *
     * @param maxGb the largest file taken by upload (10)
     * @param floorGb the free space an upload must leave on the staging folder's disk (5)
     * @param sampleRows the rows of a file (of each table of a tar) the checks read (10,000)
     * @param keepHours how long an upload not pushed stays in the staging folder (24)
     */
    public record Settings(Double maxGb, Double floorGb, Integer sampleRows, Integer keepHours) {

        public Settings {
            maxGb = maxGb == null || maxGb <= 0 ? 10.0 : maxGb;
            floorGb = floorGb == null || floorGb < 0 ? 5.0 : floorGb;
            sampleRows = sampleRows == null || sampleRows <= 0 ? 10_000 : sampleRows;
            keepHours = keepHours == null || keepHours <= 0 ? 24 : keepHours;
        }

        public static Settings defaults() {
            return new Settings(null, null, null, null);
        }

        long maxBytes() {
            return (long) (maxGb * 1024 * 1024 * 1024);
        }

        long floorBytes() {
            return (long) (floorGb * 1024 * 1024 * 1024);
        }
    }

    /** One file on the desk. Only its owner's requests reach it. */
    static final class Item {
        final String id;
        final String user;
        final String name;
        final boolean inInbox;
        final Path file;
        final Instant at;
        final MessageDigest digest;
        volatile long size;
        volatile String sha256;
        volatile long rows = -1;
        volatile long rowsSoFar;
        volatile long hashed;
        volatile String state = "receiving";
        volatile String error;
        volatile Map<String, Long> perTable = Map.of();
        volatile Map<String, java.util.SortedSet<LocalDate>> tables;
        volatile String tarError;
        volatile long sizeAtInspect;
        volatile long modifiedAtInspect;

        Item(String id, String user, String name, boolean inInbox, Path file, Instant at) {
            this.id = id;
            this.user = user;
            this.name = name;
            this.inInbox = inInbox;
            this.file = file;
            this.at = at;
            this.digest = sha256();
        }

        boolean tar() {
            return FileDates.isTar(name);
        }
    }

    private final DataLoadSettings settings;
    private final Settings upload;
    private final DataInbox inbox;
    private final Routes.Packs packs;
    private final Clock clock;
    private final String node;
    private final List<ReadyRule> rules;
    private final Map<String, Item> items = new ConcurrentHashMap<>();
    private final ReentrantLock pushing = new ReentrantLock();
    private final Semaphore inspecting = new Semaphore(2);
    private volatile Steps steps = Steps.NONE;

    public ReadyDesk(DataLoadSettings settings, Settings upload, DataInbox inbox, Routes.Packs packs, Clock clock, String node, List<ReadyRule> rules) {
        this.settings = settings;
        this.upload = upload;
        this.inbox = inbox;
        this.packs = packs;
        this.clock = clock;
        this.node = node;
        this.rules = List.copyOf(rules);
    }

    void steps(Steps s) {
        this.steps = s;
    }

    Path root() {
        return Path.of(settings.dir()).toAbsolutePath().normalize();
    }

    Path inboxDir() {
        return root().resolve("inbox");
    }

    Settings upload() {
        return upload;
    }

    DataLoadSettings settings() {
        return settings;
    }

    Routes.Packs packs() {
        return packs;
    }

    List<ReadyRule> rules() {
        return rules;
    }

    Routes routes() throws IOException {
        return Routes.read(Path.of(settings.rulesFile()), settings.lake(), packs).with(settings.defaultMode(), settings.newTables());
    }

    // -------------------------------------------------------------------------------------------------------------- what the form offers

    /** The pickers and the facts above the form, from what is configured. */
    public Map<String, Object> choices() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> st = inbox.status();
        out.put("on", inbox.started());
        out.put("owner", settings.owner());
        out.put("node", st.get("ownerText") != null ? st.get("ownerText") : node);
        out.put("nextCycleText", st.get("nextCycleText"));
        out.put("maxGb", upload.maxGb());
        out.put("sampleRows", upload.sampleRows());
        out.put("freeBytes", free(inboxDir()));
        out.put("accepted", ACCEPTED);
        out.put("newTables", settings.newTables());
        out.put("defaultLake", settings.lake());
        out.put("lakes", lakes());
        List<Map<String, Object>> tables = new ArrayList<>();
        List<Map<String, Object>> packList = new ArrayList<>();
        for (String p : packs.names()) {
            Routes.PackInfo info = packs.find(p);
            if (info == null) {
                continue;
            }
            packList.add(Map.of("pack", p, "kinds", info.kinds()));
            java.util.Set<String> named = new java.util.HashSet<>();
            List<com.ash.drishti.lakeconfig.PackEntry.PackTable> ts = tablesOf(info);
            for (var t : ts) {
                if (info.kinds().contains(t.kind()) && named.add(t.table())) {
                    boolean own = Routes.norm(t.table()).equals(Routes.norm(t.kind()));
                    tables.add(table(t.table(), t.kind(), p, own ? null : t.connector(), info));
                }
            }
            for (String k : info.kinds()) {
                if (ts.stream().noneMatch(t -> t.kind().equals(k))) {
                    tables.add(table(k, k, p, null, info));
                }
            }
        }
        tables.sort(java.util.Comparator.comparing(m -> String.valueOf(m.get("table"))));
        out.put("tables", tables);
        out.put("packs", packList);
        out.put("waiting", waiting());
        return out;
    }

    private static List<com.ash.drishti.lakeconfig.PackEntry.PackTable> tablesOf(Routes.PackInfo info) {
        try {
            return info.dir() == null ? List.of() : com.ash.drishti.lakeconfig.PackEntry.tables(info.dir());
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static Map<String, Object> table(String table, String kind, String pack, String connector, Routes.PackInfo info) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("table", table);
        m.put("kind", kind);
        m.put("pack", pack);
        m.put("connector", connector);
        m.put("key", com.ash.drishti.lakeconfig.PackEntry.contractKey(info.dir(), kind));
        m.put("label", table + " · " + kind + " · " + pack);
        return m;
    }

    private List<String> lakes() {
        try {
            return com.ash.drishti.lakeconfig.Lakes.current().all().values().stream().filter(l -> !l.inMemory()).map(com.ash.drishti.lakeconfig.Lake::name)
                    .sorted().toList();
        } catch (RuntimeException e) {
            return List.of(settings.lake());
        }
    }

    private long free(Path dir) {
        try {
            Files.createDirectories(dir);
            return Files.getFileStore(dir).getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    /** The files of the inbox folder with no marker: names only. */
    private List<Map<String, Object>> waiting() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Instant now = clock.instant();
            for (Inbox.Waiting w : new Inbox(inboxDir()).look(now, Duration.ofSeconds(settings.stableSeconds()), 0, Long.MAX_VALUE).waiting()) {
                if (Inbox.NO_MARKER.equals(w.code()) && accepted(w.name())) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    long age = Duration.between(w.modified(), now).toSeconds();
                    m.put("file", w.name());
                    m.put("size", w.size());
                    m.put("unchangedSeconds", age);
                    m.put("stable", age >= settings.stableSeconds());
                    out.add(m);
                }
            }
        } catch (IOException e) {
            LOG.warn("data inbox: the inbox folder cannot be listed: {}", e.getMessage());
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------- bringing a file in

    private static boolean accepted(String name) {
        String low = name.toLowerCase(java.util.Locale.ROOT);
        return ACCEPTED.stream().anyMatch(low::endsWith);
    }

    /** Refuses what cannot be a data file's name (a folder, {@code ..}, a leading dot, another type). */
    static String checkName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..") || !NAME.matcher(name).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'" + name + "' cannot name a data file: one name, no folder, no .. and no leading dot");
        }
        if (!accepted(name)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, name + " is not taken: send " + String.join(", ", ACCEPTED));
        }
        if (!LakeLoad.reads(name)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the loader does not read " + name + ": JSON Lines (.jsonl, plain or .gz) or an archive of them");
        }
        return name;
    }

    private void requireOwner() {
        if (!settings.owner()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "this node does not own the data inbox (drishti.dataload.owner is false): use the node that does");
        }
    }

    /** Starts an upload of {@code name}; {@code declared} is its size when known ({@code <= 0}: not known). */
    public Item begin(String name, long declared, String user) throws IOException {
        requireOwner();
        String n = checkName(name);
        if (declared > upload.maxBytes()) {
            throw tooLarge();
        }
        sweep();
        String id = UUID.randomUUID().toString();
        Path dir = root().resolve(STAGING).resolve("upload-" + id);
        Files.createDirectories(dir);
        long free = Files.getFileStore(dir).getUsableSpace();
        if (free - Math.max(0, declared) < upload.floorBytes()) {
            Files.deleteIfExists(dir);
            throw new DrishtiException(ErrorCode.PAYLOAD_TOO_LARGE, "not enough free space on the staging folder's disk: " + (free >> 20) + " MB free and "
                    + upload.floorGb() + " GB must stay free (drishti.dataload.upload.floor-gb)");
        }
        Item it = new Item(id, user, n, false, dir.resolve(n), clock.instant());
        items.put(id, it);
        return it;
    }

    private DrishtiException tooLarge() {
        return new DrishtiException(ErrorCode.PAYLOAD_TOO_LARGE, "the file is over " + upload.maxGb() + " GB (drishti.dataload.upload.max-gb): copy it straight "
                + "into the inbox folder and pick it from there");
    }

    /** One part of an upload, added to its file and its checksum. */
    public void add(Item it, InputStream in) throws IOException {
        byte[] buf = new byte[1 << 20];
        try (OutputStream out = Files.newOutputStream(it.file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            int n;
            long written = it.size;
            while ((n = in.read(buf)) > 0) {
                if (written + n > upload.maxBytes()) {
                    drop(it);
                    throw tooLarge();
                }
                it.digest.update(buf, 0, n);
                out.write(buf, 0, n);
                written += n;
                it.size = written;
            }
        } catch (IOException | RuntimeException e) {
            drop(it);
            throw e;
        }
    }

    /** The upload is whole: its SHA-256 is known and its rows are counted in the background. */
    public Map<String, Object> finish(Item it) {
        it.sha256 = HexFormat.of().formatHex(it.digest.digest());
        it.hashed = it.size;
        inspect(it);
        return state(it);
    }

    /** A whole file at once, read from the stream, never held in memory. */
    public Map<String, Object> receive(InputStream in, String name, long declared, String user) throws IOException {
        Item it = begin(name, declared, user);
        add(it, in);
        return finish(it);
    }

    /** A part of a relayed upload ({@code id} null: the first), kept in step by its owner. */
    public Map<String, Object> part(String id, String name, InputStream in, boolean last, String user) throws IOException {
        Item it = id == null || id.isBlank() ? begin(name, -1, user) : item(id, user);
        add(it, in);
        if (last) {
            return finish(it);
        }
        return state(it);
    }

    /** A file already in the inbox folder, with no marker: nothing is copied; its SHA-256 and rows are computed where it lies. */
    public Map<String, Object> pick(String name, String user) {
        requireOwner();
        String n = checkName(name);
        if (waiting().stream().noneMatch(m -> n.equals(m.get("file")))) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, n + " is not in the inbox folder without a .ready");
        }
        Path f = inboxDir().resolve(n).normalize();
        if (!f.getParent().equals(inboxDir()) || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, n + " is not a plain file of the inbox folder");
        }
        Item it = new Item(UUID.randomUUID().toString(), user, n, true, f, clock.instant());
        try {
            it.size = Files.size(f);
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, n + " cannot be read: " + e.getMessage());
        }
        items.put(it.id, it);
        inspect(it);
        return state(it);
    }

    private void inspect(Item it) {
        it.state = "inspecting";
        Thread.ofVirtual().name("dataload-inspect-" + it.id).start(() -> {
            try {
                inspecting.acquire();
            } catch (InterruptedException e) {
                it.error = "stopped";
                it.state = "failed";
                return;
            }
            try {
                run(it);
                it.state = "ready";
            } catch (IOException | RuntimeException e) {
                it.error = e.getMessage() == null ? e.toString() : e.getMessage();
                it.state = "failed";
            } finally {
                inspecting.release();
            }
        });
    }

    private void run(Item it) throws IOException {
        it.sizeAtInspect = Files.size(it.file);
        it.modifiedAtInspect = Files.getLastModifiedTime(it.file).toMillis();
        if (it.sha256 == null) {
            byte[] buf = new byte[1 << 20];
            try (InputStream in = Files.newInputStream(it.file)) {
                int n;
                long read = 0;
                while ((n = in.read(buf)) > 0) {
                    it.digest.update(buf, 0, n);
                    read += n;
                    it.hashed = read;
                }
            }
            it.sha256 = HexFormat.of().formatHex(it.digest.digest());
        }
        if (it.tar()) {
            try {
                it.tables = LakeLoad.tarTables(it.file.toString());
            } catch (IOException e) {
                it.tarError = e.getMessage();
                return;
            }
        }
        FileRows.Counted c = FileRows.count(it.file, n -> it.rowsSoFar = n);
        it.perTable = c.perDataset();
        it.rows = c.rows();
    }

    /** The file's state for its owner. */
    public Map<String, Object> state(Item it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.id);
        m.put("file", it.name);
        m.put("source", it.inInbox ? "inbox" : "upload");
        m.put("state", it.state);
        m.put("size", it.size);
        m.put("sha256", it.sha256);
        m.put("rows", it.rows < 0 ? null : it.rows);
        m.put("hashed", it.hashed);
        m.put("rowsSoFar", it.rowsSoFar);
        m.put("error", it.error);
        m.put("tar", it.tar());
        FileDates.Name nm = FileDates.parse(it.name);
        Map<String, Object> s = new LinkedHashMap<>();
        if (!it.tar() && nm.error() == null) {
            s.put("table", nm.table());
            s.put("date", nm.date() == null ? null : nm.date().toString());
        }
        m.put("suggest", s);
        if (it.tables != null) {
            List<Map<String, Object>> list = new ArrayList<>();
            it.tables.forEach((t, days) -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("folder", t);
                e.put("dates", days.stream().map(LocalDate::toString).toList());
                e.put("rows", it.perTable.get(t));
                list.add(e);
            });
            m.put("folders", list);
        }
        m.put("layoutError", it.tarError);
        return m;
    }

    /** A person's own file. */
    public Item item(String id, String user) {
        Item it = id == null ? null : items.get(id);
        if (it == null || !it.user.equals(user)) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no such file on your desk: upload or pick it again");
        }
        return it;
    }

    /** A person's upload, dropped; a file picked from the inbox stays where it is. */
    public void discard(String id, String user) {
        drop(item(id, user));
    }

    private void drop(Item it) {
        items.remove(it.id);
        if (!it.inInbox) {
            remove(it.file.getParent());
        }
    }

    private void sweep() {
        Path staging = root().resolve(STAGING);
        Instant oldest = clock.instant().minus(Duration.ofHours(upload.keepHours()));
        items.values().removeIf(it -> {
            if (it.at.isBefore(oldest)) {
                if (!it.inInbox) {
                    remove(it.file.getParent());
                }
                return true;
            }
            return false;
        });
        if (!Files.isDirectory(staging)) {
            return;
        }
        java.util.Set<Path> live = new java.util.HashSet<>();
        items.values().forEach(it -> live.add(it.file.getParent()));
        try (var dirs = Files.list(staging)) {
            for (Path d : dirs.filter(p -> p.getFileName().toString().startsWith("upload-") && !live.contains(p)).toList()) {
                if (Files.getLastModifiedTime(d).toInstant().isBefore(oldest)) {
                    remove(d);
                }
            }
        } catch (IOException e) {
            LOG.warn("data inbox: the staging folder cannot be swept: {}", e.getMessage());
        }
    }

    private static void remove(Path dir) {
        try (var all = Files.walk(dir)) {
            for (Path p : all.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            LOG.warn("data inbox: {} cannot be removed: {}", dir, e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------------------------- preview and check

    /** The exact {@code .ready} text of the form, its field errors, and what the table resolves to. */
    public ReadyChecks.Built preview(ReadyForm form, String user) {
        Item it = form.item() == null || form.item().isBlank() ? null : item(form.item(), user);
        return new ReadyChecks(this).build(form, it, it != null ? it.name : checkName(form.file()));
    }

    /** The checks of the form and its file. */
    public Map<String, Object> check(ReadyForm form, String user) {
        Item it = item(form.item(), user);
        ReadyChecks.Built b = new ReadyChecks(this).build(form, it, it.name);
        List<ReadyCheck> list = new ReadyChecks(this).run(form, it, b);
        return answer(b, list);
    }

    static Map<String, Object> answer(ReadyChecks.Built b, List<ReadyCheck> list) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checks", list);
        m.put("text", b.text());
        m.put("errors", b.errors());
        m.put("blocked", list.stream().anyMatch(c -> ReadyCheck.REFUSED.equals(c.status())));
        m.put("tick", list.stream().anyMatch(ReadyCheck::tick));
        return m;
    }

    /** Parses a {@code .ready} text someone already has into the form's fields, by the same rules as the inbox. */
    public Map<String, Object> read(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            ReadyNote n = ReadyNote.parse(text);
            m.put("lake", n.lake());
            m.put("pack", n.pack());
            m.put("kind", n.kind());
            m.put("connector", n.connector());
            m.put("table", n.table());
            m.put("businessDate", n.businessDate() == null ? null : n.businessDate().toString());
            m.put("mode", n.mode());
            m.put("newTable", n.newTable());
            m.put("key", n.key());
            m.put("folders", n.folders());
            m.put("hold", n.hold());
        } catch (IllegalArgumentException e) {
            m.put("refused", e.getMessage());
        }
        return m;
    }

    // --------------------------------------------------------------------------------------------------------------------------------- push

    /** What was pushed, for the answer and the audit. */
    public record Pushed(String file, String marker, String sha256, Long rows, String text, boolean hold, String node, String nextCycleText) {}

    /**
     * Pushes the file: the checks again, on the server; then the data file into the inbox folder and the marker last, each appearing there
     * whole or not at all. A refusal, a missing tick, or a file of that name already waiting pushes nothing.
     */
    public Pushed push(ReadyForm form, String user) throws IOException {
        requireOwner();
        Item it = item(form.item(), user);
        if (!"ready".equals(it.state) || it.sha256 == null || it.rows < 0) {
            throw refused("the file is not checked yet: wait for its SHA-256 and rows" + (it.error == null ? "" : " (" + it.error + ")"));
        }
        pushing.lock();
        try {
            if (it.inInbox && (Files.size(it.file) != it.sizeAtInspect || Files.getLastModifiedTime(it.file).toMillis() != it.modifiedAtInspect)) {
                throw refused(it.name + " changed in the inbox folder after it was checked: choose it again");
            }
            ReadyChecks c = new ReadyChecks(this);
            ReadyChecks.Built b = c.build(form, it, it.name);
            List<ReadyCheck> list = c.run(form, it, b);
            ReadyCheck bad = list.stream().filter(x -> ReadyCheck.REFUSED.equals(x.status())).findFirst().orElse(null);
            if (bad != null) {
                throw refused(bad.text() + (bad.fix() == null ? "" : " " + bad.fix()));
            }
            if (list.stream().anyMatch(ReadyCheck::tick) && !Boolean.TRUE.equals(form.ackReplace())) {
                throw refused("this replace removes rows already in the table: tick that you mean it, or use append");
            }
            String taken = waitingName(it);
            if (taken != null) {
                throw refused(taken);
            }
            Path in = inboxDir();
            Files.createDirectories(in);
            Path marker = in.resolve(it.name + Inbox.READY);
            Path staged = root().resolve(STAGING).resolve("marker-" + it.id);
            Files.createDirectories(staged.getParent());
            if (!it.inInbox) {
                Files.createDirectories(in);
                moveNew(it.file, in.resolve(it.name));
            }
            steps.after("data");
            try (var ch = java.nio.channels.FileChannel.open(staged, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ch.write(java.nio.ByteBuffer.wrap(b.text().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                ch.force(true);
            }
            moveNew(staged, marker);
            items.remove(it.id);
            if (!it.inInbox) {
                remove(it.file.getParent());
            }
            Map<String, Object> ch = choices();
            return new Pushed(it.name, marker.getFileName().toString(), it.sha256, it.rows, b.text(), Boolean.TRUE.equals(form.hold()),
                    String.valueOf(ch.get("node")), ch.get("nextCycleText") == null ? null : String.valueOf(ch.get("nextCycleText")));
        } finally {
            pushing.unlock();
        }
    }

    /** Why the file's name is taken in the inbox folder, or null. */
    String waitingName(Item it) throws IOException {
        Path in = inboxDir();
        if (!it.inInbox && Files.exists(in.resolve(it.name), LinkOption.NOFOLLOW_LINKS)) {
            return "a file named " + it.name + " is already waiting in the inbox: wait until it is loaded, or give this one another name";
        }
        for (String m : List.of(it.name + Inbox.READY, Inbox.shortName(it.name) + Inbox.READY)) {
            if (Files.exists(in.resolve(m), LinkOption.NOFOLLOW_LINKS)) {
                return "a marker named " + m + " is already in the inbox: " + it.name + " would be released by it; wait until it is loaded";
            }
        }
        return null;
    }

    /** Puts a file at {@code to} only if nothing is there, whole or not at all. */
    private void moveNew(Path from, Path to) throws IOException {
        try {
            Files.createLink(to, from);
            Files.delete(from);
            return;
        } catch (FileAlreadyExistsException e) {
            throw refused("a file named " + to.getFileName() + " is already waiting in the inbox");
        } catch (UnsupportedOperationException | SecurityException e) {
            // no hard links here: below
        } catch (FileSystemException e) {
            if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                throw refused("a file named " + to.getFileName() + " is already waiting in the inbox");
            }
        }
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
            throw refused("a file named " + to.getFileName() + " is already waiting in the inbox");
        }
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("the staging and inbox folders must be on one file system: " + e.getMessage(), e);
        }
    }

    private static DrishtiException refused(String why) {
        return new DrishtiException(ErrorCode.READY_PUSH_REFUSED, why);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
