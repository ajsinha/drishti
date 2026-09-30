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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Mnemonic ↔ kind, from {@link CommandsProperties}. Immutable. */
public final class Mnemonics {

    private final Map<String, CommandsProperties.Mnemonic> byCode;
    private final Map<String, String> codeByKind = new LinkedHashMap<>();

    public Mnemonics(CommandsProperties props) {
        Map<String, CommandsProperties.Mnemonic> m = new TreeMap<>();
        props.mnemonics().forEach((code, def) -> {
            String c = code.toUpperCase(Locale.ROOT);
            m.put(c, def);
            codeByKind.putIfAbsent(def.kind(), c);
        });
        this.byCode = Collections.unmodifiableMap(m);
    }

    public Optional<CommandsProperties.Mnemonic> of(String code) {
        return Optional.ofNullable(byCode.get(code.toUpperCase(Locale.ROOT)));
    }

    public String codeFor(String kind) {
        return codeByKind.get(kind);
    }

    public Map<String, CommandsProperties.Mnemonic> all() {
        return byCode;
    }
}
