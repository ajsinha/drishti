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
import java.util.List;
import java.util.Optional;

/** Where shares are kept: the identity database ({@link JpaShareStore}) or files ({@link FileShareStore}); both pass the same contract. */
public interface ShareStore {

    /** Stores a share and its recipients (call inside {@link CollabTx#run}, with the inbox rows and the access-log row). */
    void save(Share share, List<Recipient> recipients);

    Optional<Share> find(String id);

    /** The recipients in the order they were saved. */
    List<Recipient> recipients(String shareId);

    /** Sets the recipient's first-open time; true only when this call set it. */
    boolean markOpened(String shareId, String username, Instant at);

    /** The sender's shares, newest first; {@code beforeId} (exclusive) pages. */
    List<Share> sent(String sender, int limit, String beforeId);

    /** Shares that reached the user (recipient state {@code notified}), newest first. */
    List<Share> received(String username, int limit, String beforeId);

    /** Shares in id (creation) order after {@code afterId} (null = from the start), at most {@code limit}. */
    List<Share> page(String afterId, int limit);

    /** Removes the share and its recipients (retention only, never under a legal hold). */
    void delete(String id);

    /** How many shares the sender made at or after {@code since}. */
    long countSentSince(String sender, Instant since);
}
