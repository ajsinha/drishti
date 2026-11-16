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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.rachana.RachanaProperties;
import com.ash.drishti.rachana.SutraProblem;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.model.SourceLocation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** SEC-10: API errors are RFC 7807 problems with a DRS code. SEC-11: Sutra problems show no server paths. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false"})
@AutoConfigureMockMvc
class ErrorShapeTest {

    @Autowired MockMvc mvc;
    @Autowired SutraRegistry sutras;
    @Autowired RachanaProperties props;

    @Test
    void aMissingQueryParameterIsA400WithADrsCode() throws Exception {
        mvc.perform(get("/api/v1/search/compare").param("q", "TRD IRS-48213").header("X-Drishti-User", "t1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DRS-5001"))
                .andExpect(jsonPath("$.detail").value(containsString("'from'")));
    }

    @Test
    void aBadDateIsA400WithADrsCodeAndNoStackTrace() throws Exception {
        mvc.perform(get("/api/v1/search/compare").param("q", "TRD IRS-48213").param("from", "xx").header("X-Drishti-User", "t1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DRS-5001"))
                .andExpect(jsonPath("$.detail").value(not(containsString("Exception"))));
    }

    @Test
    void sutraPathsAreRelativeToTheSutraRoot() {
        String root = Path.of(props.allDirs().get(0)).toAbsolutePath().normalize().toString();
        List<SutraProblem> shown = sutras.relative(List.of(new SutraProblem("DRS-2001", "cannot read: " + root + "/a/b.sutra.yaml",
                new SourceLocation(root + "/a/b.sutra.yaml", 3, 4))));
        assertThat(shown.get(0).location().file()).isEqualTo("a/b.sutra.yaml");
        assertThat(shown.get(0).message()).doesNotContain(root);
    }
}
