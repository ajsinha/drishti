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
package com.ash.drishti.rachana.ui;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.List;

/** A chip or board document that cannot be read ({@code DRS-2070}): not YAML or JSON, or a required key missing or wrong. */
public final class UiDocumentException extends DrishtiException {

    private final transient List<UiProblem> problems;

    public UiDocumentException(String message, List<UiProblem> problems) {
        super(ErrorCode.UI_DOCUMENT_INVALID, message + ": " + problems.stream().map(UiProblem::toString).reduce((a, b) -> a + "; " + b).orElse(""));
        this.problems = List.copyOf(problems);
    }

    /** What is wrong, each with its path (rule {@code BC-01}). */
    public List<UiProblem> problems() {
        return problems;
    }
}
