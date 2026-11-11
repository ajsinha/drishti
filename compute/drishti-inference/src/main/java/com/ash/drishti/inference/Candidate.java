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
package com.ash.drishti.inference;

import com.ash.drishti.rachana.model.Panel;

/**
 * A panel proposed by a rule.
 *
 * @param panel the proposed panel
 * @param score confidence in [0, 1]; the highest-scoring candidate per source path wins
 * @param rule the proposing rule's name
 * @param reason one line for "How this view was built" and Sutra Studio
 * @param sourcePath the document path the panel shows; candidates for the same path compete
 * @param order discovery order, used to keep the document's own field order in the layout
 */
public record Candidate(Panel panel, double score, String rule, String reason, String sourcePath, int order) {}
