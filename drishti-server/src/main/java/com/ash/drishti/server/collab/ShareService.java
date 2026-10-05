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
package com.ash.drishti.server.collab;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.AccessLog;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.CollabTx;
import com.ash.drishti.identity.collab.Notice;
import com.ash.drishti.identity.collab.Pin;
import com.ash.drishti.identity.collab.Recipient;
import com.ash.drishti.identity.collab.Share;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.identity.collab.Ulid;
import com.ash.drishti.server.collab.thread.ThreadService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Share with a note (COLLABORATION.md). What travels is the sender's words and a pin; data is never copied in, and what a person
 * receives is computed for that person, when they look, from what they may see now.
 *
 * <p>Sending: the sender needs the {@code collaborate} power and the right to open the kind (and a shared panel's gate kind); the
 * recipients (users and roles from the directory, within the sender's scope) are expanded and each checked with
 * {@link Entitlements#mayReach}; the share, its recipients, the inbox rows and the access-log {@code share} row are written in one
 * transaction ({@link CollabTx}), and only then pushed to open streams.
 */
public final class ShareService {

    private static final Logger LOG = LoggerFactory.getLogger(ShareService.class);

    /** The people a share is addressed to. */
    public record To(List<String> users, List<String> roles) {
        public To {
            users = users == null ? List.of() : List.copyOf(users);
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    /** Channels asked for; in-app is always on. */
    public record Channels(Boolean inApp, Boolean email) {}

    /**
     * A share to send. {@code generation} is the generation the page showed (evidence; at most what the server holds); {@code panel}
     * and {@code gateKind} name a shared panel and the kind its source names; {@code live} sends a live link instead of the date.
     */
    public record Request(String kind, String id, String panel, String gateKind, Long generation, String note, To to, Channels channels,
            Boolean live, Boolean postToThread) {}

    public record Skipped(String name, String reason) {}

    /** The answer to a send. */
    public record Result(String id, String link, Pin pin, int delivered, List<Skipped> skipped, List<String> warnings) {}

    /** A recipient as the sender or compliance sees them. */
    public record RecipientView(String name, String addressed, String state, Instant openedAt) {}

    /** A share as the caller opens it. A caller who may not open it gets {@code access: false} and nothing about the entity. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record View(String id, boolean access, String reason, String pack, String role, String sender, String senderName, Instant createdAt,
            String kind, String entityId, String panel, Pin pin, String note, List<RecipientView> recipients,
            List<ThreadService.CommentView> replies) {}

    /** One line of a box (sent or received). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Summary(String id, Instant createdAt, String sender, String senderName, String kind, String entityId, String panel, Pin pin,
            String excerpt, boolean access, Integer recipients) {}

    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);
    private final ShareStore store;
    private final CollabTx tx;
    private final CollabProperties props;
    private final Entitlements entitlements;
    private final PackAccess packs;
    private final Principals principals;
    private final DirectoryService directory;
    private final UserService users;
    private final SourceRouter router;
    private final List<Notifier> notifiers;
    private final InboxHub hub;
    private final AccessLog accessLog;
    private final RateLimits limits;
    private final ThreadService threads;
    private final List<Pattern> deny = new ArrayList<>();

    @SuppressWarnings("java:S107")
    public ShareService(ShareStore store, CollabTx tx, CollabProperties props, Entitlements entitlements, PackAccess packs,
            Principals principals, DirectoryService directory, UserService users, SourceRouter router, List<Notifier> notifiers,
            InboxHub hub, AccessLog accessLog, RateLimits limits, ThreadService threads) {
        this.store = store;
        this.tx = tx;
        this.props = props;
        this.entitlements = entitlements;
        this.packs = packs;
        this.principals = principals;
        this.directory = directory;
        this.users = users;
        this.router = router;
        this.notifiers = List.copyOf(notifiers);
        this.hub = hub;
        this.accessLog = accessLog;
        this.limits = limits;
        this.threads = threads;
        for (String p : props.text().denyPatterns()) {
            try {
                deny.add(Pattern.compile(p));
            } catch (PatternSyntaxException e) {
                throw new IllegalStateException("drishti.collab.text.deny-patterns: not a regular expression: " + p, e);
            }
        }
    }

    /** DRS-7004 unless collaboration is on. */
    public void requireOn() {
        if (!props.enabled()) {
            throw new DrishtiException(ErrorCode.SHARING_OFF, "collaboration is switched off (drishti.collab.enabled)");
        }
    }

    public void requireCollaborate(Principal p) {
        requireOn();
        if (!entitlements.mayCollaborate(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, p.user() + " may not share or comment: ask an administrator for a role with collaborate");
        }
    }

    // ---- send ----------------------------------------------------------------------------------------------------------

    public Result share(Principal sender, Request req, AsOf asOf, boolean accessLogEnabled) {
        requireCollaborate(sender);
        if (req == null || blank(req.kind()) || blank(req.id())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a share needs a kind and an id");
        }
        String kind = req.kind().trim();
        String entityId = req.id().trim();
        String gate = blank(req.gateKind()) ? null : req.gateKind().trim();
        String panel = blank(req.panel()) ? null : req.panel().trim();
        requirePackSharing(kind);
        if (req.channels() != null && Boolean.TRUE.equals(req.channels().email()) && !emailAvailable()) {
            throw new DrishtiException(ErrorCode.MAIL_UNAVAILABLE, "email is not configured on this server; send in Drishti only");
        }
        entitlements.requireOpen(sender, kind);
        if (gate != null) {
            entitlements.requireOpen(sender, gate);
        }
        String note = cleanNote(req.note());
        List<RecipientDraft> people = resolve(sender, req.to(), kind, gate);
        EntityDocument doc = fetch(kind, entityId, asOf);
        long generation = req.generation() == null ? 0 : req.generation();
        if (generation < 0 || generation > doc.provenance().generation()) {
            throw new DrishtiException(ErrorCode.TEXT_REFUSED, "bad pin: generation " + generation + " is newer than the " + doc.provenance().generation()
                    + " the server holds");
        }
        List<String> warnings = new ArrayList<>();
        List<Share.Span> spans = NoteText.spans(note, entitlements.maskedValues(doc.data()));
        if (!spans.isEmpty()) {
            if ("reject".equals(props.text().onMaskedCopy())) {
                throw new DrishtiException(ErrorCode.TEXT_REFUSED, "the note contains the value of a field that is hidden from some readers; remove it");
            }
            if ("warn".equals(props.text().onMaskedCopy())) {
                warnings.add("The note contains the value of a field hidden from some readers; people without full access will see " + com.ash.drishti.api.DataNode.MASK + ".");
            }
        }
        rateLimit(sender.user());                      // after every check that can fail: a refused share costs nothing
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        boolean live = Boolean.TRUE.equals(req.live());
        Pin pin = new Pin(live ? null : asOf.businessDate(), live || asOf.live(), asOf.knownAt() != null && !live ? asOf.knownAt() : now,
                generation, doc.provenance().source());
        boolean toThread = postsToThread(req.postToThread(), props.share().postToThread());
        String threadId = toThread ? Ulid.next("th_", now.toEpochMilli()) : null;
        Share share = new Share(Ulid.next("sh_", now.toEpochMilli()), sender.user(), now, kind, entityId, panel, gate, pin, note, spans,
                "in-app", threadId, null).signed();
        List<Recipient> rows = people.stream().map(d -> new Recipient(d.addressed(), d.user(), d.state(), null)).toList();
        List<Recipient> reached = rows.stream().filter(r -> Recipient.NOTIFIED.equals(r.state())).toList();
        List<Notice> notices = tx.run(() -> {
            store.save(share, rows);
            if (threadId != null) {
                threads.postShare(threadId, share, now);
            }
            List<Notice> written = new ArrayList<>();
            Notifier.ShareEvent event = new Notifier.ShareEvent(share, reached);
            for (Notifier n : notifiers) {
                if (n.available() && (!"email".equals(n.channel()) || wantsEmail(req))) {
                    written.addAll(n.onShare(event));
                }
            }
            if (accessLogEnabled && accessLog != null) {
                accessLog.recordNow(new AccessLog.Event(now, sender.user(), "share", kind, entityId, detail(share, reached, rows),
                        pin.businessDate() == null ? null : pin.businessDate().toString()));
            }
            return written;
        });
        notices.forEach(hub::publish);
        return new Result(share.id(), link(share.id()), pin, reached.size(), props.share().tell() ? skipped(sender, people, kind) : List.of(), warnings);
    }

    /** The request's own choice wins; when it says nothing the configured default ({@code drishti.collab.share.post-to-thread}) applies. */
    static boolean postsToThread(Boolean requested, boolean configuredDefault) {
        return requested != null ? requested : configuredDefault;
    }

    private boolean wantsEmail(Request req) {
        return req.channels() != null && Boolean.TRUE.equals(req.channels().email());
    }

    private boolean emailAvailable() {
        return props.email().enabled() && notifiers.stream().anyMatch(n -> "email".equals(n.channel()) && n.available());
    }

    private void requirePackSharing(String kind) {
        String owner = packs.ownerOf(kind);
        Object flag = owner == null ? null : props.packs().getOrDefault(owner, Map.of()).get("share-enabled");
        if (flag != null && "false".equalsIgnoreCase(String.valueOf(flag))) {
            throw new DrishtiException(ErrorCode.SHARING_OFF, "sharing is switched off for " + kind + " views (the " + owner + " pack)");
        }
    }

    private String cleanNote(String raw) {
        String note = raw == null ? "" : raw.strip();
        if (note.isEmpty()) {
            throw new DrishtiException(ErrorCode.TEXT_REFUSED, "write a note: say what you want the recipient to look at");
        }
        if (note.length() > props.share().maxText()) {
            throw new DrishtiException(ErrorCode.TEXT_REFUSED, "the note is " + note.length() + " characters; the limit is " + props.share().maxText());
        }
        if (note.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\t')) {
            throw new DrishtiException(ErrorCode.TEXT_REFUSED, "the note has control characters");
        }
        for (Pattern p : deny) {
            if (p.matcher(note).find()) {
                throw new DrishtiException(ErrorCode.TEXT_REFUSED, "the note matches a pattern that may not be shared (drishti.collab.text.deny-patterns)");
            }
        }
        return note;
    }

    private void rateLimit(String user) {
        if (store.countSentSince(user, Instant.now().minus(Duration.ofDays(1))) >= props.limits().sharesPerDay()) {
            throw new RateLimitedException("shares today", Duration.ofHours(1).toSeconds());
        }
        limits.hit("share", user, props.limits().sharesPerMinute(), Duration.ofMinutes(1), "shares");
    }

    private EntityDocument fetch(String kind, String id, AsOf asOf) {
        try {
            return router.fetch(EntityRef.of(kind, id), asOf).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof DrishtiException de) {
                throw de;
            }
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "could not read " + kind + " " + id + " to pin the share", e.getCause());
        }
    }

    private record RecipientDraft(String addressed, String user, String state, boolean visible) {}

    private List<RecipientDraft> resolve(Principal sender, To to, String kind, String gate) {
        To addr = to == null ? new To(List.of(), List.of()) : to;
        Set<String> names = new LinkedHashSet<>();
        addr.users().stream().filter(n -> !blank(n)).map(String::trim).forEach(names::add);
        Set<String> roles = new LinkedHashSet<>();
        addr.roles().stream().filter(n -> !blank(n)).map(String::trim).forEach(roles::add);
        if (names.size() + roles.size() == 0) {
            throw new DrishtiException(ErrorCode.BAD_RECIPIENTS, "name at least one person or role to share with");
        }
        if (names.size() + roles.size() > props.share().maxRecipients()) {
            throw new DrishtiException(ErrorCode.BAD_RECIPIENTS, "at most " + props.share().maxRecipients() + " names (people and roles) in one share");
        }
        Set<String> visible = new LinkedHashSet<>();
        directory.visibleUsers(sender).forEach(u -> visible.add(u.username()));
        Map<String, String> addressed = new LinkedHashMap<>();                 // person -> as typed
        for (String n : names) {
            if (n.equals(sender.user())) {
                continue;
            }
            if (!visible.contains(n)) {
                throw new DrishtiException(ErrorCode.BAD_RECIPIENTS, "unknown user '" + n + "'");
            }
            addressed.putIfAbsent(n, "user:" + n);
        }
        for (String r : roles) {
            if (!users.knownRoles().contains(r) || !props.mentionable(r)) {
                throw new DrishtiException(ErrorCode.BAD_RECIPIENTS, "'" + r + "' is not a role you can share with");
            }
            List<User> members = directory.members(r);
            if (members.size() > props.maxGroupSize()) {
                throw new DrishtiException(ErrorCode.BAD_RECIPIENTS, "role '" + r + "' has " + members.size() + " people; the limit is " + props.maxGroupSize());
            }
            members.stream().map(User::username).filter(m -> !m.equals(sender.user())).forEach(m -> addressed.putIfAbsent(m, "role:" + r));
        }
        if (addressed.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_RECIPIENTS, "no recipient other than you");
        }
        if (addressed.size() > props.share().maxExpanded()) {
            throw new DrishtiException(ErrorCode.BAD_RECIPIENTS, addressed.size() + " people after roles are expanded; the limit is " + props.share().maxExpanded());
        }
        List<RecipientDraft> out = new ArrayList<>();
        addressed.forEach((person, how) -> {
            Principal rp = principals.of(person);
            String state = Recipient.NOTIFIED;
            for (String k : gate == null ? List.of(kind) : List.of(kind, gate)) {
                String rs = entitlements.reachState(rp, k);
                if ("no-access".equals(rs)) {
                    state = Recipient.NO_ACCESS;
                    break;
                }
                if ("no-pack".equals(rs)) {
                    state = Recipient.NO_PACK;
                }
            }
            out.add(new RecipientDraft(how, person, state, visible.contains(person)));
        });
        return out;
    }

    /** Who was not notified, and why. People outside the sender's directory scope are reported as a group, never by name. */
    private List<Skipped> skipped(Principal sender, List<RecipientDraft> people, String kind) {
        List<Skipped> out = new ArrayList<>();
        Map<String, Integer> hidden = new LinkedHashMap<>();
        for (RecipientDraft d : people) {
            if (Recipient.NOTIFIED.equals(d.state())) {
                continue;
            }
            if (d.visible()) {
                out.add(new Skipped(d.user(), reason(d.state(), kind)));
            } else {
                hidden.merge(d.addressed(), 1, Integer::sum);
            }
        }
        hidden.forEach((how, n) -> out.add(new Skipped(how, "some members may not open " + kind + " views")));
        return out;
    }

    public static String reason(String state, String kind) {
        return switch (state) {
            case Recipient.NO_ACCESS -> "may not open " + kind + " views";
            case Recipient.NO_PACK -> "does not have the pack for " + kind + " views";
            case Recipient.DISABLED -> "account is disabled";
            default -> "not notified";
        };
    }

    private String link(String id) {
        String base = props.consoleUrl();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/share/" + id;
    }

    private static String detail(Share s, List<Recipient> reached, List<Recipient> all) {
        Set<String> how = new LinkedHashSet<>();
        all.forEach(r -> how.add(r.addressed()));
        List<String> shown = how.stream().limit(8).toList();
        return s.id() + " to " + reached.size() + " (" + String.join(", ", shown) + (how.size() > shown.size() ? ", …" : "") + ")"
                + (s.panelId() == null ? "" : " panel " + s.panelId());
    }

    // ---- read ----------------------------------------------------------------------------------------------------------

    /**
     * Opens a share for the caller. Not the sender, a notified recipient or a compliance officer: {@code 404 DRS-7001}, the same
     * as no such share (ids are not an oracle). A recipient who may no longer open the kind gets {@code access: false} with the sender,
     * the date and the kind, and nothing about the entity; a recipient who may opens it (this sets their first-open time).
     */
    public View open(Principal caller, String id) {
        requireOn();
        Share s = Ulid.valid(id, "sh_") ? store.find(id).orElse(null) : null;
        List<Recipient> rs = s == null ? List.of() : store.recipients(id);
        boolean sender = s != null && s.sender().equals(caller.user());
        boolean recipient = s != null && rs.stream().anyMatch(r -> r.username().equals(caller.user()) && Recipient.NOTIFIED.equals(r.state()));
        boolean compliance = s != null && entitlements.mayCompliance(caller);
        if (s == null || !(sender || recipient || compliance)) {
            throw new DrishtiException(ErrorCode.SHARE_NOT_FOUND, "no share '" + (id == null ? "" : id.length() > 40 ? id.substring(0, 40) : id) + "'");
        }
        String role = sender ? "sender" : recipient ? "recipient" : "compliance";
        String senderName = displayName(s.sender());
        String blocked = blockedReason(caller, s);
        if (blocked != null) {
            return new View(s.id(), false, blocked, packs.ownerOf(s.kind()), role, s.sender(), senderName, s.createdAt(), s.kind(), null, null, null,
                    null, null, null);
        }
        if (recipient && !sender) {
            store.markOpened(id, caller.user(), Instant.now().truncatedTo(ChronoUnit.MILLIS));
        }
        List<RecipientView> shown = sender || compliance
                ? store.recipients(id).stream().map(r -> new RecipientView(r.username(), r.addressed(), r.state(), r.openedAt())).toList() : null;
        return new View(s.id(), true, null, null, role, s.sender(), senderName, s.createdAt(), s.kind(), s.entityId(), s.panelId(), s.pin(),
                NoteText.render(s.body(), s.maskedSpans(), entitlements.masks(caller)), shown, threads.replies(caller, s));
    }

    /**
     * A reply to a share, from its sender or a recipient it reached; the other party is told ({@code reply}). Anyone else: {@code 404
     * DRS-7001}. The replier must still be able to open the shared view. The text follows the same rules as a comment.
     */
    public ThreadService.CommentView reply(Principal caller, String id, String note, AsOf asOf) {
        requireCollaborate(caller);
        Share s = Ulid.valid(id, "sh_") ? store.find(id).orElse(null) : null;
        List<Recipient> rs = s == null ? List.of() : store.recipients(id);
        boolean sender = s != null && s.sender().equals(caller.user());
        boolean recipient = s != null && rs.stream().anyMatch(r -> r.username().equals(caller.user()) && Recipient.NOTIFIED.equals(r.state()));
        if (s == null || !(sender || recipient)) {
            throw new DrishtiException(ErrorCode.SHARE_NOT_FOUND, "no share '" + (id == null ? "" : id.length() > 40 ? id.substring(0, 40) : id) + "'");
        }
        if (blockedReason(caller, s) != null) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, caller.user() + " may no longer open the shared " + s.kind() + " view");
        }
        List<String> notified = rs.stream().filter(r -> Recipient.NOTIFIED.equals(r.state())).map(Recipient::username).toList();
        return threads.replyToShare(caller, s, notified, note, asOf);
    }

    /** Why the caller may not open the shared view now: {@code no-access}, {@code pack-off}, or null when they may. */
    private String blockedReason(Principal caller, Share s) {
        for (String k : s.gateKind() == null ? List.of(s.kind()) : List.of(s.kind(), s.gateKind())) {
            if (!entitlements.mayOpen(caller, k)) {
                return switch (entitlements.reachState(caller, k)) {
                    case "pack-off" -> "pack-off";
                    case "no-pack" -> "no-pack";
                    default -> "no-access";
                };
            }
        }
        return null;
    }

    public List<Summary> box(Principal caller, String box, int limit, String before) {
        requireOn();
        int n = Math.max(1, Math.min(limit, 200));
        boolean sent = !"received".equals(box);
        List<Share> list = sent ? store.sent(caller.user(), n, before) : store.received(caller.user(), n, before);
        List<Summary> out = new ArrayList<>();
        for (Share s : list) {
            boolean access = blockedReason(caller, s) == null;
            out.add(new Summary(s.id(), s.createdAt(), s.sender(), displayName(s.sender()), s.kind(), access ? s.entityId() : null,
                    access ? s.panelId() : null, access ? s.pin() : null,
                    access ? NoteText.excerpt(NoteText.render(s.body(), s.maskedSpans(), entitlements.masks(caller)), 140) : null, access,
                    sent ? (int) store.recipients(s.id()).stream().filter(r -> Recipient.NOTIFIED.equals(r.state())).count() : null));
        }
        return out;
    }

    /** A view was opened through the share's link: sets the recipient's first-open time (nothing for anyone else). */
    public void opened(String shareId, String user) {
        try {
            if (Ulid.valid(shareId, "sh_")) {
                store.markOpened(shareId, user, Instant.now().truncatedTo(ChronoUnit.MILLIS));
            }
        } catch (RuntimeException e) {
            LOG.debug("could not record the open of {}: {}", shareId, e.toString());
        }
    }

    private String displayName(String user) {
        return principals.user(user).map(User::displayName).filter(n -> n != null && !n.isBlank()).orElse(user);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
