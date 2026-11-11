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
import java.util.Objects;

/** One sample document of a Design ({@code drishti_design_sample}). Never logged. */
@Entity
@Table(name = "drishti_design_sample")
public class DesignSampleEntity {

    /** (user, design, sample name). */
    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "username")
        public String username;

        @Column(name = "design_id")
        public String designId;

        @Column(name = "name")
        public String name;

        public Key() {}

        public Key(String username, String designId, String name) {
            this.username = username;
            this.designId = designId;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(username, k.username) && Objects.equals(designId, k.designId) && Objects.equals(name, k.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(username, designId, name);
        }
    }

    @EmbeddedId
    public Key key;

    @Column(name = "content", nullable = false)
    public String content;
}
