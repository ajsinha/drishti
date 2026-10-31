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
package com.ash.drishti.server.bi.expr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * docs/guides/BI_LANGUAGE_REFERENCE.md and the function catalogue agree (docs/architecture/BI_DESIGN.md section 3.3.8): the guide's function reference is exactly what
 * {@link FunctionDocs} renders from {@code functions.yaml}. To rewrite the guide's section after changing the catalogue:
 * {@code ./mvnw -o -q -pl drishti-server test -Dtest=FunctionDocsTest -Dexpressions.doc.update=true}.
 */
class FunctionDocsTest {

    static final Path GUIDE = Path.of("..", "docs", "guides", "BI_LANGUAGE_REFERENCE.md");

    @Test
    void theGuideListsEveryFunctionOfTheCatalogueAsTheCatalogueSays() throws Exception {
        String guide = Files.readString(GUIDE);
        String rendered = FunctionDocs.render(FunctionCatalog.get());
        if (Boolean.getBoolean("expressions.doc.update")) {
            String updated = FunctionDocs.splice(guide, rendered);
            assertThat(updated).as("the guide has the generated-section markers").isNotNull();
            Files.writeString(GUIDE, updated);
            guide = updated;
        }
        assertThat(FunctionDocs.section(guide)).as("BI_LANGUAGE_REFERENCE.md differs from functions.yaml: run this test with -Dexpressions.doc.update=true").isEqualTo(rendered);
        for (FunctionCatalog.Fn f : FunctionCatalog.get().all()) {
            assertThat(guide).contains("#### `" + f.signature() + "`");
        }
    }
}
