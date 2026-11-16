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
package com.ash.drishti.registry;

/**
 * A module's state as the registry decides it: on = installed ∧ licensed ∧ enabled, and every module it requires on.
 *
 * @param id           its id
 * @param title        its name
 * @param domain       its domain
 * @param installed    present here (its jar, or a content module's file)
 * @param licensed     the licence allows it
 * @param enabled      the administrator's switch (or the shipped default)
 * @param on           whether it runs here
 * @param reason       why it is off, in words (blank when on)
 * @param switchSource where the switch's value comes from ({@code shipped}, {@code config/modules/banking.yaml}, {@code drishti.modules.banking.enabled})
 */
public record ModuleState(String id, String title, String domain, boolean installed, boolean licensed, boolean enabled, boolean on, String reason,
        String switchSource) {}
