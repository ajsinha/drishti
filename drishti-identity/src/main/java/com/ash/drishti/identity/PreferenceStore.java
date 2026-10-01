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
package com.ash.drishti.identity;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/**
 * Per-user documents by namespace: workspaces, monitors, alert rules, settings. Bounded: a document is at most a few
 * tens of kilobytes and a namespace holds at most a few dozen, so one user cannot fill the store. Kept in the identity
 * database ({@link JpaPreferenceStore}).
 */
public interface PreferenceStore {

    /** Names in a namespace, case-insensitively sorted. */
    List<String> keys(String user, String namespace);

    Optional<JsonNode> get(String user, String namespace, String key);

    void put(String user, String namespace, String key, JsonNode value);

    boolean delete(String user, String namespace, String key);

    /** Users who have stored anything. */
    List<String> users();

    /** Removes every document of a user (called when the user is deleted). */
    void forget(String user);
}
