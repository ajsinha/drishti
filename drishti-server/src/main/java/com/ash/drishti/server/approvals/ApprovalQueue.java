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
package com.ash.drishti.server.approvals;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.identity.collab.Notice;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one Approvals queue (docs/architecture/SHARED_SCREENS.md section 2): every four-eyes item waiting for a person, of any kind, in one
 * inbox, with the items due soon pinned on top; the item opened shows what changes, its effect if approved and the rules; a person
 * approves, rejects with a reason, withdraws their own, or asks a question (a thread on the item). Each kind is an {@link ApprovalSource}
 * that keeps its own rules; this class gathers, orders, audits and keeps the questions.
 *
 * <p><b>Session isolation.</b> Nothing here is per person: every answer is computed from the principal of the call, and the only state is
 * the question threads, which are per item and shown only to someone the item's source shows the item to.
 */
public final class ApprovalQueue {

    /** The boxes of the inbox: waiting for me, sent by me, decided, everything. */
    public static final Set<String> BOXES = Set.of("inbox", "sent", "done", "all");
    /** The filters. */
    public static final Set<String> GROUPS = Set.of("data", "screens", "risk", "access");
    static final String OWNER = "_approvals";
    static final String NS = "approval-questions";
    private static final Logger LOG = LoggerFactory.getLogger(ApprovalQueue.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One entry of the list: the item, its kind's words, whether it is pinned as due soon, and how many questions it has. */
    public record Entry(ApprovalSource.Item item, String label, String group, boolean dueSoon, int questions) {}

    /** The list: the entries, the counts for the top bar, the kinds known and those that could not answer. */
    public record Listing(List<Entry> items, int inbox, int dueSoon, List<Map<String, Object>> kinds, List<String> unavailable) {}

    /** A question or an answer on an item. */
    public record Post(Instant at, String by, String text) {}

    /** An item opened, with its questions and what the person may do. */
    public record Opened(ApprovalSource.Detail detail, String label, String group, boolean dueSoon, List<Post> questions, List<String> actions) {}

    private final Map<String, ApprovalSource> sources = new LinkedHashMap<>();
    private final PreferenceStore store;
    private final AuditLog audit;
    private final ApprovalProperties props;
    private final Clock clock;
    private final Consumer<Notice> notify;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * @param notify delivers a notice to one person's inbox and open streams (a question asked on their item)
     */
    public ApprovalQueue(List<ApprovalSource> sources, PreferenceStore store, AuditLog audit, ApprovalProperties props, Clock clock, Consumer<Notice> notify) {
        for (ApprovalSource s : sources) {
            if (!s.kind().matches("[a-z][a-z0-9-]{0,31}")) {
                throw new IllegalArgumentException("an approval kind is lower-case letters, digits and dashes: " + s.kind());
            }
            if (!GROUPS.contains(s.group())) {
                throw new IllegalArgumentException("approval kind " + s.kind() + ": group is one of " + GROUPS + ", not " + s.group());
            }
            if (this.sources.putIfAbsent(s.kind(), s) != null) {
                throw new IllegalArgumentException("two approval sources of the kind " + s.kind());
            }
        }
        this.store = store;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
        this.notify = notify;
    }

    /** The kinds, in the order they were registered. */
    public List<Map<String, Object>> kinds() {
        List<Map<String, Object>> out = new ArrayList<>();
        sources.values().forEach(s -> out.add(Map.of("kind", s.kind(), "label", s.label(), "group", s.group(), "enabled", s.enabled())));
        return out;
    }

    // ---- the list ---------------------------------------------------------------------------------------------------------------

    /**
     * The person's list: {@code box} inbox (waiting for them to decide), sent (theirs), done (decided) or all; {@code group} one of the
     * filters or blank; {@code kind} one kind or blank. Due-soon items first (the nearest due date first), then the newest.
     */
    public Listing list(Principal p, String box, String group, String kind, int limit) {
        String b = box == null || box.isBlank() ? "inbox" : box;
        if (!BOXES.contains(b)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "box is one of " + new java.util.TreeSet<>(BOXES));
        }
        if (group != null && !group.isBlank() && !GROUPS.contains(group)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "group is one of " + new java.util.TreeSet<>(GROUPS));
        }
        if (kind != null && !kind.isBlank() && !sources.containsKey(kind)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "kind is one of " + sources.keySet());
        }
        List<String> unavailable = new ArrayList<>();
        List<Entry> all = gather(p, unavailable);
        int inbox = (int) all.stream().filter(e -> inInbox(e.item())).count();
        int soon = (int) all.stream().filter(e -> inInbox(e.item()) && e.dueSoon()).count();
        List<Entry> out = all.stream()
                .filter(e -> switch (b) {
                    case "inbox" -> inInbox(e.item());
                    case "sent" -> e.item().mine();
                    case "done" -> !e.item().pending();
                    default -> true;
                })
                .filter(e -> group == null || group.isBlank() || group.equals(e.group()))
                .filter(e -> kind == null || kind.isBlank() || kind.equals(e.item().kind()))
                .sorted(ORDER).limit(Math.max(1, limit)).toList();
        return new Listing(out, inbox, soon, kinds(), unavailable);
    }

    /** The counts the top bar shows: waiting for the person, and of those due soon. */
    public Map<String, Integer> count(Principal p) {
        List<Entry> all = gather(p, new ArrayList<>());
        int inbox = (int) all.stream().filter(e -> inInbox(e.item())).count();
        int soon = (int) all.stream().filter(e -> inInbox(e.item()) && e.dueSoon()).count();
        return Map.of("inbox", inbox, "dueSoon", soon);
    }

    private static boolean inInbox(ApprovalSource.Item i) {
        return i.pending() && i.mayDecide() && !i.mine();
    }

    /** Due soon first (nearest due first), then the newest. */
    static final Comparator<Entry> ORDER = Comparator.<Entry, Boolean>comparing(e -> !e.dueSoon())
            .thenComparing(e -> e.dueSoon() ? e.item().due() : LocalDate.MAX)
            .thenComparing(e -> e.item().at() == null ? Instant.EPOCH : e.item().at(), Comparator.reverseOrder());

    private List<Entry> gather(Principal p, List<String> unavailable) {
        List<Entry> all = new ArrayList<>();
        for (ApprovalSource s : sources.values()) {
            if (!s.enabled()) {
                continue;
            }
            List<ApprovalSource.Item> items;
            try {
                items = s.items(p, props.perKind());
            } catch (RuntimeException e) {
                LOG.warn("approvals: the {} items could not be read for {}: {}", s.kind(), p.user(), e.toString());
                unavailable.add(s.label() + ": " + e.getMessage());
                continue;
            }
            for (ApprovalSource.Item i : items) {
                all.add(new Entry(i, s.label(), s.group(), dueSoon(i), questionCount(i.kind(), i.id())));
            }
        }
        return all;
    }

    private boolean dueSoon(ApprovalSource.Item i) {
        return i.pending() && i.due() != null && !i.due().isAfter(LocalDate.now(clock).plusDays(props.dueSoonDays()));
    }

    // ---- one item -----------------------------------------------------------------------------------------------------------------

    private ApprovalSource source(String kind) {
        ApprovalSource s = sources.get(kind);
        if (s == null || !s.enabled()) {
            throw new DrishtiException(ErrorCode.PROPOSAL_NOT_FOUND, "no approval kind '" + kind + "' on this server (the kinds: " + sources.keySet() + ")");
        }
        return s;
    }

    /** One item as the person may see it: what changes, the effect, the rules, the questions and the actions. */
    public Opened open(String kind, String id, Principal p) {
        ApprovalSource s = source(kind);
        ApprovalSource.Detail d = s.detail(id, p);
        List<String> actions = new ArrayList<>(ApprovalSource.actions(d.item()));
        actions.add("ask");
        return new Opened(d, s.label(), s.group(), dueSoon(d.item()), questions(kind, id), actions);
    }

    /**
     * Decides an item: {@code approve}, {@code reject} (with a reason) or {@code withdraw} (the maker's own). The source applies its rules
     * (who may decide, four eyes); everything is audited.
     */
    public ApprovalSource.Item decide(String kind, String id, String action, String comment, Map<String, Object> params, Principal p) {
        if (!Set.of("approve", "reject", "withdraw").contains(action)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the action is approve, reject or withdraw, not " + action);
        }
        String why = comment == null ? null : comment.strip();
        ApprovalSource s = source(kind);                                       // a reject without a reason is refused by the kind, in its own words and code
        ApprovalSource.Item done;
        try {
            done = s.decide(id, action, why, params == null ? Map.of() : params, p);
        } catch (DrishtiException e) {
            audit.record(p.user(), "approval." + action + ".refused", kind + "/" + id, e.getMessage());
            throw e;
        }
        audit.record(p.user(), "approval." + action, kind + "/" + id, (why == null ? "" : why) + " -> " + done.status());
        return done;
    }

    // ---- questions ----------------------------------------------------------------------------------------------------------------

    /**
     * Asks a question on an item (or answers one): only someone the item's source shows it to. The maker is told when someone else asks;
     * the others who wrote on the item are told when the maker answers. The notice carries no text of the question, only where it is.
     */
    public List<Post> ask(String kind, String id, String text, Principal p) {
        ApprovalSource s = source(kind);
        ApprovalSource.Item item = s.detail(id, p).item();                       // the right to see the item is the right to ask about it
        String body = text == null ? "" : text.strip();
        if (body.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a question has some text");
        }
        if (body.length() > props.maxQuestion()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a question is at most " + props.maxQuestion() + " characters (drishti.approvals.max-question)");
        }
        String key = key(kind, id);
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        List<Post> now;
        lock.lock();
        try {
            ObjectNode doc = store.get(OWNER, NS, key).map(n -> (ObjectNode) n.deepCopy()).orElseGet(() -> {
                ObjectNode n = JSON.createObjectNode();
                n.put("kind", kind).put("id", id);
                n.putArray("posts");
                return n;
            });
            ArrayNode posts = doc.withArray("posts");
            if (posts.size() >= props.maxQuestions()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "this item has " + posts.size() + " questions and answers already (drishti.approvals.max-questions)");
            }
            posts.addObject().put("at", clock.instant().toString()).put("by", p.user()).put("text", body);
            store.put(OWNER, NS, key, doc);
            now = posts(doc);
        } finally {
            lock.unlock();
        }
        audit.record(p.user(), "approval.ask", kind + "/" + id, "");
        Set<String> tell = new LinkedHashSet<>();
        if (!p.user().equals(item.maker())) {
            tell.add(item.maker());
        } else {
            now.stream().map(Post::by).filter(u -> !u.equals(p.user())).forEach(tell::add);
        }
        String line = p.user() + (p.user().equals(item.maker()) ? " answered on " : " asked a question on ") + item.title();
        for (String u : tell) {
            if (u == null || u.isBlank()) {
                continue;
            }
            try {
                notify.accept(new Notice(0, u, clock.instant(), "notice", "approval", "/approvals/" + kind + "/" + id, clip(line), null, null, null, p.user(), null));
            } catch (RuntimeException e) {
                LOG.warn("approvals: {} could not be told of a question on {}/{}: {}", u, kind, id, e.toString());
            }
        }
        return now;
    }

    /** The questions and answers on an item, oldest first. */
    List<Post> questions(String kind, String id) {
        return store.get(OWNER, NS, key(kind, id)).map(ApprovalQueue::posts).orElse(List.of());
    }

    private int questionCount(String kind, String id) {
        return store.get(OWNER, NS, key(kind, id)).map(n -> n.path("posts").size()).orElse(0);
    }

    private static List<Post> posts(JsonNode doc) {
        List<Post> out = new ArrayList<>();
        for (JsonNode n : doc.path("posts")) {
            out.add(new Post(Instant.parse(n.path("at").asText()), n.path("by").asText(), n.path("text").asText()));
        }
        return out;
    }

    /** The store key of an item's questions: a digest, so any id is a safe key. */
    static String key(String kind, String id) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((kind + "\u0000" + id).getBytes(StandardCharsets.UTF_8));
            return kind + "-" + HexFormat.of().formatHex(h, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String clip(String s) {
        return s.length() > 200 ? s.substring(0, 199) + "…" : s;
    }
}
