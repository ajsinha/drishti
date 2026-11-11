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
package com.ash.drishti.registry;

import java.util.List;
import java.util.Map;

/**
 * An assignment (ACCESS_MODEL.md section 3): a person or a group, the roles it holds in the access model, and the attributes row rules
 * read (desk, legal entity ...). Read from {@code config/access/assignments.yaml}.
 *
 * @param subject    {@code user:<name>} or {@code group:<name>} (a bare name is a user)
 * @param roles      the roles it holds
 * @param attributes its attributes
 */
public record Assignment(String subject, List<String> roles, Map<String, String> attributes) {

    public Assignment {
        subject = subject == null ? "" : subject.strip();
        if (!subject.isEmpty() && !subject.contains(":")) {
            subject = "user:" + subject;
        }
        roles = roles == null ? List.of() : List.copyOf(roles);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** True when it is about this person (by name or one of their groups). */
    public boolean matches(Subject s) {
        return subject.equals("user:" + s.user()) || s.groups().stream().anyMatch(g -> subject.equals("group:" + g));
    }
}
