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
package com.ash.drishti.server.ui;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.bi.BiDocs;
import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.server.security.Principal;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * The Chip Store (UI_BOARD.md section 9): chip drafts, versions and their lifecycle in the content store under {@code ui/chips/}, beside
 * the read-only certified seed chips of {@code config/ui/chips}. Every write is a compare-and-set, so two nodes taking the same step give
 * one success and one {@code DRS-5057}; a step out of order is {@code DRS-2078}; nobody approves their own chip ({@code DRS-2007}).
 */
public class ChipStore implements com.ash.drishti.rachana.ui.ChipCatalog {

    /** The states of a chip version (9.1). */
    public static final String DRAFT = "draft", IN_REVIEW = "in-review", APPROVED = "approved", PUBLISHED = "published",
            CERTIFIED = "certified", DEPRECATED = "deprecated", WITHDRAWN = "withdrawn";

    /** A version's lifecycle state ({@code versions/<n>.state.json}). */
    public record State(String chip, int version, String state, String author, String submittedAt, String reviewer, String reviewedAt,
            String publishedBy, String publishedAt, String certifier, String certifiedAt, String comment, String sha256, boolean seed) {

        State with(String newState, Principal by, String note) {
            String now = Instant.now().toString();
            return switch (newState) {
                case APPROVED -> new State(chip, version, newState, author, submittedAt, by.user(), now, publishedBy, publishedAt, certifier, certifiedAt, note, sha256, seed);
                case PUBLISHED -> new State(chip, version, newState, author, submittedAt, reviewer, reviewedAt, by.user(), now, certifier, certifiedAt, note, sha256, seed);
                case CERTIFIED -> new State(chip, version, newState, author, submittedAt, reviewer, reviewedAt, publishedBy, publishedAt, by.user(), now, note, sha256, seed);
                default -> new State(chip, version, newState, author, submittedAt, reviewer, reviewedAt, publishedBy, publishedAt, certifier, certifiedAt, note, sha256, seed);
            };
        }

        /** Usable on any board (published or later, not withdrawn). */
        public boolean released() {
            return PUBLISHED.equals(state) || CERTIFIED.equals(state) || DEPRECATED.equals(state);
        }
    }

    /** The catalogue fields of a chip ({@code meta.json}). */
    public record Meta(String chip, String title, String category, List<String> tags, String owner, int latestPublished, int latestCertified,
            boolean deprecated) {}

    /** One version: its document text, its parsed form and its state. */
    public record Version(String text, Map<String, Object> doc, State state, String storeVersion) {}

    /** Told after a version is published, with the earlier versions it was verified compatible with (boards follow, 9.3). */
    public interface PublishListener {
        void published(String chip, int version, List<Integer> compatibleWith);
    }

    private final ContentStore store;
    private final ContentDocs docs;
    private final UiAccess access;
    private final Map<String, TreeMap<Integer, Version>> seeds = new TreeMap<>();
    private final List<PublishListener> listeners = new CopyOnWriteArrayList<>();

    public ChipStore(ContentStore store, UiAccess access, Path seedDir) {
        this.store = store;
        this.docs = new ContentDocs(store);
        this.access = access;
        loadSeeds(seedDir);
    }

    public void addListener(PublishListener l) {
        listeners.add(l);
    }

    static String base(String chip) {
        return "ui/chips/" + BiDocs.seg(chip) + "/";
    }

    static String versionKey(String chip, int n) {
        return base(chip) + "versions/" + String.format("%010d", n) + ".yaml";
    }

    static String stateKey(String chip, int n) {
        return base(chip) + "versions/" + String.format("%010d", n) + ".state.json";
    }

