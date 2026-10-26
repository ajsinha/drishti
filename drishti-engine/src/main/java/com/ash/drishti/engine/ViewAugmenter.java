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
package com.ash.drishti.engine;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;

/**
 * Adds to a document what its page shows but no source stores: figures an engine computes when the page is opened, as the caller (the
 * VaR engine's VaR and ES of a position, a portfolio or a desk; docs/contracts/README.md section 14: a fundamental or input kind never
 * stores a computed figure). Applied by the {@link ViewPipeline} to the view, preview and panel records of a document of its kinds;
 * the stored document ({@link ViewPipeline#document}) is never changed. An augmenter that cannot compute says so in what it adds and
 * never fails the view.
 */
public interface ViewAugmenter {

    /** True when it adds to documents of this kind. */
    boolean augments(String kind);

    /** The document with what it adds; the document itself when it adds nothing. Never null, never throws. */
    EntityDocument augment(EntityDocument doc, AsOf asOf);
}
