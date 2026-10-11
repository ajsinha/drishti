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
package com.ash.drishti.identity.bi;

import com.ash.drishti.identity.db.BiEntities;
import com.ash.drishti.identity.db.IdentityRepositories;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Where business-intelligence content is kept (docs/architecture/RUPAKA.md section 21, decision 1): workspaces, members, items with
 * their draft body, published versions, per-user marks (favourite, last opened) and view counts, all in the identity database.
 * The store only keeps and returns rows; who may do what is decided by the server's {@code BiService}. Writes are serialised by one
 * lock that is never held across anything but the database call.
 */
public final class BiStore {

    /** A workspace: personal (one per user, private) or shared. */
    public record Workspace(String id, String name, String description, boolean personal, String owner, boolean reviewRequired,
            Instant createdAt, String createdBy) {}

    /** A person's role in a shared workspace: viewer, contributor, member or admin. */
    public record Member(String workspace, String username, String role, Instant addedAt, String addedBy) {}

    /** An item with its draft body, lifecycle ({@code draft}, {@code proposed}, {@code published}) and catalogue fields. */
    public record Item(String id, String workspace, String kind, String title, String description, String owner, List<String> tags, String body,
            String status, int publishedVersion, String endorsement, String endorsedBy, List<String> audienceRoles, List<String> audienceUsers,
            List<String> audiencePacks, String proposedBy, Instant proposedAt, Instant createdAt, Instant updatedAt, String updatedBy,
            Instant publishedAt, String publishedBy) {

        public Item {
            tags = tags == null ? List.of() : List.copyOf(tags);
            audienceRoles = audienceRoles == null ? List.of() : List.copyOf(audienceRoles);
            audienceUsers = audienceUsers == null ? List.of() : List.copyOf(audienceUsers);
            audiencePacks = audiencePacks == null ? List.of() : List.copyOf(audiencePacks);
            proposedBy = proposedBy == null ? "" : proposedBy;
            publishedBy = publishedBy == null ? "" : publishedBy;
            endorsedBy = endorsedBy == null ? "" : endorsedBy;
        }

        public boolean isPublished() {
            return publishedVersion > 0;
        }
    }

    /** One published version of an item. */
    public record Version(String item, int version, String body, String note, String createdBy, Instant createdAt) {}

    /** One saved state of an item's body: every save, restore, publish and import is one; {@code publishedVersion} is set when it is the published text. */
    public record Revision(String item, int seq, String body, String message, String author, Instant createdAt, String origin, int publishedVersion) {}

    /** A governance event: {@code request}, {@code withdraw}, {@code approve}, {@code reject}, {@code certify}, {@code uncertify}, {@code revoke}, {@code import}. */
    public record Event(String item, int seq, String action, String actor, String note, int revision, Instant createdAt) {}

    /** What a user did with an item. */
    public record Mark(boolean favourite, Instant lastOpened, int opens) {}

    private final IdentityRepositories.BiWorkspaces workspaces;
    private final IdentityRepositories.BiMembers members;
    private final IdentityRepositories.BiItems items;
    private final IdentityRepositories.BiVersions versions;
    private final IdentityRepositories.BiRevisions revisions;
    private final IdentityRepositories.BiEvents events;
    private final IdentityRepositories.BiUserItems marks;
    private final IdentityRepositories.BiUsages usage;
    private final IdentityRepositories.BiSettings settings;
    private final TransactionTemplate tx;
    private final ReentrantLock writes = new ReentrantLock();

    public BiStore(IdentityRepositories.BiWorkspaces workspaces, IdentityRepositories.BiMembers members, IdentityRepositories.BiItems items,
            IdentityRepositories.BiVersions versions, IdentityRepositories.BiRevisions revisions, IdentityRepositories.BiEvents events,
            IdentityRepositories.BiUserItems marks, IdentityRepositories.BiUsages usage, IdentityRepositories.BiSettings settings, TransactionTemplate tx) {
        this.revisions = revisions;
        this.events = events;
        this.workspaces = workspaces;
        this.members = members;
        this.items = items;
        this.versions = versions;
        this.marks = marks;
        this.usage = usage;
        this.settings = settings;
        this.tx = tx;
    }

    // -- workspaces -------------------------------------------------------------------------------------------------
    public Optional<Workspace> workspace(String id) {
        return Optional.ofNullable(tx.execute(s -> workspaces.findById(id).map(BiStore::of).orElse(null)));
    }

