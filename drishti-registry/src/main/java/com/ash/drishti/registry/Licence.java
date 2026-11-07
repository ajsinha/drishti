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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The customer's licence (ACCESS_MODEL.md section 3): which modules this installation may run, within which limits, from when to when.
 * Unsigned for now: without {@code config/access/licence.yaml} the installation runs the <em>development licence</em> (every module, no
 * limits, no end), so nothing changes for an existing installation. The signed licence (offline verification, expiry and grace) comes in
 * 2.9.11.i; the registry is the one place that will verify it.
 *
 * @param customer    who it is for
 * @param modules     the licence features (module ids) allowed; {@code *} allows every module
 * @param limits      limits per installation or per module (users, nodes, data volume), as written; read, not yet enforced
 * @param validFrom   first day it is valid (null: no start)
 * @param validTo     last day it is valid (null: no end)
 * @param signed      true when its signature was verified (always false until 2.9.11.i)
 * @param development true for the development licence (no file)
 * @param source      where it was read, in words
 */
public record Licence(String customer, Set<String> modules, Map<String, Object> limits, LocalDate validFrom, LocalDate validTo, boolean signed,
        boolean development, String source) {

    public Licence {
        customer = customer == null ? "" : customer.strip();
        modules = modules == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(modules));
        limits = limits == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(limits));
        source = source == null ? "" : source;
    }

    /** The development licence: every module, no limits, no end, unsigned. */
    public static Licence developmentLicence() {
        return new Licence("development", Set.of("*"), Map.of(), null, null, false, true, "no licence file: the development licence (all modules)");
    }

    /** Why the licence does not allow the feature on the day, or null when it does. */
    public String refusal(String feature, LocalDate today) {
        if (validFrom != null && today.isBefore(validFrom)) {
            return "the licence is valid from " + validFrom;
        }
        if (validTo != null && today.isAfter(validTo)) {
            return "the licence ended on " + validTo;
        }
        if (modules.contains("*") || modules.contains(feature)) {
            return null;
        }
        return "the licence does not include the module '" + feature + "'";
    }
}
