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
package com.ash.drishti.kernel.api.services;

import java.util.Map;

/** The doors of the kernel for what a module reads itself: the same entitlements, masks and row rules the REST paths apply. */
public interface Access {

    /**
     * Refuses a kind closed to the person.
     *
     * @throws Kernel.Refused with the code and words the REST path answers
     */
    void requireOpen(Person p, String kind);

    /** A record's fields with the person's masks applied (a masked field as the REST answers show it). */
    Map<String, Object> redact(Person p, String kind, Map<String, Object> data);
}
