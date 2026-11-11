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

import java.util.List;
import java.util.Optional;

/** Where users are kept. Implementations must be thread-safe and make each write durable before returning. */
public interface UserStore {

    Optional<User> find(String username);

    List<User> all();

    /** Inserts or replaces by user name. */
    void put(User user);

    boolean delete(String username);

    /**
     * Writes the user only when the store holds no user at all, in one step with that check; returns whether it wrote. The first
     * administrator is seeded this way: a node of a cluster may receive the cluster's users while it starts, and a seed written after
     * an earlier look found the store empty replaced the cluster's administrator with a new one of the same name.
     */
    boolean putIfEmpty(User user);
}
