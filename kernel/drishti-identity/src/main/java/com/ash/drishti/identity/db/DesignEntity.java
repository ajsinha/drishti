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

/** One Build workbench Design ({@code drishti_design}): its metadata as JSON text; sample documents are in {@link DesignSampleEntity}. */
@Entity
@Table(name = "drishti_design")
public class DesignEntity {

    /** (user, id). */
    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "username")
        public String username;

        @Column(name = "id")
        public String id;

        public Key() {}

        public Key(String username, String id) {
            this.username = username;
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(username, k.username) && Objects.equals(id, k.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(username, id);
        }
    }

    @EmbeddedId
    public Key key;

    @Column(name = "document", nullable = false)
    public String document;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
