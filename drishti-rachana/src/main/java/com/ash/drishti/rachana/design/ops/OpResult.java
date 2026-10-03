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
package com.ash.drishti.rachana.design.ops;

import java.util.List;

/**
 * What a list of operations made.
 *
 * @param yaml the Sutra text after every operation that could be applied (the input text when none could)
 * @param problems one entry per operation that could not be applied
 * @param applied how many operations were applied
 */
public record OpResult(String yaml, List<OpProblem> problems, int applied) {}
