-- Project Drishti · Any data. Any domain. One grammar.
--
-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
-- All rights reserved.
--
-- PROPRIETARY AND CONFIDENTIAL.
--
-- This file is the confidential and proprietary property of Ashutosh Sinha.
-- Unauthorised copying, use, modification, distribution or disclosure of this
-- file, via any medium, is strictly prohibited except with the express prior
-- written permission of the copyright holder.
--
-- See the LICENSE file in the root of this repository for the full terms.
-- Drishti identity schema for SQLite (the default: one file, nothing to install). Applied at start-up; every statement
-- is idempotent (IF NOT EXISTS), so this file is the whole schema, not a migration. Its twin is schema-postgres.sql.
-- SQLite keeps booleans as 0/1 and times as text or numbers; foreign keys are switched on per connection.

CREATE TABLE IF NOT EXISTS drishti_user (
    username             TEXT    PRIMARY KEY,
    display_name         TEXT    NOT NULL,
    email                TEXT    NOT NULL DEFAULT '',
    desk                 TEXT    NOT NULL DEFAULT '',
    enabled              INTEGER NOT NULL,
    must_change_password INTEGER NOT NULL,
    password_hash        TEXT    NOT NULL,
    failed_attempts      INTEGER NOT NULL DEFAULT 0,
    locked_until         TIMESTAMP,
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL,
    last_login_at        TIMESTAMP,
    password_changed_at  TIMESTAMP,
    packs_assigned       INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS drishti_user_role (
    username TEXT NOT NULL REFERENCES drishti_user (username) ON DELETE CASCADE,
    role     TEXT NOT NULL,
    PRIMARY KEY (username, role)
);

CREATE TABLE IF NOT EXISTS drishti_user_pack (
    username TEXT NOT NULL REFERENCES drishti_user (username) ON DELETE CASCADE,
    pack     TEXT NOT NULL,
    PRIMARY KEY (username, pack)
);

CREATE TABLE IF NOT EXISTS drishti_role (
    name        TEXT    PRIMARY KEY,
    description TEXT    NOT NULL DEFAULT '',
    raw_json    INTEGER NOT NULL DEFAULT 0,
    author      INTEGER NOT NULL DEFAULT 0,
    approve     INTEGER NOT NULL DEFAULT 0,
    admin       INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    updated_by  TEXT    NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS drishti_role_kind (
    role TEXT NOT NULL REFERENCES drishti_role (name) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    PRIMARY KEY (role, kind)
);

-- Powers added after the first release, one row each: a new power needs no new column. calc: may use Calc;
-- no-layout: may not customise layouts (layout mode is allowed by default, so the row marks its absence).
CREATE TABLE IF NOT EXISTS drishti_role_power (
    role  TEXT NOT NULL REFERENCES drishti_role (name) ON DELETE CASCADE,
    power TEXT NOT NULL,
    PRIMARY KEY (role, power)
);

CREATE TABLE IF NOT EXISTS drishti_audit (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    at      TIMESTAMP NOT NULL,
    actor   TEXT NOT NULL,
    action  TEXT NOT NULL,
    subject TEXT NOT NULL DEFAULT '',
    detail  TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS drishti_audit_subject ON drishti_audit (subject, id);
CREATE INDEX IF NOT EXISTS drishti_audit_actor ON drishti_audit (actor, id);

CREATE TABLE IF NOT EXISTS drishti_preference (
    username   TEXT NOT NULL,
    namespace  TEXT NOT NULL,
    name       TEXT NOT NULL,
    document   TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (username, namespace, name)
);

CREATE TABLE IF NOT EXISTS drishti_design (
    username   TEXT NOT NULL,
    id         TEXT NOT NULL,
    document   TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (username, id)
);

CREATE TABLE IF NOT EXISTS drishti_design_sample (
    username   TEXT NOT NULL,
    design_id  TEXT NOT NULL,
    name       TEXT NOT NULL,
    content    TEXT NOT NULL,
    PRIMARY KEY (username, design_id, name)
);

CREATE TABLE IF NOT EXISTS drishti_pack_state (
    name       TEXT    PRIMARY KEY,
    enabled    INTEGER NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    updated_by TEXT    NOT NULL DEFAULT ''
);

-- Administrators' switches (docs/admin/USER_MANAGEMENT.md): commands and packs on or off, the system of record that Drishti*net gossip spreads.
-- One row per scope (command | pack), name and grantee (* is everyone; a role once commands are granted per role); last writer wins by
-- (stamp, node), stamp being a hybrid logical clock. The log keeps every applied change (who, when, why), learned ones included, once.
CREATE TABLE IF NOT EXISTS drishti_switch (
    id         TEXT    PRIMARY KEY,
    scope      TEXT    NOT NULL,
    name       TEXT    NOT NULL,
    grantee    TEXT    NOT NULL DEFAULT '*',
    enabled    INTEGER NOT NULL,
    reason     TEXT    NOT NULL DEFAULT '',
    actor      TEXT    NOT NULL DEFAULT '',
    stamp      INTEGER NOT NULL,
    node       TEXT    NOT NULL DEFAULT '',
    updated_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS drishti_switch_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    scope      TEXT    NOT NULL,
    name       TEXT    NOT NULL,
    grantee    TEXT    NOT NULL DEFAULT '*',
    enabled    INTEGER NOT NULL,
    reason     TEXT    NOT NULL DEFAULT '',
    actor      TEXT    NOT NULL DEFAULT '',
    stamp      INTEGER NOT NULL,
    node       TEXT    NOT NULL DEFAULT '',
    at         TIMESTAMP NOT NULL,
    applied_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_switch_log_change ON drishti_switch_log (scope, name, grantee, stamp, node);

CREATE TABLE IF NOT EXISTS drishti_alert (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    username   TEXT    NOT NULL,
    at         TIMESTAMP NOT NULL,
    rule       TEXT    NOT NULL,
    kind       TEXT    NOT NULL,
    entity_id  TEXT    NOT NULL,
    severity   TEXT    NOT NULL,
    message    TEXT    NOT NULL,
    generation INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS drishti_alert_user ON drishti_alert (username, id);

CREATE TABLE IF NOT EXISTS drishti_api_token (
    id           TEXT PRIMARY KEY,
    username     TEXT NOT NULL,
    name         TEXT NOT NULL,
    secret_hash  TEXT NOT NULL,
    created_at   TIMESTAMP NOT NULL,
    expires_at   TIMESTAMP,
    last_used_at TIMESTAMP,
    revoked_at   TIMESTAMP,
    scopes       TEXT
);
CREATE INDEX IF NOT EXISTS drishti_api_token_user ON drishti_api_token (username);

-- Console sign-in sessions: a row per session, by a hash of its id; signing out, or disabling, deleting or resetting
-- the password of the user, removes the rows, and the console then refuses the cookie.
CREATE TABLE IF NOT EXISTS drishti_session (
    id_hash    TEXT PRIMARY KEY,
    username   TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS drishti_session_user ON drishti_session (username);
CREATE INDEX IF NOT EXISTS drishti_session_expires ON drishti_session (expires_at);

CREATE TABLE IF NOT EXISTS drishti_note (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    kind       TEXT NOT NULL,
    entity_id  TEXT NOT NULL,
    path       TEXT,
    username   TEXT NOT NULL,
    body       TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS drishti_note_entity ON drishti_note (kind, entity_id);

CREATE TABLE IF NOT EXISTS drishti_access (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    at            TIMESTAMP NOT NULL,
    username      TEXT NOT NULL,
    action        TEXT NOT NULL,
    kind          TEXT,
    entity_id     TEXT,
    detail        TEXT,
    business_date TEXT
);
CREATE INDEX IF NOT EXISTS drishti_access_entity ON drishti_access (kind, entity_id, at);
CREATE INDEX IF NOT EXISTS drishti_access_user ON drishti_access (username, at);
CREATE INDEX IF NOT EXISTS drishti_access_at ON drishti_access (at);

-- Collaboration (docs/architecture/COLLABORATION.md): shares, comment threads, the inbox, the email outbox and legal holds.
-- Every table of the design is created here so later build steps add none; step 2 uses share, share_recipient and inbox.

CREATE TABLE IF NOT EXISTS drishti_share (
    id VARCHAR(30) PRIMARY KEY,
    sender VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    kind VARCHAR(64) NOT NULL,
    entity_id VARCHAR(200) NOT NULL,
    panel_id VARCHAR(100),
    gate_kind VARCHAR(64),
    pin_date DATE,
    pin_live INTEGER NOT NULL,
    pin_known_at TIMESTAMP,
    pin_generation INTEGER NOT NULL,
    pin_source VARCHAR(100),
    body VARCHAR(4000) NOT NULL,
    masked_spans VARCHAR(2000) NOT NULL,
    channels VARCHAR(40) NOT NULL,
    thread_id VARCHAR(30),
    hash VARCHAR(64) NOT NULL
);
CREATE INDEX IF NOT EXISTS drishti_share_sender ON drishti_share (sender, id);
CREATE INDEX IF NOT EXISTS drishti_share_entity ON drishti_share (kind, entity_id);

CREATE TABLE IF NOT EXISTS drishti_share_recipient (
    share_id VARCHAR(30) NOT NULL,
    seq INTEGER NOT NULL,
    addressed VARCHAR(80) NOT NULL,
    username VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    opened_at TIMESTAMP,
    PRIMARY KEY (share_id, seq)
);
CREATE INDEX IF NOT EXISTS drishti_share_recipient_user ON drishti_share_recipient (username, share_id);

CREATE TABLE IF NOT EXISTS drishti_thread (
    id VARCHAR(30) PRIMARY KEY,
    kind VARCHAR(64) NOT NULL,
    entity_id VARCHAR(200) NOT NULL,
    anchor VARCHAR(8) NOT NULL,
    panel_id VARCHAR(100),
    path VARCHAR(200),
    gate_kind VARCHAR(64),
    anchor_label VARCHAR(200) NOT NULL,
    state VARCHAR(10) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_at TIMESTAMP NOT NULL,
    comments INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS drishti_thread_entity ON drishti_thread (kind, entity_id);

CREATE TABLE IF NOT EXISTS drishti_comment (
    id VARCHAR(30) PRIMARY KEY,
    thread_id VARCHAR(30) NOT NULL,
    author VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    edited_at TIMESTAMP,
    revision INTEGER NOT NULL,
    pin_date DATE,
    pin_live INTEGER NOT NULL,
    pin_known_at TIMESTAMP,
    pin_generation INTEGER NOT NULL,
    pin_source VARCHAR(100),
    body VARCHAR(4000) NOT NULL,
    masked_spans VARCHAR(2000) NOT NULL,
    state VARCHAR(10) NOT NULL,
    state_reason VARCHAR(400)
);
CREATE INDEX IF NOT EXISTS drishti_comment_thread ON drishti_comment (thread_id, created_at);

CREATE TABLE IF NOT EXISTS drishti_comment_revision (
    comment_id VARCHAR(30) NOT NULL,
    revision INTEGER NOT NULL,
    at TIMESTAMP NOT NULL,
    actor VARCHAR(64) NOT NULL,
    action VARCHAR(12) NOT NULL,
    body VARCHAR(4000),
    reason VARCHAR(400),
    prev_hash VARCHAR(64) NOT NULL,
    hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (comment_id, revision)
);

CREATE TABLE IF NOT EXISTS drishti_mention (
    comment_id VARCHAR(30) NOT NULL,
    target VARCHAR(80) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (comment_id, target)
);
CREATE INDEX IF NOT EXISTS drishti_mention_target ON drishti_mention (target, created_at);

-- A legacy note and the comment it became (the import, and the deprecated /notes facade): note_id is drishti_note.id.
CREATE TABLE IF NOT EXISTS drishti_note_link (
    note_id INTEGER PRIMARY KEY,
    comment_id VARCHAR(30) NOT NULL
);

CREATE TABLE IF NOT EXISTS drishti_follow (
    thread_id VARCHAR(30) NOT NULL,
    username VARCHAR(64) NOT NULL,
    muted INTEGER NOT NULL,
    since TIMESTAMP NOT NULL,
    PRIMARY KEY (thread_id, username)
);

CREATE TABLE IF NOT EXISTS drishti_inbox (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    username VARCHAR(64) NOT NULL,
    at TIMESTAMP NOT NULL,
    type VARCHAR(12) NOT NULL,
    kind VARCHAR(64) NOT NULL,
    entity_id VARCHAR(200) NOT NULL,
    panel_id VARCHAR(100),
    share_id VARCHAR(30),
    thread_id VARCHAR(30),
    comment_id VARCHAR(30),
    actor VARCHAR(64) NOT NULL,
    read_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS drishti_inbox_user ON drishti_inbox (username, seq);

CREATE TABLE IF NOT EXISTS drishti_outbox (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    channel VARCHAR(12) NOT NULL,
    recipient VARCHAR(200) NOT NULL,
    template VARCHAR(40) NOT NULL,
    ref_id VARCHAR(30) NOT NULL,
    state VARCHAR(10) NOT NULL,
    attempts INTEGER NOT NULL,
    next_at TIMESTAMP NOT NULL,
    lease_until TIMESTAMP,
    leased_by VARCHAR(64),
    last_error VARCHAR(400),
    created_at TIMESTAMP NOT NULL,
    sent_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS drishti_outbox_due ON drishti_outbox (state, next_at);

CREATE TABLE IF NOT EXISTS drishti_collab_hold (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    scope VARCHAR(8) NOT NULL,
    kind VARCHAR(64),
    entity_id VARCHAR(200),
    username VARCHAR(64),
    thread_id VARCHAR(30),
    date_from TIMESTAMP,
    date_to TIMESTAMP,
    reason VARCHAR(400) NOT NULL,
    placed_by VARCHAR(64) NOT NULL,
    placed_at TIMESTAMP NOT NULL,
    released_by VARCHAR(64),
    released_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS drishti_collab_seal (
    seal_key VARCHAR(200) PRIMARY KEY,
    cnt BIGINT NOT NULL,
    hash VARCHAR(64) NOT NULL
);

-- Host applications that may show views in their own pages (Drishti Elements; docs/architecture/ELEMENTS.md).
CREATE TABLE IF NOT EXISTS drishti_embed_app (
    id                    TEXT    PRIMARY KEY,
    name                  TEXT    NOT NULL,
    contact               TEXT    NOT NULL DEFAULT '',
    origins               TEXT    NOT NULL DEFAULT '',
    kinds                 TEXT    NOT NULL DEFAULT '',
    scopes                TEXT    NOT NULL DEFAULT '',
    subject_types         TEXT    NOT NULL DEFAULT '',
    subject_audiences     TEXT    NOT NULL DEFAULT '',
    jwks                  TEXT,
    secret_hash           TEXT,
    prev_secret_hash      TEXT,
    prev_secret_until     TIMESTAMP,
    token_seconds         INTEGER NOT NULL,
    calls_per_minute      INTEGER NOT NULL,
    user_calls_per_minute INTEGER NOT NULL,
    enabled               INTEGER NOT NULL,
    created_at            TIMESTAMP NOT NULL,
    created_by            TEXT    NOT NULL DEFAULT '',
    updated_at            TIMESTAMP NOT NULL,
    updated_by            TEXT    NOT NULL DEFAULT '',
    last_used_at          TIMESTAMP
);

-- Business intelligence (Rupaka, docs/architecture/RUPAKA.md): workspaces, members, items, versions, per-user marks, usage, settings.
CREATE TABLE IF NOT EXISTS drishti_bi_workspace (
    id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', personal INTEGER NOT NULL, owner TEXT NOT NULL DEFAULT '',
    review_required INTEGER NOT NULL, created_at TIMESTAMP NOT NULL, created_by TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS drishti_bi_member (
    id TEXT PRIMARY KEY, workspace TEXT NOT NULL, username TEXT NOT NULL, role TEXT NOT NULL, added_at TIMESTAMP NOT NULL, added_by TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS ix_bi_member_user ON drishti_bi_member (username);
CREATE TABLE IF NOT EXISTS drishti_bi_item (
    id TEXT PRIMARY KEY, workspace TEXT NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', owner TEXT NOT NULL,
    tags TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '', status TEXT NOT NULL, published_version INTEGER NOT NULL DEFAULT 0,
    endorsement TEXT NOT NULL DEFAULT 'none', endorsed_by TEXT NOT NULL DEFAULT '', audience_roles TEXT NOT NULL DEFAULT '', audience_users TEXT NOT NULL DEFAULT '',
    audience_packs TEXT NOT NULL DEFAULT '', proposed_by TEXT NOT NULL DEFAULT '', proposed_at TIMESTAMP, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
    updated_by TEXT NOT NULL DEFAULT '', published_at TIMESTAMP, published_by TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS ix_bi_item_workspace ON drishti_bi_item (workspace);
CREATE TABLE IF NOT EXISTS drishti_bi_version (
    id TEXT PRIMARY KEY, item_id TEXT NOT NULL, version INTEGER NOT NULL, body TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '',
    created_by TEXT NOT NULL DEFAULT '', created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_bi_version_item ON drishti_bi_version (item_id);
CREATE TABLE IF NOT EXISTS drishti_bi_revision (
    id TEXT PRIMARY KEY, item_id TEXT NOT NULL, seq INTEGER NOT NULL, body TEXT NOT NULL DEFAULT '', message TEXT NOT NULL DEFAULT '', author TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL, origin TEXT NOT NULL DEFAULT 'save', published_version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_bi_revision_item ON drishti_bi_revision (item_id);
CREATE TABLE IF NOT EXISTS drishti_bi_event (
    id TEXT PRIMARY KEY, item_id TEXT NOT NULL, seq INTEGER NOT NULL, action TEXT NOT NULL, actor TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '',
    revision INTEGER NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_bi_event_item ON drishti_bi_event (item_id);
CREATE TABLE IF NOT EXISTS drishti_bi_user_item (
    id TEXT PRIMARY KEY, username TEXT NOT NULL, item_id TEXT NOT NULL, favourite INTEGER NOT NULL, last_opened TIMESTAMP, opens INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_bi_user_item_user ON drishti_bi_user_item (username);
CREATE TABLE IF NOT EXISTS drishti_bi_usage (
    id TEXT PRIMARY KEY, item_id TEXT NOT NULL, day TEXT NOT NULL, views INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_bi_usage_item ON drishti_bi_usage (item_id);
CREATE TABLE IF NOT EXISTS drishti_bi_setting (
    name TEXT PRIMARY KEY, value TEXT NOT NULL DEFAULT ''
);
-- BI runs (docs/architecture/BI_RUNS.md section 3.4): every visible BI run, its shares and its schedules. Names and counts only, never a data value.
CREATE TABLE IF NOT EXISTS drishti_bi_run (
    id TEXT PRIMARY KEY,
    runner TEXT NOT NULL,
    owner TEXT NOT NULL,
    workspace TEXT,
    source_type TEXT NOT NULL,
    source_id TEXT,
    source_ref TEXT,
    title TEXT,
    question TEXT NOT NULL,
    params TEXT,
    as_of TEXT,
    data_version TEXT,
    fingerprint TEXT NOT NULL,
    signature TEXT NOT NULL,
    result_key TEXT,
    tier TEXT NOT NULL,
    engine TEXT NOT NULL,
    state TEXT NOT NULL,
    progress REAL,
    phase TEXT,
    estimate TEXT NOT NULL,
    rows_read INTEGER,
    rows_out INTEGER,
    bytes_out INTEGER,
    code TEXT,
    reason TEXT,
    node TEXT NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 1,
    lease_until TIMESTAMP,
    submitted_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    expires_at TIMESTAMP,
    last_opened TIMESTAMP,
    rerun_of TEXT,
    shared_from TEXT,
    reuse_of TEXT,
    group_id TEXT,
    promoted_to TEXT
);
CREATE INDEX IF NOT EXISTS ix_bi_run_owner ON drishti_bi_run (owner, submitted_at);
CREATE INDEX IF NOT EXISTS ix_bi_run_state ON drishti_bi_run (node, state);
CREATE INDEX IF NOT EXISTS ix_bi_run_key ON drishti_bi_run (result_key);
CREATE TABLE IF NOT EXISTS drishti_bi_run_share (
    run_id TEXT NOT NULL,
    recipient TEXT NOT NULL,
    mode TEXT NOT NULL,
    recipient_run TEXT,
    shared_by TEXT NOT NULL,
    shared_at TIMESTAMP NOT NULL,
    PRIMARY KEY (run_id, recipient)
);
CREATE INDEX IF NOT EXISTS ix_bi_run_share_recipient ON drishti_bi_run_share (recipient);
CREATE TABLE IF NOT EXISTS drishti_bi_run_schedule (
    id TEXT PRIMARY KEY,
    owner TEXT NOT NULL,
    run_id TEXT,
    title TEXT,
    question TEXT NOT NULL,
    params TEXT,
    source_type TEXT NOT NULL,
    source_id TEXT,
    schedule TEXT NOT NULL,
    data TEXT NOT NULL DEFAULT 'latest',
    enabled INTEGER NOT NULL DEFAULT 1,
    last_run TEXT,
    next_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_bi_run_schedule_owner ON drishti_bi_run_schedule (owner);

-- The content store (docs/architecture/CONTENT_STORE.md): user content as keyed documents with a compare-and-set version; deleted = 1 is a tombstone.
CREATE TABLE IF NOT EXISTS drishti_content (
    k          TEXT    PRIMARY KEY,
    v          BLOB,
    version    INTEGER NOT NULL,
    updated_ms INTEGER NOT NULL,
    deleted    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_content_updated ON drishti_content (updated_ms);

-- Compute requirements' approval (docs/architecture/PYTHON_COMPUTE.md section 6): requests to approve what a connector or pool requires,
-- and the approved items (one row per item per approving request; revoked_at set when revoked). Four eyes: requested_by never decides.
CREATE TABLE IF NOT EXISTS drishti_compute_request (
    id            TEXT PRIMARY KEY,
    subject       TEXT NOT NULL,
    fingerprint   TEXT NOT NULL,
    items         TEXT NOT NULL,
    state         TEXT NOT NULL,
    requested_by  TEXT NOT NULL,
    requested_at  TIMESTAMP NOT NULL,
    decided_by    TEXT,
    decided_at    TIMESTAMP,
    reason        TEXT,
    revoked_by    TEXT,
    revoked_at    TIMESTAMP,
    revoke_reason TEXT
);
CREATE INDEX IF NOT EXISTS ix_compute_request_subject ON drishti_compute_request (subject, state);
CREATE TABLE IF NOT EXISTS drishti_compute_approval (
    item_key    TEXT NOT NULL,
    request_id  TEXT NOT NULL,
    kind        TEXT NOT NULL,
    name        TEXT NOT NULL,
    version     TEXT NOT NULL,
    hash        TEXT NOT NULL,
    source      TEXT NOT NULL,
    native_code INTEGER NOT NULL,
    approved_by TEXT NOT NULL,
    approved_at TIMESTAMP NOT NULL,
    revoked_at  TIMESTAMP,
    PRIMARY KEY (item_key, request_id)
);
CREATE INDEX IF NOT EXISTS ix_compute_approval_live ON drishti_compute_approval (item_key, revoked_at);

-- Write-back (docs/guides/WRITE_BACK.md › The design and its reasons, sections 5 to 11): P lanes, each one durable ordered queue with one owner at a time
-- (ownership taken by a conditional UPDATE, fenced by its epoch); the edit log, one row per change, ordered by (lane, seq), acknowledged
-- only once its row is committed; changes awaiting approval; overlays and Drishti-owned values written in the same transaction as the
-- log row's state; each record's version; and how far each consumer of applied changes (the reactive triggers) has read per lane.
-- Lease times are epoch milliseconds on the database's clock.
CREATE TABLE IF NOT EXISTS drishti_edit_lane (
    lane        INTEGER PRIMARY KEY,
    owner_node  TEXT,
    epoch       INTEGER NOT NULL DEFAULT 0,
    lease_until INTEGER,
    next_seq    INTEGER NOT NULL DEFAULT 1,
    applied_seq INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS drishti_edit_log (
    lane             INTEGER NOT NULL,
    seq              INTEGER NOT NULL,
    change_id        TEXT    NOT NULL UNIQUE,
    epoch            INTEGER NOT NULL,
    state            TEXT    NOT NULL,
    change_key       TEXT    NOT NULL,
    kind             TEXT    NOT NULL,
    entity           TEXT    NOT NULL,
    business_date    TEXT    NOT NULL,
    target           TEXT    NOT NULL,
    connector        TEXT,
    body             TEXT    NOT NULL,
    command          TEXT,
    command_attempts INTEGER NOT NULL DEFAULT 0,
    command_sent_at  TIMESTAMP,
    command_reason   TEXT,
    state_reason     TEXT,
    by_user          TEXT    NOT NULL,
    submitted_at     TIMESTAMP NOT NULL,
    applied_at       TIMESTAMP,
    signature        TEXT    NOT NULL,
    PRIMARY KEY (lane, seq)
);
CREATE INDEX IF NOT EXISTS ix_edit_log_key ON drishti_edit_log (change_key, lane, seq);
CREATE INDEX IF NOT EXISTS ix_edit_log_state ON drishti_edit_log (state, submitted_at);
CREATE INDEX IF NOT EXISTS ix_edit_log_command ON drishti_edit_log (command, lane, seq);
CREATE TABLE IF NOT EXISTS drishti_edit_request (
    change_id     TEXT    PRIMARY KEY,
    change_key    TEXT    NOT NULL,
    kind          TEXT    NOT NULL,
    entity        TEXT    NOT NULL,
    target        TEXT    NOT NULL,
    body          TEXT    NOT NULL,
    state         TEXT    NOT NULL,
    needed        INTEGER NOT NULL,
    approvals     TEXT    NOT NULL,
    by_user       TEXT    NOT NULL,
    submitted_at  TIMESTAMP NOT NULL,
    decided_by    TEXT,
    decided_at    TIMESTAMP,
    reason        TEXT
);
CREATE INDEX IF NOT EXISTS ix_edit_request_state ON drishti_edit_request (state, submitted_at);
CREATE TABLE IF NOT EXISTS drishti_overlay (
    change_id     TEXT    NOT NULL,
    path          TEXT    NOT NULL,
    kind          TEXT    NOT NULL,
    entity        TEXT    NOT NULL,
    value         TEXT,
    source_value  TEXT,
    business_date TEXT    NOT NULL,
    valid_until   TEXT,
    state         TEXT    NOT NULL,
    by_user       TEXT    NOT NULL,
    applied_at    TIMESTAMP NOT NULL,
    PRIMARY KEY (change_id, path)
);
CREATE INDEX IF NOT EXISTS ix_overlay_entity ON drishti_overlay (kind, entity, state);
CREATE TABLE IF NOT EXISTS drishti_owned (
    kind       TEXT    NOT NULL,
    entity     TEXT    NOT NULL,
    path       TEXT    NOT NULL,
    value      TEXT,
    change_id  TEXT    NOT NULL,
    updated_by TEXT    NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (kind, entity, path)
);
CREATE TABLE IF NOT EXISTS drishti_edit_version (
    change_key  TEXT    PRIMARY KEY,
    version     INTEGER NOT NULL,
    change_id   TEXT    NOT NULL,
    updated_at  TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS drishti_edit_cursor (
    consumer TEXT    NOT NULL,
    lane     INTEGER NOT NULL,
    seq      INTEGER NOT NULL,
    PRIMARY KEY (consumer, lane)
);
