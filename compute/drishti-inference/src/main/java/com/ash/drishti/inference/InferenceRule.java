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

import java.util.List;

/**
 * Proposes panels for the parts of a document it recognises. Rules are stateless, deterministic and
 * cheap; they run once per document shape (results are cached by fingerprint).
 */
public interface InferenceRule {

    String name();

    void propose(RuleContext ctx, List<Candidate> out);
}
