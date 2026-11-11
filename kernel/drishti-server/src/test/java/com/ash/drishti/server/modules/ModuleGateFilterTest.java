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
package com.ash.drishti.server.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ash.drishti.registry.AccessRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** A function waiting for data answers 409 DRS-1035 with what it needs; a function switched off still answers 404 DRS-1026. */
class ModuleGateFilterTest {

    @Test
    void aFunctionWaitingForDataExplainsItselfAndOneSwitchedOffIsGone() throws Exception {
        AccessRegistry registry = mock(AccessRegistry.class);
        when(registry.moduleOfApi(anyString())).thenReturn(Optional.of("banking"));
        when(registry.functionOfApi("/api/v1/risk/exposure/scope")).thenReturn(Optional.of("EXP"));
        when(registry.functionOfApi("/api/v1/simm/run")).thenReturn(Optional.of("SIMM"));
        String need = "EXP needs data: exposure-path is not bound; ask your data administrator";
        when(registry.functionOffReason("EXP")).thenReturn(Optional.of(need));
        when(registry.dataGateReason("EXP")).thenReturn(Optional.of(need));
        when(registry.functionOffReason("SIMM")).thenReturn(Optional.of("an administrator switched it off (Admin -> Functions)"));
        when(registry.dataGateReason("SIMM")).thenReturn(Optional.empty());
        ModuleGateFilter f = new ModuleGateFilter(registry, id -> true);

        MockHttpServletResponse exp = run(f, "/api/v1/risk/exposure/scope");
        assertThat(exp.getStatus()).isEqualTo(409);
        assertThat(exp.getContentAsString()).contains("\"code\":\"DRS-1035\"", "exposure-path is not bound", "Data bindings");

        MockHttpServletResponse simm = run(f, "/api/v1/simm/run");
        assertThat(simm.getStatus()).isEqualTo(404);
        assertThat(simm.getContentAsString()).contains("DRS-1026");
    }

    private static MockHttpServletResponse run(ModuleGateFilter f, String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRequestURI(path);
        req.setServletPath(path);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res;
    }
}
