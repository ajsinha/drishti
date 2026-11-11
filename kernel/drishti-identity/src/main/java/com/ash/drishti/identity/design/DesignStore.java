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
package com.ash.drishti.identity.design;

import java.util.List;
import java.util.Optional;

/**
 * Where the Build workbench keeps Designs: a file store ({@link FileDesignStore}, {@code data/designs/<user>/<id>/}) or the
 * identity database ({@link JpaDesignStore}), chosen by {@code drishti.builder.designs.store}. Primitive operations only;
 * quotas, expiry and ownership rules are {@link DesignService}'s. Every method is addressed by (user, id), so one user's
 * Design can never be reached through another's name. Sample contents are never logged by a store.
 */
public interface DesignStore {

    /** The user's Designs (metadata with the sample index, no sample documents), in no particular order. */
    List<StoredDesign> list(String user);

    Optional<StoredDesign> get(String user, String id);

    /** Creates or replaces the Design's metadata ({@link StoredDesign#owner} and {@link StoredDesign#id} say where). */
    void save(StoredDesign design);

    /** Deletes a Design and all its sample documents at once; false when there was none. */
    boolean delete(String user, String id);

    /** Keeps (or replaces) one sample document, JSON text. */
    void putSample(String user, String id, String name, String json);

    Optional<String> sample(String user, String id, String name);

    void removeSample(String user, String id, String name);

    /** Users who have any Design. */
    List<String> users();

    /** Removes every Design of a user. */
    void forget(String user);
}
