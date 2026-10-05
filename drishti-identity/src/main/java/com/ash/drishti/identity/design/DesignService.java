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
package com.ash.drishti.identity.design;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.design.StoredDesign.SampleInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The rules around a {@link DesignStore}: a Design is reachable only by its owner (anyone else gets "not found", exactly as
 * for a Design that does not exist), the quotas of {@link DesignProperties} (a limit answers {@code 413 DRS-5005}), and expiry
 * (scratch Designs after a day, named ones after 90 days untouched, with a warning from 75). Writes of one user are serialised;
 * different users never contend. Nothing here logs sample contents, only counts.
 */
public final class DesignService {

    private static final Logger LOG = LoggerFactory.getLogger(DesignService.class);
    private static final int NAME_MAX = 120;
    private static final int SAMPLE_NAME_MAX = 200;

    private final DesignStore store;
    private final DesignProperties props;
    private final LongSupplier clock;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public DesignService(DesignStore store, DesignProperties props, LongSupplier clock) {
        this.store = store;
        this.props = props;
        this.clock = clock;
    }

    public DesignService(DesignStore store, DesignProperties props) {
        this(store, props, System::currentTimeMillis);
    }

    public DesignProperties limits() {
        return props;
    }

    /** What changes in an update; null fields stay as they are. */
    public record Patch(String name, String kind, String notes, String sutra, List<JsonNode> tests) {}

    /** A sample to add: a document ({@code json}), a reference ({@code refKind}, {@code refId}) or a synthetic document. */
    public record NewSample(String name, String type, String refKind, String refId, String json) {}

    /** A listed Design: its metadata, its size and when it expires. */
    public record Summary(StoredDesign design, long bytes, long expiresAt, boolean expiryWarning) {}

    // ---- reads ---------------------------------------------------------------------------------------------------

    public List<Summary> list(String user) {
        List<Summary> out = new ArrayList<>();
        for (StoredDesign d : store.list(user)) {
            out.add(summary(d));
        }
        out.sort(Comparator.comparingLong((Summary s) -> s.design().updated).reversed());
        return out;
    }

    public Summary summary(StoredDesign d) {
        long ttl = (d.scratch ? props.scratchTtl() : props.namedTtl()).toMillis();
        boolean warn = !d.scratch && clock.getAsLong() - d.updated >= props.warnAfter().toMillis();
        return new Summary(d, d.totalBytes(), d.updated + ttl, warn);
    }

    /** The owner's Design, else 404 DRS-5006. Opening one counts as touching it, at most once an hour. */
    public StoredDesign open(String user, String id) {
        StoredDesign d = get(user, id);
        if (clock.getAsLong() - d.updated > 3_600_000L) {
            d.updated = clock.getAsLong();
            store.save(d);
        }
        return d;
    }

    public StoredDesign get(String user, String id) {
        return store.get(user, id).filter(d -> user.equals(d.owner)).filter(d -> !expired(d))
                .orElseThrow(() -> new DrishtiException(ErrorCode.DESIGN_NOT_FOUND, "no design '" + id + "'"));
    }

    public String sample(String user, String id, String name) {
        get(user, id);
        return store.sample(user, id, name).orElseThrow(() -> new DrishtiException(ErrorCode.DESIGN_NOT_FOUND, "no kept sample '" + name + "' in this design"));
    }

    // ---- writes --------------------------------------------------------------------------------------------------

    public StoredDesign create(String user, String name, String kind, String base, String sutra, String notes) {
        return locked(user, () -> {
            if (clean(name).isEmpty()) {
                evictScratch(user, null, props.maxScratch() - 1);
            } else {
                requireRoomForNamed(user);
            }
            requireText(user, null, sutra, notes, List.of());
            return create0(user, name, kind, base, sutra, notes);
        });
    }

    /** Scratch Designs do not count toward {@code max-per-user}: only named ones do. */
    private void requireRoomForNamed(String user) {
        if (store.list(user).stream().filter(d -> !d.scratch).count() >= props.maxPerUser()) {
            throw tooMany("you keep " + props.maxPerUser() + " named designs, the most allowed (drishti.builder.designs.max-per-user): delete one first");
        }
    }

