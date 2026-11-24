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
package com.ash.drishti.server.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/** The planner's template is read on first use: a missing part never stops the server, and the workbook keeps Excel's {@code _rels/.rels} name. */
class CapacityLazyTemplateTest {

    @Test
    void aMissingTemplatePartAnswersOnlyWhenUsed() {
        AtomicInteger builds = new AtomicInteger();
        Supplier<CapacityWorkbook> missing = CapacityController.lazy(() -> {
            builds.incrementAndGet();
            throw new IllegalStateException("the server has no capacity/template/xl/styles.xml (tools/sizing_planner.py writes it)");
        });
        assertThat(builds).hasValue(0);                                          // constructing the controller reads nothing
        assertThatThrownBy(missing::get).isInstanceOfSatisfying(DrishtiException.class, e -> {
            assertThat(e.getMessage()).contains("the capacity template is missing capacity/template/xl/styles.xml");
            assertThat(e.errorCode()).isEqualTo(ErrorCode.MODULE_PART_UNAVAILABLE);
        });
    }

    @Test
    void theTemplateIsBuiltOnceAndKept() {
        AtomicInteger builds = new AtomicInteger();
        Supplier<String> s = CapacityController.lazy(() -> "built" + builds.incrementAndGet());
        assertThat(s.get()).isEqualTo(s.get()).isEqualTo("built1");
    }

    @Test
    void theWorkbookHoldsExcelsRootRelationshipsPart() throws Exception {
        CapacityWorkbook w = CapacityWorkbook.builtIn();
        assertThat(w.partNames()).contains("_rels/.rels");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        w.write(out, Map.of(), null, Map.of(), java.util.List.of(), com.ash.drishti.common.Branding.current());
        Set<String> names = new HashSet<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                names.add(e.getName());
            }
        }
        assertThat(names).containsExactlyInAnyOrderElementsOf(w.partNames()).contains("_rels/.rels").doesNotContain("_rels/root.rels.xml");
    }
}
