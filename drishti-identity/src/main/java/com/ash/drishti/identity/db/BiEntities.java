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
package com.ash.drishti.identity.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** The rows of business intelligence (Rupaka, docs/architecture/BI_DESIGN.md): workspaces, members, items, versions, per-user marks, usage, settings. */
public final class BiEntities {

    private BiEntities() {}

    /** A BI workspace: a container for items, personal (one per user) or shared. */
    @Entity(name = "BiWorkspace")
    @Table(name = "drishti_bi_workspace")
    public static class BiWorkspace {
        @Id
        @Column(name = "id", nullable = false)
        public String id;

        @Column(name = "name", nullable = false)
        public String name = "";

        @Column(name = "description", nullable = false)
        public String description = "";

        @Column(name = "personal", nullable = false)
        public boolean personal;

        @Column(name = "owner", nullable = false)
        public String owner = "";

        @Column(name = "review_required", nullable = false)
        public boolean reviewRequired;

        @Column(name = "created_at", nullable = false)
        public Instant createdAt;

        @Column(name = "created_by", nullable = false)
        public String createdBy = "";
    }

    /** A person's role in a shared workspace (id = workspace/username). */
    @Entity(name = "BiMember")
    @Table(name = "drishti_bi_member")
    public static class BiMember {
        @Id
        @Column(name = "id", nullable = false)
        public String id;

        @Column(name = "workspace", nullable = false)
        public String workspace = "";

        @Column(name = "username", nullable = false)
        public String username = "";

        @Column(name = "role", nullable = false)
        public String role = "";

        @Column(name = "added_at", nullable = false)
        public Instant addedAt;

        @Column(name = "added_by", nullable = false)
        public String addedBy = "";
    }

    /** A typed BI item (dataset, report, board, notebook): its draft body, lifecycle and catalogue fields. */
    @Entity(name = "BiItem")
    @Table(name = "drishti_bi_item")
    public static class BiItem {
        @Id
        @Column(name = "id", nullable = false)
        public String id;

        @Column(name = "workspace", nullable = false)
        public String workspace = "";

        @Column(name = "kind", nullable = false)
        public String kind = "";

        @Column(name = "title", nullable = false)
        public String title = "";

        @Column(name = "description", nullable = false)
        public String description = "";

        @Column(name = "owner", nullable = false)
        public String owner = "";

        @Column(name = "tags", nullable = false)
        public String tags = "";

        @Column(name = "body", nullable = false)
        public String body = "";

        @Column(name = "status", nullable = false)
        public String status = "";

        @Column(name = "published_version", nullable = false)
        public int publishedVersion;

        @Column(name = "endorsement", nullable = false)
        public String endorsement = "none";

        @Column(name = "endorsed_by", nullable = false)
        public String endorsedBy = "";

        @Column(name = "audience_roles", nullable = false)
        public String audienceRoles = "";

        @Column(name = "audience_users", nullable = false)
        public String audienceUsers = "";

        @Column(name = "audience_packs", nullable = false)
        public String audiencePacks = "";

        @Column(name = "proposed_by", nullable = false)
        public String proposedBy = "";

        @Column(name = "proposed_at")
        public Instant proposedAt;

        @Column(name = "created_at", nullable = false)
        public Instant createdAt;

        @Column(name = "updated_at", nullable = false)
        public Instant updatedAt;

        @Column(name = "updated_by", nullable = false)
        public String updatedBy = "";

        @Column(name = "published_at")
        public Instant publishedAt;

        @Column(name = "published_by", nullable = false)
        public String publishedBy = "";
    }

    /** A published version of an item (id = item/version); never changed. */
    @Entity(name = "BiVersion")
    @Table(name = "drishti_bi_version")
    public static class BiVersion {
        @Id
        @Column(name = "id", nullable = false)
        public String id;

        @Column(name = "item_id", nullable = false)
        public String itemId = "";

        @Column(name = "version", nullable = false)
        public int version;

        @Column(name = "body", nullable = false)
        public String body = "";

        @Column(name = "note", nullable = false)
        public String note = "";

        @Column(name = "created_by", nullable = false)
        public String createdBy = "";

        @Column(name = "created_at", nullable = false)
        public Instant createdAt;
    }

    /** One saved state of an item's body (id = item/seq): every save is a revision, so nothing an author did is lost. */
    @Entity(name = "BiRevision")
    @Table(name = "drishti_bi_revision")
    public static class BiRevision {
        @Id
        @Column(name = "id", nullable = false)
        public String id;

        @Column(name = "item_id", nullable = false)
        public String itemId = "";

        @Column(name = "seq", nullable = false)
        public int seq;

        @Column(name = "body", nullable = false)
        public String body = "";

        @Column(name = "message", nullable = false)
        public String message = "";

        @Column(name = "author", nullable = false)
        public String author = "";

        @Column(name = "created_at", nullable = false)
        public Instant createdAt;

        @Column(name = "origin", nullable = false)
        public String origin = "save";

        @Column(name = "published_version", nullable = false)
        public int publishedVersion;
    }

    /** A governance event on an item (id = item/seq): review requested, approved, rejected, withdrawn, certification given or revoked, imported. */
    @Entity(name = "BiEvent")
    @Table(name = "drishti_bi_event")
    public static class BiEvent {
        @Id
        @Column(name = "id", nullable = false)
        public String id;

        @Column(name = "item_id", nullable = false)
        public String itemId = "";

        @Column(name = "seq", nullable = false)
        public int seq;

        @Column(name = "action", nullable = false)
        public String action = "";

        @Column(name = "actor", nullable = false)
        public String actor = "";

        @Column(name = "note", nullable = false)
        public String note = "";

        @Column(name = "revision", nullable = false)
        public int revision;

        @Column(name = "created_at", nullable = false)
        public Instant createdAt;
    }

    /** What a user does with an item: favourite, last opened (id = username/item). */
    @Entity(name = "BiUserItem")
    @Table(name = "drishti_bi_user_item")
    public static class BiUserItem {
        @Id
        @Column(name = "id", nullable = false)
        public String id;

        @Column(name = "username", nullable = false)
        public String username = "";

        @Column(name = "item_id", nullable = false)
        public String itemId = "";

        @Column(name = "favourite", nullable = false)
        public boolean favourite;

        @Column(name = "last_opened")
        public Instant lastOpened;

        @Column(name = "opens", nullable = false)
        public int opens;
    }

    /** Views of an item on a day (id = item/day), for the 30-day usage count. */
    @Entity(name = "BiUsage")
    @Table(name = "drishti_bi_usage")
    public static class BiUsage {
        @Id
        @Column(name = "id", nullable = false)
        public String id;

        @Column(name = "item_id", nullable = false)
        public String itemId = "";

        @Column(name = "day", nullable = false)
        public String day = "";

        @Column(name = "views", nullable = false)
        public int views;
    }

    /** A BI setting an administrator changed at run time. */
    @Entity(name = "BiSetting")
    @Table(name = "drishti_bi_setting")
    public static class BiSetting {
        @Id
        @Column(name = "name", nullable = false)
        public String name;

        @Column(name = "value", nullable = false)
        public String value = "";
    }
}
