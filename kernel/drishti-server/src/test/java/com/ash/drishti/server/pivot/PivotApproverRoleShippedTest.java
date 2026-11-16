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
package com.ash.drishti.server.pivot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The four-eyes approval of custom pivot functions works out of the box: the role {@code drishti.pivot.functions.approver-role} names as
 * shipped is one of the built-in roles ({@code drishti.security.roles}), so a user can be given it at once (no {@code DRS-6007 unknown
 * role 'pivot-approver'}).
 */
class PivotApproverRoleShippedTest {

    @Test
    void theShippedApproverRoleIsABuiltInRole() throws Exception {
        List<PropertySource<?>> pivot = new YamlPropertySourceLoader().load("pivot", new ClassPathResource("engines/pivot.yaml"));
        Object role = pivot.get(0).getProperty("drishti.pivot.functions.approver-role");
        assertThat(role).isEqualTo("pivot-approver");
        List<PropertySource<?>> app = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        assertThat(app.get(0).getProperty("drishti.security.roles." + role + ".kinds[0]")).as("built in, every kind").isEqualTo("*");
        assertThat(app.get(0).getProperty("drishti.security.roles." + role + ".raw")).as("no raw JSON: approving needs none").isNull();
        assertThat(app.get(0).getProperty("drishti.security.roles." + role + ".approve")).as("not the Sutra review's approve").isNull();
    }
}
