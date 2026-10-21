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

import com.ash.drishti.identity.content.ContentStore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * BI content in the content store (docs/architecture/CONTENT_STORE.md section 9), so every node of a cluster sees the same workspaces and items:
 *
 * <pre>
 *  bi/workspaces/&lt;ws&gt;.json                       a workspace
 *  bi/members/&lt;ws&gt;/&lt;user&gt;.json                  one member of it
 *  bi/items/&lt;item&gt;.json                          an item with its draft body (compare-and-set on save)
 *  bi/history/&lt;item&gt;/versions/&lt;n&gt;.json           a published version
 *  bi/history/&lt;item&gt;/revisions/&lt;seq&gt;.json        a saved state
 *  bi/history/&lt;item&gt;/events/&lt;seq&gt;.json           a governance event
 *  bi/users/&lt;user&gt;/marks.json                    a person's favourites and last opens
 *  bi/usage/&lt;yyyy-mm-dd&gt;/&lt;node&gt;.json              the day's views counted by one node
 *  bi/settings/&lt;name&gt;.json                       a BI setting
 * </pre>
 *
 * Every segment is {@link BiDocs#seg escaped}; numbers are zero-padded so keys sort. Item documents sit apart from their history so listing
 * the items reads item documents only. An item save is a compare-and-set against the version the request read ({@code 409 DRS-5057} when
 * another node saved it meanwhile); a new sequence number (revision, event) is taken by a create that must not exist yet, so two nodes never
 * take the same one. The listings (all items, a workspace's items, the published ones, all workspaces) come from a snapshot at most
 * {@code drishti.bi.store-list-ttl} old; a single item or workspace is always read from the store.
 */
public final class ContentBiStore implements BiStore {

    static final String ROOT = "bi/";
    private final BiDocs docs;
    private final String node;

    /** The views one node counted on one day. */
    record Views(Map<String, Integer> items) {}

    /** A person's marks, by item. */
    record Marks(Map<String, Mark> items) {}

    record Setting(String value) {}

    public ContentBiStore(ContentStore store, String node, long listTtlMs) {
        this.docs = new BiDocs(store, listTtlMs);
        this.node = node == null || node.isBlank() ? "local" : node;
    }

    public ContentStore content() {
        return docs.store();
    }

    static String workspaceKey(String id) {
        return ROOT + "workspaces/" + BiDocs.seg(id) + ".json";
    }

    static String memberKey(String ws, String user) {
        return ROOT + "members/" + BiDocs.seg(ws) + "/" + BiDocs.seg(user) + ".json";
    }

    static String itemKey(String id) {
        return ROOT + "items/" + BiDocs.seg(id) + ".json";
    }

    static String history(String item) {
        return ROOT + "history/" + BiDocs.seg(item) + "/";
    }

    static String versionKey(String item, int n) {
        return history(item) + "versions/" + BiDocs.num(n) + ".json";
    }

    static String revisionKey(String item, int seq) {
        return history(item) + "revisions/" + BiDocs.num(seq) + ".json";
    }

    static String eventKey(String item, int seq) {
        return history(item) + "events/" + BiDocs.num(seq) + ".json";
    }

    static String marksKey(String user) {
        return ROOT + "users/" + BiDocs.seg(user) + "/marks.json";
    }

    static String settingKey(String name) {
        return ROOT + "settings/" + BiDocs.seg(name) + ".json";
    }

    // -- workspaces -------------------------------------------------------------------------------------------------
    @Override
    public Optional<Workspace> workspace(String id) {
        return docs.get(workspaceKey(id), Workspace.class).map(BiDocs.Read::value);
    }

    @Override
    public List<Workspace> workspaces() {
        return docs.listed(ROOT + "workspaces/", Workspace.class).stream().sorted(Comparator.comparing(Workspace::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    @Override
    public Workspace saveWorkspace(Workspace w) {
        Workspace n = new Workspace(w.id(), w.name(), w.description() == null ? "" : w.description(), w.personal(), w.owner() == null ? "" : w.owner(),
                w.reviewRequired(), w.createdAt(), w.createdBy() == null ? "" : w.createdBy());
        docs.save(workspaceKey(w.id()), n);
        return n;
    }

    @Override
    public void deleteWorkspace(String id) {
        for (Item i : itemsIn(id)) {
            deleteItem(i.id());
        }
        docs.deleteAll(ROOT + "members/" + BiDocs.seg(id) + "/");
        docs.delete(workspaceKey(id));
    }

    // -- members ----------------------------------------------------------------------------------------------------
    @Override
    public List<Member> members(String workspace) {
        return docs.all(ROOT + "members/" + BiDocs.seg(workspace) + "/", Member.class).stream().sorted(Comparator.comparing(Member::username)).toList();
    }

    @Override
    public List<Member> membershipsOf(String username) {
        String tail = "/" + BiDocs.seg(username) + ".json";
        List<Member> out = new ArrayList<>();
        for (String k : docs.keys(ROOT + "members/")) {
            if (k.endsWith(tail)) {
                docs.peek(k, Member.class).filter(m -> username.equals(m.username())).ifPresent(out::add);
            }
        }
        return out;
    }

    @Override
    public Member setMember(Member m) {
        Member n = new Member(m.workspace(), m.username(), m.role(), m.addedAt(), m.addedBy() == null ? "" : m.addedBy());
        docs.store().putLatest(memberKey(m.workspace(), m.username()), BiDocs.bytes(n));
        return n;
    }

    @Override
    public boolean removeMember(String workspace, String username) {
        return docs.delete(memberKey(workspace, username));
    }

    // -- items ------------------------------------------------------------------------------------------------------
    @Override
    public Optional<Item> item(String id) {
        return docs.get(itemKey(id), Item.class).map(BiDocs.Read::value);
    }

    @Override
    public List<Item> items() {
        return docs.listed(ROOT + "items/", Item.class);
    }

    @Override
    public List<Item> itemsIn(String workspace) {
        return items().stream().filter(i -> workspace.equals(i.workspace())).toList();
    }

    @Override
    public List<Item> published() {
        return items().stream().filter(i -> i.publishedVersion() > 0).toList();
    }

    @Override
    public Item saveItem(Item i) {
        Item n = new Item(i.id(), i.workspace(), i.kind(), i.title(), i.description() == null ? "" : i.description(), i.owner(), i.tags(),
                i.body() == null ? "" : i.body(), i.status(), i.publishedVersion(), i.endorsement(), i.endorsedBy(), i.audienceRoles(), i.audienceUsers(),
                i.audiencePacks(), i.proposedBy(), i.proposedAt(), i.createdAt(), i.updatedAt(), i.updatedBy() == null ? "" : i.updatedBy(), i.publishedAt(),
                i.publishedBy());
        docs.save(itemKey(i.id()), n);
        return n;
    }

    @Override
    public boolean deleteItem(String id) {
        boolean had = docs.delete(itemKey(id));
        docs.deleteAll(history(id));
        return had;
    }

    // -- versions ---------------------------------------------------------------------------------------------------
    @Override
    public List<Version> versions(String item) {
        return docs.all(history(item) + "versions/", Version.class).stream().sorted(Comparator.comparingInt(Version::version).reversed()).toList();
    }

    @Override
    public Optional<Version> version(String item, int n) {
        return docs.peek(versionKey(item, n), Version.class);
    }

    @Override
    public void addVersion(Version v, int keep) {
        docs.store().putLatest(versionKey(v.item(), v.version()), BiDocs.bytes(new Version(v.item(), v.version(), v.body(), v.note() == null ? "" : v.note(),
                v.createdBy(), v.createdAt())));
        List<String> keys = docs.keys(history(v.item()) + "versions/");
        for (int k = 0; k < keys.size() - Math.max(1, keep); k++) {            // sorted oldest first: the oldest go
            docs.store().deleteLatest(keys.get(k));
        }
    }

    @Override
    public void dropVersionsAbove(String item, int n) {
        String p = history(item) + "versions/";
        for (String k : docs.keys(p)) {
            if (Integer.parseInt(k.substring(p.length(), k.length() - 5)) > n) {
                docs.store().deleteLatest(k);
            }
        }
    }

    // -- revisions --------------------------------------------------------------------------------------------------
    @Override
    public List<Revision> revisions(String item) {
        return docs.all(history(item) + "revisions/", Revision.class).stream().sorted(Comparator.comparingInt(Revision::seq).reversed()).toList();
    }

    @Override
    public Optional<Revision> revision(String item, int seq) {
        return docs.peek(revisionKey(item, seq), Revision.class);
    }

    private int next(String prefix) {
        List<String> keys = docs.keys(prefix);
        if (keys.isEmpty()) {
            return 1;
        }
        String last = keys.get(keys.size() - 1);
        return Integer.parseInt(last.substring(prefix.length(), last.length() - 5)) + 1;
    }

    @Override
    public Revision addRevision(String item, String body, String message, String author, Instant at, String origin, int publishedVersion, int keep) {
        String p = history(item) + "revisions/";
        Revision r;
        for (int seq = next(p); ; seq++) {
            r = new Revision(item, seq, body == null ? "" : body, message == null ? "" : message, author == null ? "" : author, at, origin, publishedVersion);
            if (docs.create(revisionKey(item, seq), r)) {
                break;
            }
        }
        List<Revision> all = revisions(item);                                  // newest first
        int kept = 0;
        for (Revision old : all) {
            if (++kept > Math.max(2, keep) && old.publishedVersion() == 0) {
                docs.store().deleteLatest(revisionKey(item, old.seq()));
            }
        }
        return r;
    }

    @Override
    public void markRevisionPublished(String item, int seq, int version) {
        docs.update(revisionKey(item, seq), Revision.class, r -> r == null ? null
                : new Revision(r.item(), r.seq(), r.body(), r.message(), r.author(), r.createdAt(), r.origin(), version));
    }

    // -- governance events ------------------------------------------------------------------------------------------
    @Override
    public List<Event> events(String item) {
        return docs.all(history(item) + "events/", Event.class).stream().sorted(Comparator.comparingInt(Event::seq).reversed()).toList();
    }

    @Override
    public Event addEvent(String item, String action, String actor, String note, int revision, Instant at) {
        String p = history(item) + "events/";
        for (int seq = next(p); ; seq++) {
            Event e = new Event(item, seq, action, actor == null ? "" : actor, note == null ? "" : note, revision, at);
            if (docs.create(eventKey(item, seq), e)) {
                return e;
            }
        }
    }

    // -- per-user marks and usage -----------------------------------------------------------------------------------
    @Override
    public Map<String, Mark> marks(String username) {
        return docs.peek(marksKey(username), Marks.class).map(m -> m.items() == null ? Map.<String, Mark>of() : Map.copyOf(m.items())).orElse(Map.of());
    }

    private void mark(String username, String item, java.util.function.UnaryOperator<Mark> change) {
        docs.update(marksKey(username), Marks.class, m -> {
            Map<String, Mark> all = new LinkedHashMap<>(m == null || m.items() == null ? Map.of() : m.items());
            all.put(item, change.apply(all.getOrDefault(item, new Mark(false, null, 0))));
            return new Marks(all);
        });
    }

    @Override
    public void favourite(String username, String item, boolean on) {
        mark(username, item, m -> new Mark(on, m.lastOpened(), m.opens()));
    }

    @Override
    public void opened(String username, String item, Instant now) {
        mark(username, item, m -> new Mark(m.favourite(), now, m.opens() + 1));
        String day = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        docs.update(ROOT + "usage/" + day + "/" + BiDocs.seg(node) + ".json", Views.class, v -> {
            Map<String, Integer> all = new HashMap<>(v == null || v.items() == null ? Map.of() : v.items());
            all.merge(item, 1, Integer::sum);
            return new Views(all);
        });
    }

    @Override
    public Map<String, Integer> views(int days, Instant now) {
        String from = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(Math.max(0, days - 1L)).toString();
        Map<String, Integer> out = new HashMap<>();
        String p = ROOT + "usage/";
        for (String k : docs.keys(p)) {
            String day = k.substring(p.length(), k.indexOf('/', p.length()));
            if (day.compareTo(from) >= 0) {
                docs.peek(k, Views.class).ifPresent(v -> {
                    if (v.items() != null) {
                        v.items().forEach((item, n) -> out.merge(item, n, Integer::sum));
                    }
                });
            }
        }
        return out;
    }

    /** Puts a person's marks (the import): marks already in the store win. */
    void addMarks(String username, Map<String, Mark> marks) {
        docs.update(marksKey(username), Marks.class, m -> {
            Map<String, Mark> all = new LinkedHashMap<>(marks);
            if (m != null && m.items() != null) {
                all.putAll(m.items());
            }
            return new Marks(all);
        });
    }

    BiDocs docs() {
        return docs;
    }

    // -- settings ---------------------------------------------------------------------------------------------------
    @Override
    public Optional<String> setting(String name) {
        return docs.peek(settingKey(name), Setting.class).map(Setting::value);
    }

    @Override
    public void setSetting(String name, String value) {
        docs.store().putLatest(settingKey(name), BiDocs.bytes(new Setting(value)));
    }
}
