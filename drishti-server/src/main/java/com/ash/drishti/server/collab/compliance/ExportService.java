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
package com.ash.drishti.server.collab.compliance;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.Comment;
import com.ash.drishti.identity.collab.CommentThread;
import com.ash.drishti.identity.collab.Hold;
import com.ash.drishti.identity.collab.Mention;
import com.ash.drishti.identity.collab.Pin;
import com.ash.drishti.identity.collab.Recipient;
import com.ash.drishti.identity.collab.Revision;
import com.ash.drishti.identity.collab.Share;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The compliance (eDiscovery) export: an asynchronous job that streams the collaboration record into one zip under
 * {@code <drishti.collab.dir>/exports}: {@code shares.ndjson}, {@code threads.ndjson} (every comment with every revision,
 * unscrubbed: this is the record), {@code chains.ndjson} (each thread's first and last hash and the verification result),
 * {@code holds.ndjson}, {@code README.txt} and, last, {@code manifest.json} with the filters, who and when, the counts and the SHA-256
 * and size of every other file. Entries are written line by line through a digest, so memory is bounded whatever the size; one export
 * runs at a time per server. The requester downloads it once; it is deleted then or after {@code export-keep}. Starting, finishing and
 * downloading are audited. Needs the {@code compliance} power.
 */
public final class ExportService implements AutoCloseable {

    /** The filters; every field optional. Dates are ISO instants or days (UTC). */
    public record Request(String from, String to, String kind, String id, String user, Boolean includeShares, Boolean includeThreads) {}

    /** Where a job is: {@code queued}, {@code running}, {@code done}, {@code failed}, {@code downloaded} or {@code expired}. */
    public static final class Job {
        final String id;
        final String requester;
        final Instant created;
        final Map<String, Object> filters;
        volatile String state = "queued";
        volatile String error;
        volatile Path file;
        volatile Instant finished;
        volatile Map<String, Object> counts = Map.of();

        Job(String id, String requester, Instant created, Map<String, Object> filters) {
            this.id = id;
            this.requester = requester;
            this.created = created;
            this.filters = filters;
        }

        public Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("state", state);
            m.put("requestedBy", requester);
            m.put("createdAt", created);
            m.put("finishedAt", finished);
            m.put("filters", filters);
            m.put("counts", counts);
            m.put("error", error);
            return m;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(ExportService.class);
    private static final int PAGE = 200;

    private final CollabProperties props;
    private final ThreadStore threads;
    private final ShareStore shares;
    private final HoldServiceView holds;
    private final UserService users;
    private final Entitlements entitlements;
    private final AuditLog audit;
    private final ChainVerifier verifier;
    private final Clock clock;
    private final String version;
    private final Path dir;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService worker;

    /** The holds an export lists (a narrow view so the export does not need the whole service). */
    public interface HoldServiceView {
        List<Hold> all();
    }

    @SuppressWarnings("java:S107")
    public ExportService(CollabProperties props, ThreadStore threads, ShareStore shares, HoldServiceView holds, UserService users,
            Entitlements entitlements, AuditLog audit, ChainVerifier verifier, Clock clock, String version) {
        this.props = props;
        this.threads = threads;
        this.shares = shares;
        this.holds = holds;
        this.users = users;
        this.entitlements = entitlements;
        this.audit = audit;
        this.verifier = verifier;
        this.clock = clock;
        this.version = version == null || version.isBlank() ? "unknown" : version;
        this.dir = Path.of(props.dir()).toAbsolutePath().normalize().resolve("exports");
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
                r -> Thread.ofPlatform().daemon().name("drishti-collab-export").unstarted(r));
    }

    /** Queues an export; returns the job to poll ({@code 202}). */
    public Job start(Principal p, Request r) {
        entitlements.requireCompliance(p);
        if (!props.enabled()) {
            throw new DrishtiException(ErrorCode.SHARING_OFF, "collaboration is off (drishti.collab.enabled)");
        }
        Instant from = ComplianceDates.from(r.from());
        Instant to = ComplianceDates.to(r.to());
        ComplianceDates.requireOrdered(from, to);
        boolean withShares = r.includeShares() == null || r.includeShares();
        boolean withThreads = r.includeThreads() == null || r.includeThreads();
        if (!withShares && !withThreads) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "an export needs includeShares or includeThreads");
        }
        sweep();
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("from", from);
        filters.put("to", to);
        filters.put("kind", blank(r.kind()));
        filters.put("id", blank(r.id()));
        filters.put("user", blank(r.user()));
        filters.put("includeShares", withShares);
        filters.put("includeThreads", withThreads);
        Job job = new Job(UUID.randomUUID().toString().replace("-", ""), p.user(), clock.instant(), filters);
        jobs.put(job.id, job);
        Filter f = new Filter(from, to, blank(r.kind()), blank(r.id()), blank(r.user()), withShares, withThreads);
        try {
            worker.execute(() -> run(job, f));
        } catch (RejectedExecutionException e) {
            jobs.remove(job.id);
            throw new DrishtiException(ErrorCode.COLLAB_RATE_LIMITED, "too many exports are waiting; try again shortly");
        }
        audit.record(p.user(), "collab.export.start", "export " + job.id, filters.toString());
        return job;
    }

    /** The job's state, for any {@code compliance} user (it says who asked). */
    public Job status(Principal p, String id) {
        entitlements.requireCompliance(p);
        sweep();
        Job j = jobs.get(id);
        if (j == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no export " + id + " (finished exports are kept " + props.exportKeep() + ")");
        }
        return j;
    }

    /** The zip, for its requester, once: the stream deletes the file when closed. */
    public Download download(Principal p, String id) {
        Job j = status(p, id);
        if (!j.requester.equals(p.user())) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "an export is downloaded by the person who asked for it");
        }
        synchronized (j) {
            if (!"done".equals(j.state) || j.file == null) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "export " + id + " is " + j.state + (j.state.equals("downloaded")
                        ? ": an export is downloaded once; ask for it again" : ""));
            }
            j.state = "downloaded";
        }
        Path file = j.file;
        audit.record(p.user(), "collab.export.download", "export " + id, "downloaded " + file.getFileName());
        try {
            long size = Files.size(file);
            InputStream in = new FilterInputStream(Files.newInputStream(file)) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        Files.deleteIfExists(file);
                    }
                }
            };
            return new Download(in, size, id + ".zip");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A zip to stream. */
    public record Download(InputStream stream, long size, String name) {}

    // ---- the job -----------------------------------------------------------------------------------------------------------------

    private record Filter(Instant from, Instant to, String kind, String id, String user, boolean shares, boolean threads) {}

    private void run(Job job, Filter f) {
        job.state = "running";
        Path part = dir.resolve(job.id + ".zip.part");
        Path zip = dir.resolve(job.id + ".zip");
        try {
            Files.createDirectories(dir);
            Map<String, Object> counts = new LinkedHashMap<>();
            List<Map<String, Object>> files = new ArrayList<>();
            try (OutputStream raw = Files.newOutputStream(part); ZipOutputStream out = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
                readme(out);
                Function<String, String> names = nameCache();
                Sink sh = new Sink(out, "shares.ndjson");
                if (f.shares()) {
                    exportShares(sh, f, names);
                }
                files.add(sh.close());
                Sink th = new Sink(out, "threads.ndjson");
                Sink ch = new Sink(out, "chains.ndjson");
                // two entries cannot be open at once in a zip: threads and chains are written one after the other
                long[] tallies = new long[3];
                Path chainTmp = dir.resolve(job.id + ".chains.tmp");
                try (java.io.BufferedWriter chains = Files.newBufferedWriter(chainTmp, StandardCharsets.UTF_8)) {
                    if (f.threads()) {
                        exportThreads(th, f, names, chains, tallies);
                    }
                }
                files.add(th.close());
                try (java.io.BufferedReader in = Files.newBufferedReader(chainTmp, StandardCharsets.UTF_8)) {
                    for (String line; (line = in.readLine()) != null; ) {
                        ch.line(line);
                    }
                } finally {
                    Files.deleteIfExists(chainTmp);
                }
                files.add(ch.close());
                Sink hs = new Sink(out, "holds.ndjson");
                int holdCount = 0;
                for (Hold h : holds.all()) {
                    hs.line(json.writeValueAsString(holdNode(h)));
                    holdCount++;
                }
                files.add(hs.close());
                counts.put("shares", sh.lines);
                counts.put("threads", th.lines);
                counts.put("comments", tallies[0]);
                counts.put("revisions", tallies[1]);
                counts.put("chainsBroken", tallies[2]);
                counts.put("holds", holdCount);
                manifest(out, job, f, counts, files);
            }
            Files.move(part, zip, StandardCopyOption.REPLACE_EXISTING);
            job.file = zip;
            job.counts = counts;
            job.finished = clock.instant();
            job.state = "done";
            audit.record(job.requester, "collab.export.done", "export " + job.id, counts.toString());
        } catch (IOException | RuntimeException e) {
            LOG.warn("collaboration export {} failed: {}", job.id, e.toString());
            job.error = e.getMessage() == null ? e.toString() : e.getMessage();
            job.finished = clock.instant();
            job.state = "failed";
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // nothing more to do
            }
            audit.record(job.requester, "collab.export.failed", "export " + job.id, job.error);
        }
    }

    private void exportShares(Sink out, Filter f, Function<String, String> names) throws IOException {
        for (String after = null; ; ) {
            List<Share> page = shares.page(after, PAGE);
            if (page.isEmpty()) {
                return;
            }
            for (Share s : page) {
                after = s.id();
                if (!shareMatches(s, f)) {
                    continue;
                }
                List<Recipient> recipients = shares.recipients(s.id());
                if (f.user() != null && !s.sender().equals(f.user()) && recipients.stream().noneMatch(r -> r.username().equals(f.user()))) {
                    continue;
                }
                ObjectNode n = json.createObjectNode();
                n.put("type", "share");
                n.put("id", s.id());
                person(n, "sender", s.sender(), names);
                n.put("createdAt", s.createdAt().toString());
                n.put("kind", s.kind());
                n.put("entityId", s.entityId());
                n.put("panelId", s.panelId());
                n.set("pin", pin(s.pin()));
                n.put("body", s.body());
                n.set("maskedSpans", json.valueToTree(s.maskedSpans()));
                n.put("channels", s.channels());
                n.put("threadId", s.threadId());
                n.put("hash", s.hash());
                n.put("hashOk", s.intact());
                ArrayNode rs = n.putArray("recipients");
                for (Recipient r : recipients) {
                    ObjectNode o = rs.addObject();
                    o.put("addressed", r.addressed());
                    person(o, "user", r.username(), names);
                    o.put("state", r.state());
                    o.put("openedAt", r.openedAt() == null ? null : r.openedAt().toString());
                }
                out.line(json.writeValueAsString(n));
            }
        }
    }

    private static boolean shareMatches(Share s, Filter f) {
        return (f.kind() == null || f.kind().equals(s.kind())) && (f.id() == null || f.id().equals(s.entityId()))
                && (f.from() == null || !s.createdAt().isBefore(f.from())) && (f.to() == null || !s.createdAt().isAfter(f.to()));
    }

    private void exportThreads(Sink out, Filter f, Function<String, String> names, java.io.BufferedWriter chains, long[] tallies) throws IOException {
        if (f.kind() != null && f.id() != null) {
            for (CommentThread t : threads.threads(f.kind(), f.id()).stream().sorted(java.util.Comparator.comparing(CommentThread::id)).toList()) {
                exportThread(out, t, f, names, chains, tallies);
            }
            return;
        }
        for (String after = null; ; ) {
            List<CommentThread> page = threads.page(after, PAGE);
            if (page.isEmpty()) {
                return;
            }
            for (CommentThread t : page) {
                after = t.id();
                if (f.kind() == null || f.kind().equals(t.kind())) {
                    exportThread(out, t, f, names, chains, tallies);
                }
            }
        }
    }

    private void exportThread(Sink out, CommentThread t, Filter f, Function<String, String> names, java.io.BufferedWriter chains, long[] tallies)
            throws IOException {
        if ((f.from() != null && t.lastAt().isBefore(f.from())) || (f.to() != null && t.createdAt().isAfter(f.to()))) {
            return;
        }
        List<Comment> comments = threads.comments(t.id());
        if (f.user() != null && !t.createdBy().equals(f.user()) && comments.stream().noneMatch(c -> c.author().equals(f.user()))) {
            return;
        }
        ObjectNode n = json.createObjectNode();
        n.put("type", "thread");
        n.put("id", t.id());
        n.put("kind", t.kind());
        n.put("entityId", t.entityId());
        n.put("anchor", t.anchor());
        n.put("panelId", t.panelId());
        n.put("path", t.path());
        n.put("gateKind", t.gateKind());
        n.put("anchorLabel", t.anchorLabel());
        n.put("state", t.state());
        person(n, "createdBy", t.createdBy(), names);
        n.put("createdAt", t.createdAt().toString());
        n.put("lastAt", t.lastAt().toString());
        ArrayNode cs = n.putArray("comments");
        for (Comment c : comments) {
            ObjectNode o = cs.addObject();
            o.put("id", c.id());
            person(o, "author", c.author(), names);
            o.put("createdAt", c.createdAt().toString());
            o.put("editedAt", c.editedAt() == null ? null : c.editedAt().toString());
            o.put("state", c.state());
            o.put("stateReason", c.stateReason());
            o.set("pin", pin(c.pin()));
            o.put("body", c.body());
            o.set("maskedSpans", json.valueToTree(c.maskedSpans()));
            ArrayNode ms = o.putArray("mentions");
            for (Mention m : threads.mentions(c.id())) {
                ms.add(m.target());
            }
            ArrayNode rs = o.putArray("revisions");
            for (Revision r : threads.revisions(c.id())) {
                ObjectNode x = rs.addObject();
                x.put("revision", r.revision());
                x.put("at", r.at().toString());
                person(x, "actor", r.actor(), names);
                x.put("action", r.action());
                x.put("body", r.body());
                x.put("reason", r.reason());
                x.put("prevHash", r.prevHash());
                x.put("hash", r.hash());
                tallies[1]++;
            }
            tallies[0]++;
        }
        ChainVerifier.ThreadReport rep = verifier.report(t.id());
        ObjectNode chain = n.putObject("chain");
        chain.put("genesis", com.ash.drishti.identity.collab.HashChain.genesis(t.id()));
        chain.put("steps", rep.steps());
        chain.put("firstHash", rep.firstHash());
        chain.put("lastHash", rep.lastHash());
        chain.put("verified", rep.ok());
        chain.put("problem", rep.problem());
        if (!rep.ok()) {
            tallies[2]++;
        }
        out.line(json.writeValueAsString(n));
        ObjectNode c = json.createObjectNode();
        c.put("thread", t.id());
        c.put("kind", t.kind());
        c.put("entityId", t.entityId());
        c.put("steps", rep.steps());
        c.put("firstHash", rep.firstHash());
        c.put("lastHash", rep.lastHash());
        c.put("verified", rep.ok());
        c.put("problem", rep.problem());
        chains.write(json.writeValueAsString(c));
        chains.newLine();
    }

    private ObjectNode holdNode(Hold h) {
        ObjectNode n = json.createObjectNode();
        n.put("id", h.id());
        n.put("scope", h.scope());
        n.put("kind", h.kind());
        n.put("entityId", h.entityId());
        n.put("user", h.username());
        n.put("thread", h.threadId());
        n.put("from", h.from() == null ? null : h.from().toString());
        n.put("to", h.to() == null ? null : h.to().toString());
        n.put("reason", h.reason());
        n.put("placedBy", h.placedBy());
        n.put("placedAt", h.placedAt().toString());
        n.put("releasedBy", h.releasedBy());
        n.put("releasedAt", h.releasedAt() == null ? null : h.releasedAt().toString());
        return n;
    }

    private ObjectNode pin(Pin p) {
        ObjectNode n = json.createObjectNode();
        if (p != null) {
            n.put("businessDate", p.businessDate() == null ? null : p.businessDate().toString());
            n.put("live", p.live());
            n.put("knownAt", p.knownAt() == null ? null : p.knownAt().toString());
            n.put("generation", p.generation());
            n.put("source", p.source());
        }
        return n;
    }

    private static void person(ObjectNode n, String field, String user, Function<String, String> names) {
        n.put(field, user);
        n.put(field + "Name", names.apply(user));
    }

    private Function<String, String> nameCache() {
        Map<String, String> cache = new java.util.HashMap<>();
        return u -> u == null ? null : cache.computeIfAbsent(u, k -> users.find(k).map(User::displayName).orElse(""));
    }

    // ---- entries -----------------------------------------------------------------------------------------------------------------

    /** One zip entry written line by line through a SHA-256 digest. */
    private static final class Sink {
        private final ZipOutputStream zip;
        private final String name;
        private final MessageDigest digest = digest();
        long lines;
        long bytes;
        private boolean open;

        Sink(ZipOutputStream zip, String name) {
            this.zip = zip;
            this.name = name;
        }

        void line(String text) throws IOException {
            if (!open) {
                zip.putNextEntry(new ZipEntry(name));
                open = true;
            }
            byte[] b = (text + "\n").getBytes(StandardCharsets.UTF_8);
            digest.update(b);
            zip.write(b);
            bytes += b.length;
            lines++;
        }

        Map<String, Object> close() throws IOException {
            if (!open) {
                zip.putNextEntry(new ZipEntry(name));
            }
            zip.closeEntry();
            open = false;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("lines", lines);
            m.put("bytes", bytes);
            m.put("sha256", HexFormat.of().formatHex(digest.digest()));
            return m;
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void manifest(ZipOutputStream out, Job job, Filter f, Map<String, Object> counts, List<Map<String, Object>> files) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "drishti-collab-export/1");
        m.put("exportId", job.id);
        m.put("requestedBy", job.requester);
        m.put("requestedAt", job.created);
        m.put("completedAt", clock.instant());
        m.put("software", Map.of("product", "Drishti", "version", version));
        m.put("filters", job.filters);
        m.put("counts", counts);
        m.put("files", files);
        m.put("note", "chains.ndjson lists each thread's first and last hash: a later export that disagrees shows history was rewritten");
        out.putNextEntry(new ZipEntry("manifest.json"));
        out.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(m));
        out.closeEntry();
    }

    private void readme(ZipOutputStream out) throws IOException {
        String text = """
                Drishti collaboration export (drishti-collab-export/1)

                One JSON object per line, UTF-8, times in ISO-8601 UTC, ids stable. Every person appears as a user name and, beside it,
                the display name at the time of export (field + "Name").

                shares.ndjson   each share: sender, pin (business date, knownAt, generation), the note as written, masked spans, channels,
                                every recipient with the state (notified, no-access, no-pack, disabled, over-limit) and the time it was
                                first opened, the share's own SHA-256 and whether it still matches (hashOk).
                threads.ndjson  each thread with every comment and every revision (created, edited, retracted, hidden, unhidden):
                                author or actor, pin, text, reason, mentions, prevHash and hash. Text is the record, unscrubbed.
                                "chain" holds the genesis hash (SHA-256 of the thread id), the first and last hash and whether the chain verified.
                chains.ndjson   one line per thread: first and last hash and the verification result, for the archive to compare with
                                a later export.
                holds.ndjson    every legal hold, active or released.
                manifest.json   the filters, who and when, counts, the software version, and the SHA-256, size and line count of
                                every file above (written last).

                Verify a chain: hash = SHA-256(prevHash, commentId, revision, at, actor, action, body, reason), each field followed by
                the unit separator U+001F and null written as empty; the first revision's prevHash is the genesis hash.
                """;
        out.putNextEntry(new ZipEntry("README.txt"));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Forgets finished jobs older than {@code export-keep} and deletes their files. */
    void sweep() {
        Instant cutoff = clock.instant().minus(props.exportKeep());
        for (Job j : jobs.values()) {
            if (j.finished != null && j.finished.isBefore(cutoff)) {
                jobs.remove(j.id);
                Path f = j.file;
                if (f != null) {
                    try {
                        Files.deleteIfExists(f);
                    } catch (IOException e) {
                        LOG.warn("could not delete export {}: {}", f, e.toString());
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
