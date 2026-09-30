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

import com.ash.drishti.sutra.model.Panel;
import com.ash.drishti.sutra.model.StripItem;
import com.ash.drishti.sutra.model.Title;
import java.util.List;
import java.util.Map;

/**
 * The layout inference proposes for a document with no Sutra.
 *
 * @param title the inferred title line
 * @param strip the inferred header figures
 * @param panels main-column panels, then right-column panels, each in document order
 * @param explanations panel id to "rule score: reason"
 */
public record InferredLayout(Title title, List<StripItem> strip, List<Panel> panels, Map<String, String> explanations) {}
