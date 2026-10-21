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
package com.ash.drishti.packs;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Connector edits made through Admin, shared by every node of a cluster (CONTENT_STORE.md section 11): the files in
 * {@code config/connectors} stay the base (GitOps), and an edit is an overlay over the file of that name. The text kept is
 * exactly what was saved: {@code ${VAR}} placeholders stay unresolved and masked credentials are never written.
 * {@link ConnectorFiles} reads through it when one is attached; the server's implementation keeps it in the content store.
 */
public interface ConnectorOverlay {

    /** One connector's overlay: its text, or {@code hidden} when an Admin delete hides the file of that name. */
    record Entry(String text, boolean hidden, Instant at) {}

    /** The overlay of one connector, or null when it has none (the file, if any, is what counts). */
    Entry get(String name);

    /** Every connector with an overlay. */
    Map<String, Entry> all();

    /**
     * Saves the text as the connector's overlay, keeping the previous effective text in the shared history.
     * {@code expectedEtag} is the {@link ConnectorFiles#etag etag} of the effective text the edit was based on (null: no check);
     * {@code fileText} is the file's text (null when none), which is effective when there is no overlay. Another node's edit
     * since then is a {@code 409} conflict.
     */
    void put(String name, String text, String expectedEtag, String fileText);

    /** Hides the connector on every node (an Admin delete of a connector the files define), keeping its text in the history. */
    void hide(String name, String effectiveText);

    /** Removes the overlay (Reset): the file, or the pack's template, counts again. True when there was one. */
    boolean remove(String name, String effectiveText);

    /** The kept earlier texts, newest first; ids are {@code yyyyMMdd'T'HHmmssSSS[-n]} like the file history. */
    List<ConnectorFiles.Revision> history(String name);

    /** The text of one kept revision, or null. */
    String revisionText(String name, String id);
}
