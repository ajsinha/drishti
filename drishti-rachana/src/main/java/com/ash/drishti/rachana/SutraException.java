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
package com.ash.drishti.rachana;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.List;

/** A Sutra failed to parse or validate. Carries every problem found, not only the first. */
public class SutraException extends DrishtiException {

    private static final long serialVersionUID = 1L;
    private final transient List<SutraProblem> problems;

    public SutraException(List<SutraProblem> problems) {
        super(ErrorCode.SUTRA_INVALID, problems.size() + " problem(s): " + problems);
        this.problems = List.copyOf(problems);
    }

    public List<SutraProblem> problems() {
        return problems;
    }
}
