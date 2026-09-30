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

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.rachana.SutraRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"drishti.rachana.hot-reload=false"})
class DrishtiApplicationTest {

    private final SourceRegistry registry;
    private final SourceRouter router;
    private final SutraRegistry sutras;

    @Autowired
    DrishtiApplicationTest(SourceRegistry registry, SourceRouter router, SutraRegistry sutras) {
        this.registry = registry;
        this.router = router;
        this.sutras = sutras;
    }

    @Test
    void loadsTheReferenceSutras() {
        assertThat(sutras.all()).extracting(s -> s.id())
                .containsExactlyInAnyOrder("fx-swap@2", "irs-vanilla@3", "listed-future@1", "netting-set@1");
        assertThat(sutras.problems()).isEmpty();
    }

    @Test
    void startsWithTheDemoSourceAndServesReferenceEntities() {
        assertThat(registry.plugin("demo")).isPresent();
        assertThat(registry.plugin("file")).isPresent();
        assertThat(router.fetch(EntityRef.of("trade", "IRS-48213")).join().provenance().source()).isEqualTo("aero-risk");
    }
}
