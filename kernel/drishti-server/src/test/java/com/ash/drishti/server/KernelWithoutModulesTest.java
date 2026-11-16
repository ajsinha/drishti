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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.kernel.api.vocabulary.DomainVocabulary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** The kernel boots with no banking module (docs/architecture/KERNEL_AND_MODULES.md P1): this class path has none, and the core asks nobody. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false"})
class KernelWithoutModulesTest {

    @Autowired ApplicationContext context;

    @Test
    void bootsWithNoBankingOnTheClassPath() {
        assertThat(context).isNotNull();
        assertThatThrownBy(() -> Class.forName("com.ash.drishti.banking.BankingAutoConfiguration")).isInstanceOf(ClassNotFoundException.class);
        assertThat(DomainVocabulary.union(DomainVocabulary::phraseMeasures)).isEmpty();
        assertThat(DomainVocabulary.union(DomainVocabulary::reservedFunctionNames)).isEmpty();
    }
}