    public List<Workspace> workspaces() {
        return tx.execute(s -> workspaces.findAll().stream().map(BiStore::of).sorted(Comparator.comparing(Workspace::name, String.CASE_INSENSITIVE_ORDER)).toList());
    }

    public Workspace saveWorkspace(Workspace w) {
        return locked(() -> tx.execute(s -> {
            BiEntities.BiWorkspace e = workspaces.findById(w.id()).orElseGet(BiEntities.BiWorkspace::new);
            e.id = w.id();
            e.name = w.name();
            e.description = w.description() == null ? "" : w.description();
            e.personal = w.personal();
            e.owner = w.owner() == null ? "" : w.owner();
            e.reviewRequired = w.reviewRequired();
            e.createdAt = w.createdAt();
            e.createdBy = w.createdBy() == null ? "" : w.createdBy();
            return of(workspaces.save(e));
        }));
    }

    /** Removes the workspace with its members, items and their versions. */
    public void deleteWorkspace(String id) {
        locked(() -> tx.execute(s -> {
            for (BiEntities.BiItem i : items.findByWorkspace(id)) {
                versions.deleteByItemId(i.id);
                revisions.deleteByItemId(i.id);
                events.deleteByItemId(i.id);
                items.delete(i);
            }
            members.deleteByWorkspace(id);
            workspaces.deleteById(id);
            return null;
        }));
    }

    // -- members ----------------------------------------------------------------------------------------------------
    public List<Member> members(String workspace) {
        return tx.execute(s -> members.findByWorkspace(workspace).stream().map(BiStore::of).sorted(Comparator.comparing(Member::username)).toList());
    }

    public List<Member> membershipsOf(String username) {
        return tx.execute(s -> members.findByUsername(username).stream().map(BiStore::of).toList());
    }

    public Member setMember(Member m) {
        return locked(() -> tx.execute(s -> {
            BiEntities.BiMember e = members.findById(m.workspace() + "/" + m.username()).orElseGet(BiEntities.BiMember::new);
            e.id = m.workspace() + "/" + m.username();
            e.workspace = m.workspace();
            e.username = m.username();
            e.role = m.role();
            e.addedAt = m.addedAt();
            e.addedBy = m.addedBy() == null ? "" : m.addedBy();
            return of(members.save(e));
        }));
    }

    public boolean removeMember(String workspace, String username) {
        return Boolean.TRUE.equals(locked(() -> tx.execute(s -> {
            if (!members.existsById(workspace + "/" + username)) {
                return false;
            }
            members.deleteById(workspace + "/" + username);
            return true;
        })));
    }

    // -- items ------------------------------------------------------------------------------------------------------
    public Optional<Item> item(String id) {
        return Optional.ofNullable(tx.execute(s -> items.findById(id).map(BiStore::of).orElse(null)));
    }

    public List<Item> items() {
        return tx.execute(s -> items.findAll().stream().map(BiStore::of).toList());
    }

    public List<Item> itemsIn(String workspace) {
        return tx.execute(s -> items.findByWorkspace(workspace).stream().map(BiStore::of).toList());
    }

    public List<Item> published() {
        return tx.execute(s -> items.findByPublishedVersionGreaterThan(0).stream().map(BiStore::of).toList());
    }

    public Item saveItem(Item i) {
        return locked(() -> tx.execute(s -> {
            BiEntities.BiItem e = items.findById(i.id()).orElseGet(BiEntities.BiItem::new);
            e.id = i.id();
            e.workspace = i.workspace();
            e.kind = i.kind();
            e.title = i.title();
            e.description = i.description() == null ? "" : i.description();
            e.owner = i.owner();
            e.tags = String.join("\n", i.tags());
            e.body = i.body() == null ? "" : i.body();
            e.status = i.status();
            e.publishedVersion = i.publishedVersion();
            e.endorsement = i.endorsement();
            e.endorsedBy = i.endorsedBy();
            e.audienceRoles = String.join("\n", i.audienceRoles());
            e.audienceUsers = String.join("\n", i.audienceUsers());
            e.audiencePacks = String.join("\n", i.audiencePacks());
            e.proposedBy = i.proposedBy();
            e.proposedAt = i.proposedAt();
            e.createdAt = i.createdAt();
            e.updatedAt = i.updatedAt();
            e.updatedBy = i.updatedBy() == null ? "" : i.updatedBy();
            e.publishedAt = i.publishedAt();
            e.publishedBy = i.publishedBy();
            return of(items.save(e));
        }));
    }

