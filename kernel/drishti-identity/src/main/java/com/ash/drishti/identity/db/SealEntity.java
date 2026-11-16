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
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One tamper-evidence seal ({@code drishti_collab_seal}): a count and a hash under a key such as {@code thread:th_...}. */
@Entity
@Table(name = "drishti_collab_seal")
public class SealEntity {

    @Id
    @Column(name = "seal_key", length = 200)
    public String key;

    @Column(name = "cnt", nullable = false)
    public long count;

    @Column(name = "hash", nullable = false)
    public String hash;
}