    /** Deletes the oldest scratch Designs (never {@code keep}) until at most {@code room} remain; returns how many went. */
    private int evictScratch(String user, String keep, int room) {
        List<StoredDesign> scratch = new ArrayList<>(store.list(user).stream().filter(d -> d.scratch && !d.id.equals(keep)).toList());
        scratch.sort(Comparator.comparingLong((StoredDesign d) -> d.updated));
        int gone = 0;
        while (scratch.size() - gone > Math.max(0, room)) {
            store.delete(user, scratch.get(gone).id);
            gone++;
        }
        return gone;
    }

    /** Deletes all of the user's scratch Designs (My designs, "delete scratch designs"); returns how many. */
    public int deleteScratch(String user) {
        return locked(user, () -> evictScratch(user, null, 0));
    }

    /**
     * The Sutra, notes and tests count against the Design and user limits like samples do, each with a cap of its own; a text that
     * only shrinks is always accepted. {@code current} is the stored Design (null for a new one).
     */
    private void requireText(String user, StoredDesign current, String sutra, String notes, List<JsonNode> tests) {
        long sutraBytes = sutra == null ? 0 : sutra.getBytes(StandardCharsets.UTF_8).length;
        long notesBytes = notes == null ? 0 : notes.getBytes(StandardCharsets.UTF_8).length;
        long testsBytes = StoredDesign.testsBytes(tests);
        long sutraMax = props.maxSutraKb() * 1024L;
        long notesMax = props.maxNotesKb() * 1024L;
        long testsMax = props.maxTestsKb() * 1024L;
        if (sutraBytes > sutraMax && (current == null || sutraBytes > current.sutra.getBytes(StandardCharsets.UTF_8).length)) {
            throw tooMany("the Sutra is over " + props.maxSutraKb() + " KB (drishti.builder.designs.max-sutra-kb)");
        }
        if (notesBytes > notesMax && (current == null || notesBytes > current.notes.getBytes(StandardCharsets.UTF_8).length)) {
            throw tooMany("the notes are over " + props.maxNotesKb() + " KB (drishti.builder.designs.max-notes-kb)");
        }
        if (testsBytes > testsMax && (current == null || testsBytes > StoredDesign.testsBytes(current.tests))) {
            throw tooMany("the tests are over " + props.maxTestsKb() + " KB (drishti.builder.designs.max-tests-kb)");
        }
        long before = current == null ? 0 : current.textBytes();
        long after = sutraBytes + notesBytes + testsBytes;
        if (after <= before) {
            return;
        }
        long design = (current == null ? 0 : current.sampleBytes()) + after;
        if (design > props.maxBytes()) {
            throw tooMany("the design would hold over " + props.maxMb() + " MB with its Sutra, notes and tests (drishti.builder.designs.max-mb)");
        }
        if (userBytes(user) - before + after > props.maxUserBytes()) {
            throw tooMany("your designs would hold over " + props.maxUserMb() + " MB with Sutra, notes and tests (drishti.builder.designs.max-user-mb)");
        }
    }

    private StoredDesign create0(String user, String name, String kind, String base, String sutra, String notes) {
        StoredDesign d = new StoredDesign();
        d.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        d.owner = user;
        d.name = clean(name);
        d.scratch = d.name.isEmpty();
        d.kind = kind == null || kind.isBlank() ? "sample" : kind.trim();
        d.base = base == null || base.isBlank() ? null : base;
        d.sutra = sutra == null ? "" : sutra;
        d.rev = d.sutra.isEmpty() ? 0 : 1;
        d.notes = notes == null ? "" : notes;
        d.created = d.updated = clock.getAsLong();
        store.save(d);
        return d;
    }

