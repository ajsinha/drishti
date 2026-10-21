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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where business-intelligence content is kept (docs/architecture/RUPAKA.md section 21, decision 1): workspaces, members, items with their draft
 * body, published versions, revisions, governance events, per-user marks (favourite, last opened), view counts and settings. Two kinds:
 * {@link ContentBiStore} (the content store, every node of a cluster; the default) and {@link JpaBiStore} (this node's identity database). The store
 * only keeps and returns rows; who may do what is decided by the server's {@code BiService}.
 */
public interface BiStore {

    /** A workspace: personal (one per user, private) or shared. */
    record Workspace(String id, String name, String description, boolean personal, String owner, boolean reviewRequired,
            Instant createdAt, String createdBy) {}

    /** A person's role in a shared workspace: viewer, contributor, member or admin. */
    record Member(String workspace, String username, String role, Instant addedAt, String addedBy) {}

    /** An item with its draft body, lifecycle ({@code draft}, {@code proposed}, {@code published}) and catalogue fields. */
    record Item(String id, String workspace, String kind, String title, String description, String owner, List<String> tags, String body,
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
    record Version(String item, int version, String body, String note, String createdBy, Instant createdAt) {}

    /** One saved state of an item's body: every save, restore, publish and import is one; {@code publishedVersion} is set when it is the published text. */
    record Revision(String item, int seq, String body, String message, String author, Instant createdAt, String origin, int publishedVersion) {}

    /** A governance event: {@code request}, {@code withdraw}, {@code approve}, {@code reject}, {@code certify}, {@code uncertify}, {@code revoke}, {@code import}. */
    record Event(String item, int seq, String action, String actor, String note, int revision, Instant createdAt) {}

    /** What a user did with an item. */
    record Mark(boolean favourite, Instant lastOpened, int opens) {}

    Optional<Workspace> workspace(String id);

    List<Workspace> workspaces();

    Workspace saveWorkspace(Workspace w);

    /** Removes the workspace with its members, items and their versions. */
    void deleteWorkspace(String id);

    List<Member> members(String workspace);

    List<Member> membershipsOf(String username);

    Member setMember(Member m);

    boolean removeMember(String workspace, String username);

    Optional<Item> item(String id);

    List<Item> items();

    List<Item> itemsIn(String workspace);

    List<Item> published();

    Item saveItem(Item i);

    boolean deleteItem(String id);

    List<Version> versions(String item);

    Optional<Version> version(String item, int n);

    /** Adds a published version and drops the oldest beyond {@code keep}. */
    void addVersion(Version v, int keep);

    /** Removes the published versions numbered above {@code n} (an import that is rolled back takes the versions it added with it). */
    void dropVersionsAbove(String item, int n);

    /** Newest first. */
    List<Revision> revisions(String item);

    Optional<Revision> revision(String item, int seq);

    /** Adds the next revision of the item and drops the oldest beyond {@code keep}; a revision that carries a published version is never dropped. */
    Revision addRevision(String item, String body, String message, String author, Instant at, String origin, int publishedVersion, int keep);

    /** Marks an existing revision as the text of a published version. */
    void markRevisionPublished(String item, int seq, int version);

    /** Newest first. */
    List<Event> events(String item);

    Event addEvent(String item, String action, String actor, String note, int revision, Instant at);

    Map<String, Mark> marks(String username);

    void favourite(String username, String item, boolean on);

    /** Records that the user opened the item now and counts one view for today. */
    void opened(String username, String item, Instant now);

    /** Views per item over the last {@code days} days (UTC). */
    Map<String, Integer> views(int days, Instant now);

    Optional<String> setting(String name);

    void setSetting(String name, String value);
}
