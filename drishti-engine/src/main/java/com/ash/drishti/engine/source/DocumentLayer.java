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
package com.ash.drishti.engine.source;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Subscription;

/**
 * What is laid over a source's document at read time (WRITE_BACK.md section 5.2): write-back's overlays and Drishti-owned values, applied
 * by the router to every document it reads ({@link SourceRouter#fetch}) and every live tick, so a view, a panel's records, a computed
 * panel's read and a linked entity all see the same corrected value. {@link #watch} tells a live view that a change was applied to its
 * record, so it reads it again (with the viewer's masks, as every rebuild is). {@link #NONE} as long as write-back is off.
 */
public interface DocumentLayer {

    DocumentLayer NONE = new DocumentLayer() {
        @Override
        public EntityDocument apply(EntityDocument doc, AsOf asOf) {
            return doc;
        }

        @Override
        public Subscription watch(EntityRef ref, Runnable changed) {
            return Subscription.NONE;
        }

        @Override
        public boolean active() {
            return false;
        }
    };

    /** The document with what is laid over it for the as-of read; the same document when nothing is. */
    EntityDocument apply(EntityDocument doc, AsOf asOf);

    /** Calls {@code changed} whenever a change to {@code ref} is applied, until closed. */
    Subscription watch(EntityRef ref, Runnable changed);

    /** True when changes can be applied (write-back on): a view of a record that no source streams stays open for them. */
    boolean active();
}
