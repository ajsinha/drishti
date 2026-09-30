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
package com.ash.drishti.sutra.model;

/**
 * Which entities a Sutra applies to.
 *
 * @param kind the entity kind
 * @param where an optional Sutra-EL predicate over the document; null matches every entity of the kind
 * @param priority higher wins when several Sutras match
 */
public record Match(String kind, String where, int priority) {}