    public boolean deleteItem(String id) {
        return Boolean.TRUE.equals(locked(() -> tx.execute(s -> {
            if (!items.existsById(id)) {
                return false;
            }
            versions.deleteByItemId(id);
            revisions.deleteByItemId(id);
            events.deleteByItemId(id);
            items.deleteById(id);
            return true;
        })));
    }

    // -- versions ---------------------------------------------------------------------------------------------------
    public List<Version> versions(String item) {
        return tx.execute(s -> versions.findByItemIdOrderByVersionDesc(item).stream().map(BiStore::of).toList());
    }

    public Optional<Version> version(String item, int n) {
        return Optional.ofNullable(tx.execute(s -> versions.findById(item + "/" + n).map(BiStore::of).orElse(null)));
    }

    /** Adds a published version and drops the oldest beyond {@code keep}. */
    public void addVersion(Version v, int keep) {
        locked(() -> tx.execute(s -> {
            BiEntities.BiVersion e = new BiEntities.BiVersion();
            e.id = v.item() + "/" + v.version();
            e.itemId = v.item();
            e.version = v.version();
            e.body = v.body();
            e.note = v.note() == null ? "" : v.note();
            e.createdBy = v.createdBy();
            e.createdAt = v.createdAt();
            versions.save(e);
            List<BiEntities.BiVersion> all = versions.findByItemIdOrderByVersionDesc(v.item());
            for (int k = Math.max(1, keep); k < all.size(); k++) {
                versions.delete(all.get(k));
            }
            return null;
        }));
    }

    // -- revisions: every save ----------------------------------------------------------------------------------------
    /** Newest first. */
    public List<Revision> revisions(String item) {
        return tx.execute(s -> revisions.findByItemIdOrderBySeqDesc(item).stream().map(BiStore::of).toList());
    }

    public Optional<Revision> revision(String item, int seq) {
        return Optional.ofNullable(tx.execute(s -> revisions.findById(item + "/" + seq).map(BiStore::of).orElse(null)));
    }

    /** Adds the next revision of the item and drops the oldest beyond {@code keep}; a revision that carries a published version is never dropped. */
    public Revision addRevision(String item, String body, String message, String author, Instant at, String origin, int publishedVersion, int keep) {
        return locked(() -> tx.execute(s -> {
            List<BiEntities.BiRevision> all = revisions.findByItemIdOrderBySeqDesc(item);
            int seq = all.isEmpty() ? 1 : all.get(0).seq + 1;
            BiEntities.BiRevision e = new BiEntities.BiRevision();
            e.id = item + "/" + seq;
            e.itemId = item;
            e.seq = seq;
            e.body = body == null ? "" : body;
            e.message = message == null ? "" : message;
            e.author = author == null ? "" : author;
            e.createdAt = at;
            e.origin = origin;
            e.publishedVersion = publishedVersion;
            revisions.save(e);
            int kept = 1;
            for (BiEntities.BiRevision r : all) {
                if (++kept > Math.max(2, keep) && r.publishedVersion == 0) {
                    revisions.delete(r);
                }
            }
            return of(e);
        }));
    }

    /** Marks an existing revision as the text of a published version. */
    public void markRevisionPublished(String item, int seq, int version) {
        locked(() -> tx.execute(s -> {
            revisions.findById(item + "/" + seq).ifPresent(r -> {
                r.publishedVersion = version;
                revisions.save(r);
            });
            return null;
        }));
    }

    // -- governance events ----------------------------------------------------------------------------------------------
    /** Newest first. */
    public List<Event> events(String item) {
        return tx.execute(s -> events.findByItemIdOrderBySeqDesc(item).stream().map(BiStore::of).toList());
    }

    public Event addEvent(String item, String action, String actor, String note, int revision, Instant at) {
        return locked(() -> tx.execute(s -> {
            List<BiEntities.BiEvent> all = events.findByItemIdOrderBySeqDesc(item);
            int seq = all.isEmpty() ? 1 : all.get(0).seq + 1;
            BiEntities.BiEvent e = new BiEntities.BiEvent();
            e.id = item + "/" + seq;
            e.itemId = item;
            e.seq = seq;
            e.action = action;
            e.actor = actor == null ? "" : actor;
            e.note = note == null ? "" : note;
            e.revision = revision;
            e.createdAt = at;
            return of(events.save(e));
        }));
    }

