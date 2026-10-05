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
package com.ash.drishti.identity.collab;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Where inbox rows are kept: the identity database ({@link JpaInboxStore}) or files ({@link FileInboxStore}). */
public interface InboxStore {

    /** Stores the row and returns it with its sequence number. */
    Notice add(Notice notice);

    /** The user's rows, newest first, filtered by type (null = all) and unread; {@code beforeSeq} (exclusive, 0 = none) pages. */
    List<Notice> list(String username, String type, boolean unreadOnly, int limit, long beforeSeq);

    long unread(String username);

    /** Marks these rows of the user read; returns how many changed. */
    int markRead(String username, Collection<Long> seqs, Instant at);

    /** Marks every row of the user up to {@code upTo} read; returns how many changed. */
    int markReadUpTo(String username, long upTo, Instant at);

    /** Rows of any user above {@code seq}, oldest first (another server's writes, for the hub's poll). */
    List<Notice> after(long seq, int limit);

    /** The highest sequence number stored (0 when none). */
    long maxSeq();

    /** Keeps only the user's newest {@code keep} rows. */
    void prune(String username, int keep);

    /** Removes a user's rows (the user was deleted). */
    void forget(String username);
}