    public StoredDesign update(String user, String id, Patch p) {
        return locked(user, () -> {
            StoredDesign d = get(user, id);
            requireText(user, d, p.sutra() != null ? p.sutra() : d.sutra, p.notes() != null ? p.notes() : d.notes, p.tests() != null ? p.tests() : d.tests);
            if (p.name() != null) {
                boolean named = !clean(p.name()).isEmpty();
                if (named && d.scratch) {
                    requireRoomForNamed(user);
                }
                d.name = clean(p.name());
                d.scratch = !named;
                if (d.scratch) {
                    evictScratch(user, d.id, props.maxScratch() - 1);
                }
            }
            if (p.kind() != null && !p.kind().isBlank()) {
                d.kind = p.kind().trim();
            }
            if (p.notes() != null) {
                d.notes = p.notes();
            }
            if (p.sutra() != null && !p.sutra().equals(d.sutra)) {
                record(d, TEXT_OP, d.sutra, p.sutra());
            }
            if (p.tests() != null) {
                d.tests = new ArrayList<>(p.tests());
            }
            d.updated = clock.getAsLong();
            store.save(d);
            return d;
        });
    }

    // ---- operations: the log, undo and redo ------------------------------------------------------------------------

    /** What applying operations to a Sutra made: its new text, and the operations as they are kept in the log. */
    public record Applied(String yaml, JsonNode ops) {}

    /**
     * Applies operations to the Design's Sutra and appends them to its log (the redo tail is dropped). {@code baseRev} must be
     * the revision the caller built on, else {@code 409 DRS-5007}. A step that changes nothing is not logged.
     */
    public StoredDesign applyOps(String user, String id, int baseRev, java.util.function.Function<String, Applied> apply) {
        return locked(user, () -> {
            StoredDesign d = get(user, id);
            stale(d, baseRev);
            Applied a = apply.apply(d.sutra);
            if (a.yaml() != null && !a.yaml().equals(d.sutra)) {
                requireText(user, d, a.yaml(), d.notes, d.tests);
                record(d, a.ops(), d.sutra, a.yaml());
                d.updated = clock.getAsLong();
                store.save(d);
            }
            return d;
        });
    }

    /**
     * The Design moves to a newer base: its Sutra becomes {@code yaml} (the base text with the replayed operations {@code ops} on it).
     * The log starts again from the new base, so a later rebase replays only what was replayed now; the old steps are not undoable.
     */
    public StoredDesign rebase(String user, String id, int baseRev, String newBase, String baseText, String yaml, JsonNode ops) {
        return locked(user, () -> {
            StoredDesign d = get(user, id);
            stale(d, baseRev);
            requireText(user, d, yaml, d.notes, d.tests);
            d.ops = new ArrayList<>();
            if (!yaml.equals(baseText) && ops.size() > 0) {
                ObjectNode e = JSON.createObjectNode();
                e.set("ops", ops);
                e.put("before", baseText).put("after", yaml).put("at", clock.getAsLong());
                d.ops.add(e);
            }
            d.opsAt = d.ops.size();
            d.base = newBase;
            d.sutra = yaml;
            d.rev++;
            d.status = "draft";
            d.updated = clock.getAsLong();
            store.save(d);
            return d;
        });
    }

    /** Takes the last applied step back: the Sutra is as it was before it. {@code 409 DRS-5007} when nothing can be undone or the revision is stale. */
    public StoredDesign undo(String user, String id, int baseRev) {
        return step(user, id, baseRev, -1);
    }

    /** Brings back the step undo took: {@code 409 DRS-5007} when there is none or the revision is stale. */
    public StoredDesign redo(String user, String id, int baseRev) {
        return step(user, id, baseRev, 1);
    }

    private StoredDesign step(String user, String id, int baseRev, int direction) {
        return locked(user, () -> {
            StoredDesign d = get(user, id);
            stale(d, baseRev);
            if (direction < 0 ? d.opsAt <= 0 : d.opsAt >= d.ops.size()) {
                throw new DrishtiException(ErrorCode.STALE_REVISION, "nothing to " + (direction < 0 ? "undo" : "redo"));
            }
            JsonNode entry = d.ops.get(direction < 0 ? d.opsAt - 1 : d.opsAt);
            if (ABOUT_PART.equals(entry.path("part").asText())) {
                d.about = entry.path(direction < 0 ? "aboutBefore" : "aboutAfter").asText("");
            } else {
                d.sutra = entry.path(direction < 0 ? "before" : "after").asText("");
            }
            d.opsAt += direction;
            d.rev++;
            d.status = "draft";
            d.updated = clock.getAsLong();
            store.save(d);
            return d;
        });
    }