    // -- per-user marks and usage -----------------------------------------------------------------------------------
    public Map<String, Mark> marks(String username) {
        return tx.execute(s -> marks.findByUsername(username).stream()
                .collect(Collectors.toMap(m -> m.itemId, m -> new Mark(m.favourite, m.lastOpened, m.opens))));
    }

    public void favourite(String username, String item, boolean on) {
        locked(() -> tx.execute(s -> {
            BiEntities.BiUserItem e = mark(username, item);
            e.favourite = on;
            marks.save(e);
            return null;
        }));
    }

    /** Records that the user opened the item now and counts one view for today. */
    public void opened(String username, String item, Instant now) {
        locked(() -> tx.execute(s -> {
            BiEntities.BiUserItem e = mark(username, item);
            e.lastOpened = now;
            e.opens++;
            marks.save(e);
            String day = LocalDate.ofInstant(now, java.time.ZoneOffset.UTC).toString();
            BiEntities.BiUsage u = usage.findById(item + "/" + day).orElseGet(() -> {
                BiEntities.BiUsage n = new BiEntities.BiUsage();
                n.id = item + "/" + day;
                n.itemId = item;
                n.day = day;
                return n;
            });
            u.views++;
            usage.save(u);
            return null;
        }));
    }

    /** Views per item over the last {@code days} days (UTC). */
    public Map<String, Integer> views(int days, Instant now) {
        String from = LocalDate.ofInstant(now, java.time.ZoneOffset.UTC).minusDays(Math.max(0, days - 1L)).toString();
        return tx.execute(s -> {
            Map<String, Integer> out = new HashMap<>();
            usage.findByDayGreaterThanEqual(from).forEach(u -> out.merge(u.itemId, u.views, Integer::sum));
            return out;
        });
    }

    private BiEntities.BiUserItem mark(String username, String item) {
        return marks.findById(username + "/" + item).orElseGet(() -> {
            BiEntities.BiUserItem n = new BiEntities.BiUserItem();
            n.id = username + "/" + item;
            n.username = username;
            n.itemId = item;
            return n;
        });
    }

    // -- settings ---------------------------------------------------------------------------------------------------
    public Optional<String> setting(String name) {
        return Optional.ofNullable(tx.execute(s -> settings.findById(name).map(e -> e.value).orElse(null)));
    }

    public void setSetting(String name, String value) {
        locked(() -> tx.execute(s -> {
            BiEntities.BiSetting e = settings.findById(name).orElseGet(BiEntities.BiSetting::new);
            e.name = name;
            e.value = value;
            settings.save(e);
            return null;
        }));
    }

    // -- mapping ----------------------------------------------------------------------------------------------------
    private <T> T locked(java.util.function.Supplier<T> work) {
        writes.lock();
        try {
            return work.get();
        } finally {
            writes.unlock();
        }
    }

    private static List<String> lines(String text) {
        return text == null || text.isEmpty() ? List.of() : Arrays.stream(text.split("\n")).filter(x -> !x.isBlank()).toList();
    }

    private static Workspace of(BiEntities.BiWorkspace e) {
        return new Workspace(e.id, e.name, e.description, e.personal, e.owner, e.reviewRequired, e.createdAt, e.createdBy);
    }

    private static Member of(BiEntities.BiMember e) {
        return new Member(e.workspace, e.username, e.role, e.addedAt, e.addedBy);
    }

    private static Revision of(BiEntities.BiRevision e) {
        return new Revision(e.itemId, e.seq, e.body, e.message, e.author, e.createdAt, e.origin, e.publishedVersion);
    }

    private static Event of(BiEntities.BiEvent e) {
        return new Event(e.itemId, e.seq, e.action, e.actor, e.note, e.revision, e.createdAt);
    }

    private static Version of(BiEntities.BiVersion e) {
        return new Version(e.itemId, e.version, e.body, e.note, e.createdBy, e.createdAt);
    }

    private static Item of(BiEntities.BiItem e) {
        return new Item(e.id, e.workspace, e.kind, e.title, e.description, e.owner, lines(e.tags), e.body, e.status, e.publishedVersion, e.endorsement,
                e.endorsedBy, lines(e.audienceRoles), lines(e.audienceUsers), lines(e.audiencePacks), e.proposedBy, e.proposedAt, e.createdAt,
                e.updatedAt, e.updatedBy, e.publishedAt, e.publishedBy);
    }
}
