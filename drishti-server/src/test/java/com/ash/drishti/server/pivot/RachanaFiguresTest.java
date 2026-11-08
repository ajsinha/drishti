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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.rows.RowsProvider;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.server.security.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Rachana's {@code measure(...)} and {@code cell(...)} on the server (docs/architecture/ARCHITECTURE.md part 3): answered for the person of the request only,
 * refused in words with no person, with the pivot engine off, for a measure no compute engine offers (a source's plain fields are not
 * measures), and for a scope not written LEVEL:MEMBER. The figures themselves are the engine's (PivotApiTest, the VaR provider's tests).
 */
class RachanaFiguresTest {

    private static PivotCore core() {
        List<PivotProvider.Field> fields = List.of(new PivotProvider.Field("DESK", "string", "Desk", ""),
                new PivotProvider.Field("MTM", "number", "MTM", ""));
        List<Object[]> rows = List.of(new Object[] {"DESK-COMM", 10.0}, new Object[] {"DESK-RATES", 5.0});
        return new PivotCore(List.of(new RowsProvider("trades", "trades", fields, rows)), StateCache.none(), PivotCore.Settings.defaults());
    }

    private static void signedIn() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute(Principal.ATTRIBUTE, new Principal("ann", List.of("trader")));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r));
    }

    @AfterEach
    void signOut() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void aFigureIsAnsweredForAPersonOnlyAndNeverWithThePivotEngineOff() {
        RachanaFigures figures = new RachanaFigures(core(), null, () -> true);
        assertThatThrownBy(() -> figures.measure("VAR", "DESK:DESK-COMM", Map.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signed-in person");
        signedIn();
        RachanaFigures off = new RachanaFigures(core(), null, () -> false);
        assertThatThrownBy(() -> off.measure("VAR", "", Map.of())).hasMessageContaining("switched off");
        assertThatThrownBy(() -> off.cell("ann/desk-pnl", Map.of())).hasMessageContaining("switched off");
    }

    @Test
    void onlyAComputeEnginesOwnMeasureIsAMeasureAndAScopeIsLevelColonMember() {
        signedIn();
        RachanaFigures figures = new RachanaFigures(core(), null, () -> true);
        assertThatThrownBy(() -> figures.measure("MTM", "DESK:DESK-COMM", Map.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no compute engine on this server offers the measure 'mtm'");
        assertThatThrownBy(() -> figures.measure("sum", "DESK", Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