    /** Sets the lifecycle status ({@code proposed(P-1)}, {@code live(v3)}, ...) without touching the Sutra or the revision; false when the design is gone or not at {@code onlyAtRev}. */
    public boolean markStatus(String user, String id, String status, Integer onlyAtRev) {
        return locked(user, () -> {
            java.util.Optional<StoredDesign> found = store.get(user, id).filter(x -> user.equals(x.owner));
            if (found.isEmpty()) {
                return false;
            }
            StoredDesign d = found.get();
            if (onlyAtRev != null && d.rev != onlyAtRev) {
                return false;
            }
            d.status = status;
            store.save(d);
            return true;
        });
    }

    /** Sets or clears (null) the share token hash. */
    public StoredDesign setShare(String user, String id, String hash) {
        return locked(user, () -> {
            StoredDesign d = get(user, id);
            d.shareHash = hash;
            store.save(d);
            return d;
        });
    }

    /** Binds (file and the hash of its current text) or unbinds (null file) the Design. */
    public StoredDesign setBinding(String user, String id, String file, String syncHash) {
        return locked(user, () -> {
            StoredDesign d = get(user, id);
            d.boundFile = file;
            d.boundSync = syncHash;
            store.save(d);
            return d;
        });
    }

    /** The file text read from disk becomes the Sutra (a text step, so undo brings the old one back); {@code syncHash} is remembered. */
    public StoredDesign adoptFileText(String user, String id, String text, String syncHash) {
        return locked(user, () -> {
            StoredDesign d = get(user, id);
            if (!text.equals(d.sutra)) {
                requireText(user, d, text, d.notes, d.tests);
                record(d, TEXT_OP, d.sutra, text);
                d.updated = clock.getAsLong();
            }
            d.boundSync = syncHash;
            store.save(d);
            return d;
        });
    }

    /** Records the outcome of a check of revision {@code rev}: {@code checked} when it was green and the Sutra has not moved on, else {@code draft}. */
    public StoredDesign markChecked(String user, String id, int rev, boolean green) {
        return locked(user, () -> {
            StoredDesign d = get(user, id);
            if (d.rev == rev && ("draft".equals(d.status) || "checked".equals(d.status))) {
                String next = green ? "checked" : "draft";
                if (!next.equals(d.status)) {
                    d.status = next;
                    store.save(d);
                }
            }
            return d;
        });
    }

    /** The marker of a log step that changed the About text, not the Sutra (its {@code before} and {@code after} are the unchanged Sutra). */
    public static final String ABOUT_PART = "about";

    /**
     * Replaces the Design's About text (the pack's about.yaml) as a step of the log, so undo, redo and revisions cover it like a Sutra
     * edit; a text that changes nothing is not a step. {@code baseRev} must be the revision the caller built on ({@code 409 DRS-5007}).
     * The text counts in the quotas like the notes, with their cap ({@code max-notes-kb}).
     */
    public StoredDesign setAbout(String user, String id, int baseRev, String text) {
        return locked(user, () -> {
            StoredDesign d = get(user, id);
            stale(d, baseRev);
            String next = text == null ? "" : text;
            if (next.equals(d.about)) {
                return d;
            }
            requireAbout(user, d, next);
            ObjectNode e = JSON.createObjectNode();
            e.set("ops", JSON.createArrayNode().add(JSON.createObjectNode().put("op", ABOUT_PART)));
            e.put("part", ABOUT_PART).put("before", d.sutra).put("after", d.sutra).put("aboutBefore", d.about).put("aboutAfter", next)
                    .put("at", clock.getAsLong());
            d.ops = new ArrayList<>(d.ops.subList(0, Math.min(d.opsAt, d.ops.size())));
            d.ops.add(e);
            while (d.ops.size() > props.maxOps()) {
                d.ops.remove(0);
            }
            d.opsAt = d.ops.size();
            d.about = next;
            d.rev++;
            d.status = "draft";
            d.updated = clock.getAsLong();
            store.save(d);
            return d;
        });
    }

