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
package com.ash.drishti.api;

/**
 * An entity as read from a source: its address, its document and its provenance.
 *
 * <p>A live source may also push a <b>deletion</b> through {@link SourcePlugin#subscribe}: a document whose
 * {@link #deleted()} is true, made by {@link #deleted(EntityRef, Provenance)}, whose data is
 * {@link DataNode#missing()} and whose provenance says when the source learnt of the delete (a Kafka tombstone, a
 * queue's delete message). Reads ({@link SourcePlugin#fetch}) never return one: a deleted entity is simply not held.
 *
 * @param ref the entity address
 * @param data the document ({@link DataNode#missing()} for a deletion)
 * @param provenance source, generation and time of the read, or of the delete
 * @param deleted true when this is a deletion pushed to subscribers, not a document
 */
public record EntityDocument(EntityRef ref, DataNode data, Provenance provenance, boolean deleted) {

    /** A document as read (not a deletion). */
    public EntityDocument(EntityRef ref, DataNode data, Provenance provenance) {
        this(ref, data, provenance, false);
    }

    /** The deletion of {@code ref}, as a live source pushes it to its subscribers. */
    public static EntityDocument deleted(EntityRef ref, Provenance provenance) {
        return new EntityDocument(ref, DataNode.missing(), provenance, true);
    }
}
