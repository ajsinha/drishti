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

CREATE TABLE IF NOT EXISTS drishti_pack_state (
    name       TEXT    PRIMARY KEY,
    enabled    INTEGER NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    updated_by TEXT    NOT NULL DEFAULT ''
);
