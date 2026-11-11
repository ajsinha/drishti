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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/** UX-16: a fresh start's WARN lines are the ones that need an action; expected conditions are not warnings. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false"})
class QuietStartTest {

    @Autowired Environment env;

    @Test
    void noWarningForConditionsThatNeedNoAction() {
        assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(env.getProperty("springdoc.api-docs.enabled")).isNotNull();                  // set, so springdoc does not warn
        for (String logger : new String[] {"io.delta.kernel.internal.snapshot.SnapshotManager", "org.hibernate.orm.incubating"}) {
            var l = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(logger);
            assertThat(l.getEffectiveLevel().isGreaterOrEqual(Level.ERROR)).as(logger).isTrue();
        }
    }
}
