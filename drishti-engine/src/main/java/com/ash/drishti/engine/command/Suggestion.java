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
package com.ash.drishti.engine.command;

/**
 * One entry of the command-line dropdown.
 *
 * @param type {@code mnemonic}, {@code recent} or {@code entity}
 * @param mnemonic the mnemonic ({@code TRD})
 * @param kind the entity kind, or null for a bare mnemonic
 * @param id the entity id, or null for a bare mnemonic
 * @param title primary text
 * @param subtitle one-line description
 * @param complete the command text that choosing this entry puts in the input ({@code TRD IRS-48213})
 */
public record Suggestion(String type, String mnemonic, String kind, String id, String title, String subtitle, String complete) {}