    /** Sets the About text without a step (a design just made by an import). */
    public StoredDesign adoptAbout(String user, String id, String text) {
        return locked(user, () -> {
            StoredDesign d = get(user, id);
            String next = text == null ? "" : text;
            requireAbout(user, d, next);
            d.about = next;
            d.updated = clock.getAsLong();
            store.save(d);
            return d;
        });
    }

    private void requireAbout(String user, StoredDesign current, String about) {
        long bytes = StoredDesign.aboutBytes(about);
        long was = StoredDesign.aboutBytes(current.about);
        if (bytes > props.maxNotesKb() * 1024L && bytes > was) {
            throw tooMany("the About text is over " + props.maxNotesKb() + " KB (drishti.builder.designs.max-notes-kb)");
        }
        if (bytes <= was) {
            return;
        }
        if (current.sampleBytes() + current.textBytes() - was + bytes > props.maxBytes()) {
            throw tooMany("the design would hold over " + props.maxMb() + " MB with its About text (drishti.builder.designs.max-mb)");
        }
        if (userBytes(user) - was + bytes > props.maxUserBytes()) {
            throw tooMany("your designs would hold over " + props.maxUserMb() + " MB with the About text (drishti.builder.designs.max-user-mb)");
        }
    }

    private static void stale(StoredDesign d, int baseRev) {
        if (baseRev != d.rev) {
            throw new DrishtiException(ErrorCode.STALE_REVISION,
                    "this design is at revision " + d.rev + ", not " + baseRev + ": reload it, then edit again");
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    /** The log entry for a change made outside the operations (a text edit, an auto-design). */
    private static final JsonNode TEXT_OP = JSON.createArrayNode().add(JSON.createObjectNode().put("op", "text"));

    /** A new Sutra text as the next log step; the log keeps at most {@code max-ops} steps. */
    private void record(StoredDesign d, JsonNode ops, String before, String after) {
        ObjectNode e = JSON.createObjectNode();
        e.set("ops", ops);
        e.put("before", before).put("after", after).put("at", clock.getAsLong());
        d.ops = new ArrayList<>(d.ops.subList(0, Math.min(d.opsAt, d.ops.size())));
        d.ops.add(e);
        while (d.ops.size() > props.maxOps()) {
            d.ops.remove(0);
        }
        d.opsAt = d.ops.size();
        d.sutra = after;
        d.rev++;
        d.status = "draft";
    }

    public void delete(String user, String id) {
        locked(user, () -> {
            get(user, id);
            store.delete(user, id);
            return null;
        });
    }

    /** A named copy of a Design with its samples (the quotas apply to the copy). */
    public StoredDesign duplicate(String user, String id, String name) {
        return locked(user, () -> {
            StoredDesign from = get(user, id);
            requireRoomForNamed(user);
            if (userBytes(user) + from.totalBytes() > props.maxUserBytes()) {
                throw tooMany("a copy would take your designs over " + props.maxUserMb() + " MiB (drishti.builder.designs.max-user-mb)");
            }
            String copyName = name == null || name.isBlank() ? (from.name.isEmpty() ? "Untitled" : from.name) + " copy" : name;
            StoredDesign c = create0(user, copyName, from.kind, from.base, from.sutra, from.notes);
            c.about = from.about;
            c.tests = new ArrayList<>(from.tests);
            c.samples = new ArrayList<>(from.samples);
            for (SampleInfo s : from.samples) {
                if (!StoredDesign.REF.equals(s.type())) {
                    store.putSample(user, c.id, s.name(), store.sample(user, id, s.name()).orElse("null"));
                }
            }
            store.save(c);
            return c;
        });
    }

    /** Adds (or replaces, by name) samples, checking the quotas before anything is kept. */
    public StoredDesign addSamples(String user, String id, List<NewSample> add) {
        return locked(user, () -> {
            StoredDesign d = get(user, id);
            long design = d.totalBytes();
            long mine = userBytes(user);
            List<SampleInfo> kept = new ArrayList<>(d.samples);
            for (NewSample n : add) {
                String name = n.name();
                if (name == null || name.isBlank() || name.length() > SAMPLE_NAME_MAX || hasControl(name)) {
                    throw new DrishtiException(ErrorCode.BAD_REQUEST, "a sample's name is 1-" + SAMPLE_NAME_MAX + " characters");
                }
                long bytes = n.json() == null ? 0 : n.json().getBytes(StandardCharsets.UTF_8).length;
                SampleInfo old = kept.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
                if (old != null) {
                    kept.remove(old);
                    design -= old.bytes();
                    mine -= old.bytes();
                }
                if (kept.size() >= props.maxSamples()) {
                    throw tooMany("a design holds " + props.maxSamples() + " samples, the most allowed (drishti.builder.designs.max-samples)");
                }
                if (design + bytes > props.maxBytes()) {
                    throw tooMany("the samples would take this design over " + props.maxMb() + " MiB (drishti.builder.designs.max-mb)");
                }
                if (mine + bytes > props.maxUserBytes()) {
                    throw tooMany("your designs would hold over " + props.maxUserMb() + " MiB of samples (drishti.builder.designs.max-user-mb)");
                }
                design += bytes;
                mine += bytes;
                kept.add(new SampleInfo(name, n.type(), n.refKind(), n.refId(), bytes));
            }
            for (NewSample n : add) {
                if (n.json() != null) {
                    store.putSample(user, id, n.name(), n.json());
                } else {
                    store.removeSample(user, id, n.name());
                }
            }
            d.samples = kept;
            d.updated = clock.getAsLong();
            store.save(d);
            LOG.info("design {}: {} sample(s) added, {} in all", id, add.size(), kept.size());
            return d;
        });
    }

    public StoredDesign removeSample(String user, String id, String name) {
        return locked(user, () -> {
            StoredDesign d = get(user, id);
            if (d.samples.removeIf(s -> s.name().equals(name))) {
                store.removeSample(user, id, name);
                d.updated = clock.getAsLong();
                store.save(d);
            } else {
                throw new DrishtiException(ErrorCode.DESIGN_NOT_FOUND, "no sample '" + name + "' in this design");
            }
            return d;
        });
    }

    /** Deletes every Design past its expiry; returns how many. */
    public int sweep() {
        int n = 0;
        for (String user : store.users()) {
            for (StoredDesign d : store.list(user)) {
                if (expired(d)) {
                    n += locked(user, () -> store.delete(user, d.id) ? 1 : 0);
                }
            }
        }
        if (n > 0) {
            LOG.info("design sweep: {} expired design(s) deleted", n);
        }
        return n;
    }

    public void forget(String user) {
        locked(user, () -> {
            store.forget(user);
            return null;
        });
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private boolean expired(StoredDesign d) {
        long ttl = (d.scratch ? props.scratchTtl() : props.namedTtl()).toMillis();
        return clock.getAsLong() - d.updated >= ttl;
    }

    private long userBytes(String user) {
        long n = 0;
        for (StoredDesign d : store.list(user)) {
            n += d.totalBytes();
        }
        return n;
    }

    private static String clean(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() > NAME_MAX || hasControl(n)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a design's name is at most " + NAME_MAX + " characters, without control characters");
        }
        return n;
    }

    private static boolean hasControl(String s) {
        return s.chars().anyMatch(Character::isISOControl);
    }

    private static DrishtiException tooMany(String message) {
        return new DrishtiException(ErrorCode.PAYLOAD_TOO_LARGE, message);
    }

    /** Per-user lock; a ReentrantLock because the guarded work is file or database I/O. */
    private <T> T locked(String user, java.util.function.Supplier<T> work) {
        ReentrantLock lock = locks.computeIfAbsent(user, u -> new ReentrantLock());
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }
}
