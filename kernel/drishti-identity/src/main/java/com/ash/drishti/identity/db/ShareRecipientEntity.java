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
package com.ash.drishti.identity.db;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** Who a share was addressed to and who it reached ({@code drishti_share_recipient}): one row per person after expansion. */
@Entity
@Table(name = "drishti_share_recipient")
public class ShareRecipientEntity {

    /** (share, position). */
    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "share_id")
        public String shareId;

        @Column(name = "seq")
        public int seq;

        public Key() {}

        public Key(String shareId, int seq) {
            this.shareId = shareId;
            this.seq = seq;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(shareId, k.shareId) && seq == k.seq;
        }

        @Override
        public int hashCode() {
            return Objects.hash(shareId, seq);
        }
    }

    @EmbeddedId
    public Key key;

    @Column(name = "addressed", nullable = false)
    public String addressed;

    @Column(name = "username", nullable = false)
    public String username;

    @Column(name = "state", nullable = false)
    public String state;

    @Column(name = "opened_at")
    public Instant openedAt;
}