    private void loadSeeds(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(x -> x.getFileName().toString().endsWith(".chip.yaml")).sorted().toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                Map<String, Object> doc = UiDocs.read(text);
                String name = UiDocs.chipHeader(doc);
                int v = UiDocs.version(doc);
                State st = new State(name, v, CERTIFIED, "drishti", null, "drishti", null, "drishti", null, "drishti", null, "seed chip", UiDocs.sha256(text), true);
                seeds.computeIfAbsent(name, k -> new TreeMap<>()).put(v, new Version(text, doc, st, "seed"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The seed chips (name to versions), read-only. */
    public Map<String, TreeMap<Integer, Version>> seeds() {
        return seeds;
    }

    private Optional<Meta> meta(String chip) {
        return docs.value(base(chip) + "meta.json", Meta.class);
    }

    /** Every version of a chip in the store and the seeds (number to version), without access checks. */
    TreeMap<Integer, Version> allVersions(String chip) {
        TreeMap<Integer, Version> out = new TreeMap<>(seeds.getOrDefault(chip, new TreeMap<>()));
        for (String k : store.list(base(chip) + "versions/")) {
            if (!k.endsWith(".state.json")) {
                continue;
            }
            Optional<ContentDocs.Doc<State>> st = docs.read(k, State.class);
            Optional<ContentStore.Item> body = store.get(k.replace(".state.json", ".yaml"));
            if (st.isPresent() && body.isPresent()) {
                out.put(st.get().value().version(), new Version(body.get().text(), UiDocs.read(body.get().text()), st.get().value(), st.get().version()));
            }
        }
        return out;
    }

    /** One version without access checks, or empty. */
    public Optional<Version> find(String chip, int n) {
        Version seed = seeds.getOrDefault(chip, new TreeMap<>()).get(n);
        if (seed != null) {
            return Optional.of(seed);
        }
        Optional<ContentDocs.Doc<State>> st = docs.read(stateKey(chip, n), State.class);
        Optional<ContentStore.Item> body = store.get(versionKey(chip, n));
        if (st.isEmpty() || body.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Version(body.get().text(), UiDocs.read(body.get().text()), st.get().value(), st.get().version()));
    }

    /** The Chip Store as the grammar's board check reads it (the P1 interface): a version, its state and a newer compatible one. */
    @Override
    public Optional<Entry> find(com.ash.drishti.rachana.ui.ChipRef ref) {
        Optional<Version> v = find(ref.name(), ref.version());
        if (v.isEmpty()) {
            return Optional.empty();
        }
        com.ash.drishti.rachana.ui.ChipDoc doc;
        try {
            doc = com.ash.drishti.rachana.ui.UiDocuments.readChip(v.get().text());
        } catch (DrishtiException e) {
            return Optional.empty();
        }
        Integer newer = allVersions(ref.name()).tailMap(ref.version(), false).descendingMap().values().stream()
                .filter(x -> x.state().released() && UiDocs.compatibleWith(x.doc()).contains(ref.version()))
                .map(x -> x.state().version()).findFirst().orElse(null);
        return Optional.of(new Entry(doc, com.ash.drishti.rachana.ui.ChipState.valueOf(v.get().state().state().toUpperCase(Locale.ROOT).replace('-', '_')), newer));
    }

    /** The caller's own current draft as a catalogue (state draft), so their personal boards may use it before it is submitted. */
    public com.ash.drishti.rachana.ui.ChipCatalog draftsOf(Principal p) {
        return ref -> {
            Optional<Meta> m = meta(ref.name());
            if (m.isEmpty() || !p.user().equals(m.get().owner())) {
                return Optional.empty();
            }
            return store.get(base(ref.name()) + "draft.yaml").flatMap(it -> {
                com.ash.drishti.rachana.ui.UiDocuments.Read<com.ash.drishti.rachana.ui.ChipDoc> r = com.ash.drishti.rachana.ui.UiDocuments.parseChip(it.text());
                return r.doc() != null && r.doc().version() == ref.version()
                        ? Optional.of(new Entry(r.doc(), com.ash.drishti.rachana.ui.ChipState.DRAFT, null)) : Optional.empty();
            });
        };
    }

    /** Whether {@code p} may read this version: released ones by anyone who may view; earlier states by the author and the governors. */
    boolean mayRead(Version v, Principal p) {
        State s = v.state();
        return access.mayView(p) && (s.released() || CERTIFIED.equals(s.state()) || p.user().equals(s.author()) || access.mayReview(p)
                || access.mayCertify(p) || access.isAdmin(p));
    }

    public Version get(String chip, int n, Principal p) {
        requireView(p);
        Version v = find(chip, n).filter(x -> mayRead(x, p))
                .orElseThrow(() -> new DrishtiException(ErrorCode.UI_CHIP_NOT_FOUND, "no chip " + chip + "@" + n));
        return v;
    }

    /** The versions of a chip the caller may see, with their states. */
    public List<State> versions(String chip, Principal p) {
        requireView(p);
        List<State> out = allVersions(chip).values().stream().filter(v -> mayRead(v, p)).map(Version::state).toList();
        if (out.isEmpty()) {
            throw new DrishtiException(ErrorCode.UI_CHIP_NOT_FOUND, "no chip " + chip);
        }
        return out;
    }

    /**
     * The Chip Store listing: chips with a released version (deprecated and withdrawn ones are not offered), and the caller's own chips
     * in progress; filtered by category, tag, text (name, title, purpose) and certified.
     */
    public List<Map<String, Object>> list(String category, String tag, String q, Boolean certified, Principal p) {
        requireView(p);
        java.util.Set<String> names = new java.util.TreeSet<>(seeds.keySet());
        for (String k : store.list("ui/chips/")) {
            if (k.endsWith("/meta.json")) {
                meta(k.substring("ui/chips/".length(), k.length() - "/meta.json".length())).ifPresent(m -> names.add(m.chip()));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
        for (String name : names) {
            TreeMap<Integer, Version> all = allVersions(name);
            Version latest = all.descendingMap().values().stream().filter(v -> v.state().released() || CERTIFIED.equals(v.state().state()))
                    .filter(v -> !DEPRECATED.equals(v.state().state())).findFirst().orElse(null);
            Version cert = all.descendingMap().values().stream().filter(v -> CERTIFIED.equals(v.state().state())).findFirst().orElse(null);
            boolean mine = all.values().stream().anyMatch(v -> p.user().equals(v.state().author()) && !v.state().released())
                    || meta(name).map(m -> p.user().equals(m.owner()) && store.get(base(name) + "draft.yaml").isPresent()).orElse(false);
            Version shown = latest != null ? latest : mine ? (all.isEmpty() ? null : all.lastEntry().getValue()) : null;
            if (shown == null && !mine) {
                continue;
            }
            Map<String, Object> doc = shown != null ? shown.doc() : draftDoc(name).orElse(Map.of());
            String cat = UiDocs.str(doc.get("category"));
            List<String> tags = UiDocs.list(doc.get("tags")).stream().map(String::valueOf).toList();
            String purpose = UiDocs.str(UiDocs.map(doc.get("manual")).get("purpose"));
            if (category != null && !category.equals(cat) || tag != null && !tags.contains(tag) || Boolean.TRUE.equals(certified) && cert == null) {
                continue;
            }
            if (needle != null && !(name + " " + doc.get("title") + " " + purpose).toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chip", name);
            m.put("title", doc.get("title"));
            m.put("category", cat);
            m.put("tags", tags);
            m.put("purpose", purpose == null ? null : purpose.strip());
            m.put("latest", latest == null ? null : latest.state().version());
            m.put("certified", cert == null ? null : cert.state().version());
            m.put("seed", seeds.containsKey(name));
            m.put("mine", mine);
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("chip"))));
        return out;
    }

    private Optional<Map<String, Object>> draftDoc(String chip) {
        return store.get(base(chip) + "draft.yaml").map(i -> UiDocs.read(i.text()));
    }

    /** The current draft of a chip, for its owner (and the governors). */
    public ContentStore.Item draft(String chip, Principal p) {
        requireView(p);
        Optional<Meta> m = meta(chip);
        if (m.isEmpty() || !(p.user().equals(m.get().owner()) || access.mayReview(p) || access.isAdmin(p))) {
            throw new DrishtiException(ErrorCode.UI_CHIP_NOT_FOUND, "no draft of chip " + chip);
        }
        return store.get(base(chip) + "draft.yaml").orElseThrow(() -> new DrishtiException(ErrorCode.UI_CHIP_NOT_FOUND, "no draft of chip " + chip));
    }

    /** The store version of a chip's draft (its ETag), or null when there is none. */
    public String draftVersion(String chip) {
        return store.get(base(chip) + "draft.yaml").map(ContentStore.Item::version).orElse(null);
    }

    /**
     * Saves the draft (compare-and-set: {@code expected} null creates). The document's name must be the path's; its version must be new
     * (a published number never changes, {@code DRS-2078}); the chip belongs to whoever first drafted it.
     */
    public String saveDraft(String chip, String text, String expected, Principal p) {
        if (!access.mayAuthor(p)) {
            throw forbidden(p, "author chips");
        }
        UiDocs.name(chip, "chip");
        Map<String, Object> doc = UiDocs.read(text);
        String name = UiDocs.chipHeader(doc);
        if (!name.equals(chip)) {
            throw new DrishtiException(ErrorCode.UI_DOCUMENT_INVALID, "the document is chip '" + name + "', not '" + chip + "'");
        }
        int v = UiDocs.version(doc);
        TreeMap<Integer, Version> all = allVersions(chip);
        if (seeds.containsKey(chip) && seeds.get(chip).containsKey(v)) {
            throw new DrishtiException(ErrorCode.UI_LIFECYCLE_ORDER, chip + "@" + v + " is a seed chip; a store chip of the same name and version is refused");
        }
        if (all.containsKey(v)) {
            throw new DrishtiException(ErrorCode.UI_LIFECYCLE_ORDER, chip + "@" + v + " exists already (" + all.get(v).state().state()
                    + "); a version never changes: draft version " + (all.lastKey() + 1));
        }
        Meta m = meta(chip).orElse(null);
        if (m != null && !m.owner().equals(p.user()) && !access.isAdmin(p)) {
            throw forbidden(p, "change the draft of " + chip + " (it is " + m.owner() + "'s)");
        }
        String ver = store.put(base(chip) + "draft.yaml", text.getBytes(StandardCharsets.UTF_8), expected);
        Meta next = new Meta(chip, UiDocs.str(doc.get("title")), UiDocs.str(doc.get("category")),
                UiDocs.list(doc.get("tags")).stream().map(String::valueOf).toList(), m == null ? p.user() : m.owner(),
                m == null ? 0 : m.latestPublished(), m == null ? 0 : m.latestCertified(), m != null && m.deprecated());
        docs.update(base(chip) + "meta.json", Meta.class, old -> old == null ? next
                : new Meta(chip, next.title(), next.category(), next.tags(), old.owner(), old.latestPublished(), old.latestCertified(), old.deprecated()));
        event(chip, v, "draft", p, null);
        access.audit(p, "ui.chip.draft", chip + "@" + v, "");
        return ver;
    }

    /** A lifecycle step (9.1): submit, approve, reject, publish, certify, deprecate, withdraw. Answers the new state. */
    public State act(String chip, int n, String action, String comment, Principal p) {
        if (seeds.containsKey(chip) && seeds.get(chip).containsKey(n)) {
            throw new DrishtiException(ErrorCode.UI_LIFECYCLE_ORDER, chip + "@" + n + " is a seed chip: shipped certified and read-only");
        }
        State done = switch (action == null ? "" : action) {
            case "submit" -> submit(chip, n, p);
            case "approve" -> review(chip, n, true, comment, p);
            case "reject" -> review(chip, n, false, comment, p);
            case "publish" -> publish(chip, n, p);
            case "certify" -> step(chip, n, List.of(PUBLISHED), CERTIFIED, comment, p, access.mayCertify(p) || access.isAdmin(p), "certify chips");
            case "deprecate" -> step(chip, n, List.of(PUBLISHED, CERTIFIED), DEPRECATED, comment, p,
                    isAuthor(chip, n, p) || access.mayReview(p) || access.isAdmin(p), "deprecate this chip");
            case "withdraw" -> step(chip, n, List.of(PUBLISHED, CERTIFIED, DEPRECATED), WITHDRAWN, comment, p,
                    access.mayReview(p) || access.mayCertify(p) || access.isAdmin(p), "withdraw chips");
            default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "no chip action '" + action
                    + "': submit, approve, reject, publish, certify, deprecate or withdraw");
        };
        event(chip, n, action, p, comment);
        access.audit(p, "ui.chip." + action, chip + "@" + n, comment == null ? "" : comment);
        return done;
    }

    private boolean isAuthor(String chip, int n, Principal p) {
        return find(chip, n).map(v -> p.user().equals(v.state().author())).orElse(false);
    }

    private State submit(String chip, int n, Principal p) {
        ContentStore.Item d = store.get(base(chip) + "draft.yaml")
                .orElseThrow(() -> new DrishtiException(ErrorCode.UI_LIFECYCLE_ORDER, "chip " + chip + " has no draft to submit"));
        Meta m = meta(chip).orElseThrow(() -> new DrishtiException(ErrorCode.UI_LIFECYCLE_ORDER, "chip " + chip + " has no draft to submit"));
        if (!m.owner().equals(p.user()) && !access.isAdmin(p)) {
            throw forbidden(p, "submit " + chip + " (it is " + m.owner() + "'s)");
        }
        Map<String, Object> doc = UiDocs.read(d.text());
        if (UiDocs.version(doc) != n) {
            throw new DrishtiException(ErrorCode.UI_LIFECYCLE_ORDER, "the draft of " + chip + " is version " + UiDocs.version(doc) + ", not " + n);
        }
        publishCheck(chip, n, d.text());
        if (find(chip, n).isPresent()) {
            throw new DrishtiException(ErrorCode.UI_LIFECYCLE_ORDER, chip + "@" + n + " was submitted already");
        }
        State st = new State(chip, n, IN_REVIEW, m.owner(), Instant.now().toString(), null, null, null, null, null, null, null, UiDocs.sha256(d.text()), false);
        store.put(versionKey(chip, n), d.text().getBytes(StandardCharsets.UTF_8), null);
        try {
            store.put(stateKey(chip, n), ContentDocs.encode(st), null);
        } catch (DrishtiException e) {
            store.deleteLatest(versionKey(chip, n));
            throw e;
        }
        return st;
    }

    /** The publish gate (CP-01 to CP-05 with the board check over the manual's examples, against this store); errors are {@code DRS-2071}. */
    public List<com.ash.drishti.rachana.ui.UiProblem> publishCheck(String chip, int n, String text) {
        List<com.ash.drishti.rachana.ui.UiProblem> problems = com.ash.drishti.rachana.ui.ChipPublishCheck.check(text, this);
        List<String> errors = problems.stream().filter(com.ash.drishti.rachana.ui.UiProblem::error).map(Object::toString).toList();
        if (!errors.isEmpty()) {
            throw new DrishtiException(ErrorCode.UI_CHECK_FAILED, chip + "@" + n + " fails the chip publish check: " + String.join("; ", errors));
        }
        return problems;
    }

    private State review(String chip, int n, boolean approve, String comment, Principal p) {
        Version v = find(chip, n).orElseThrow(() -> new DrishtiException(ErrorCode.UI_CHIP_NOT_FOUND, "no chip " + chip + "@" + n));
        if (!access.mayReview(p)) {
            throw forbidden(p, "review chips (a reviewer, the Sutra approver role, reviews them)");
        }
        if (!IN_REVIEW.equals(v.state().state())) {
            throw outOfOrder(chip, n, v.state().state(), approve ? "approve" : "reject");
        }
        if (p.user().equals(v.state().author())) {
            throw new DrishtiException(ErrorCode.FOUR_EYES, "a reviewer may not " + (approve ? "approve" : "reject") + " their own chip (" + chip + "@" + n + ")");
        }
        if (approve) {
            State next = v.state().with(APPROVED, p, comment);
            store.put(stateKey(chip, n), ContentDocs.encode(next), v.storeVersion());
            return next;
        }
        if (comment == null || comment.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "rejecting needs a comment for the author");
        }
        store.delete(stateKey(chip, n), v.storeVersion());          // back to draft: the version number is free again
        store.deleteLatest(versionKey(chip, n));
        return new State(chip, n, DRAFT, v.state().author(), v.state().submittedAt(), p.user(), Instant.now().toString(), null, null, null, null,
                comment, v.state().sha256(), false);
    }

    private State publish(String chip, int n, Principal p) {
        Version v = find(chip, n).orElseThrow(() -> new DrishtiException(ErrorCode.UI_CHIP_NOT_FOUND, "no chip " + chip + "@" + n));
        if (!(p.user().equals(v.state().author()) || access.mayReview(p) || access.isAdmin(p))) {
            throw forbidden(p, "publish " + chip + "@" + n);
        }
        if (!APPROVED.equals(v.state().state())) {
            throw outOfOrder(chip, n, v.state().state(), "publish");
        }
        if (!UiDocs.sha256(v.text()).equals(v.state().sha256())) {
            throw new DrishtiException(ErrorCode.UI_LIFECYCLE_ORDER, chip + "@" + n + " changed after it was submitted; submit it again");
        }
        publishCheck(chip, n, v.text());
        List<Integer> compat = UiDocs.compatibleWith(v.doc());
        for (int c : compat) {
            Version prev = find(chip, c).filter(x -> x.state().released())
                    .orElseThrow(() -> new DrishtiException(ErrorCode.UI_LIFECYCLE_ORDER, "compatible-with " + c + ": " + chip + "@" + c + " is not a published version"));
            List<String> why = UiDocs.incompatibilities(prev.doc(), v.doc());
            if (!why.isEmpty()) {
                throw new DrishtiException(ErrorCode.UI_LIFECYCLE_ORDER, chip + "@" + n + " is not compatible with version " + c + ": " + String.join("; ", why));
            }
        }
        State next = v.state().with(PUBLISHED, p, null);
        store.put(stateKey(chip, n), ContentDocs.encode(next), v.storeVersion());
        docs.update(base(chip) + "meta.json", Meta.class, m -> m == null ? null
                : new Meta(m.chip(), m.title(), m.category(), m.tags(), m.owner(), Math.max(m.latestPublished(), n), m.latestCertified(), false));
        for (PublishListener l : listeners) {
            l.published(chip, n, compat);
        }
        return next;
    }

    private State step(String chip, int n, List<String> from, String to, String comment, Principal p, boolean allowed, String what) {
        Version v = find(chip, n).orElseThrow(() -> new DrishtiException(ErrorCode.UI_CHIP_NOT_FOUND, "no chip " + chip + "@" + n));
        if (!allowed) {
            throw forbidden(p, what);
        }
        if (!from.contains(v.state().state())) {
            throw outOfOrder(chip, n, v.state().state(), to);
        }
        if (WITHDRAWN.equals(to) && (comment == null || comment.isBlank())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "withdrawing needs a comment naming the fault and the replacement");
        }
        State next = v.state().with(to, p, comment);
        store.put(stateKey(chip, n), ContentDocs.encode(next), v.storeVersion());
        if (CERTIFIED.equals(to)) {
            docs.update(base(chip) + "meta.json", Meta.class, m -> m == null ? null
                    : new Meta(m.chip(), m.title(), m.category(), m.tags(), m.owner(), m.latestPublished(), Math.max(m.latestCertified(), n), m.deprecated()));
        }
        return next;
    }

    private void event(String chip, int n, String action, Principal p, String comment) {
        long seq = ContentDocs.next(store, base(chip) + "events.seq");
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("chip", chip);
        e.put("version", n);
        e.put("action", action);
        e.put("by", p.user());
        e.put("at", Instant.now().toString());
        e.put("comment", comment);
        docs.create(base(chip) + "events/" + String.format("%010d", seq) + ".json", e);
    }

    /** The lifecycle events of a chip, oldest first. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> events(String chip, Principal p) {
        versions(chip, p);
        return store.list(base(chip) + "events/").stream().map(k -> (Map<String, Object>) docs.value(k, Map.class).orElse(Map.of())).toList();
    }

    private void requireView(Principal p) {
        if (!access.mayView(p)) {
            throw forbidden(p, "use the Chip Store");
        }
    }

    static DrishtiException forbidden(Principal p, String what) {
        return new DrishtiException(ErrorCode.UI_FORBIDDEN, p.user() + " may not " + what);
    }

    private static DrishtiException outOfOrder(String chip, int n, String state, String step) {
        return new DrishtiException(ErrorCode.UI_LIFECYCLE_ORDER, chip + "@" + n + " is " + state + "; cannot " + step + " it now (draft, submit, approve, publish, certify)");
    }
}
