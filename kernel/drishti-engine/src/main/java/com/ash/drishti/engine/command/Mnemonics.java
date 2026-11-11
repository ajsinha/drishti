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

/** Mnemonic ↔ kind, from {@link CommandsProperties}. Readers see one complete table; a pack change swaps it whole ({@link #replace}). */
public final class Mnemonics {

    private record Table(Map<String, CommandsProperties.Mnemonic> byCode, Map<String, String> codeByKind) {}

    private volatile Table table;

    public Mnemonics(CommandsProperties props) {
        this.table = table(props.mnemonics());
    }

    private static Table table(Map<String, CommandsProperties.Mnemonic> source) {
        Map<String, CommandsProperties.Mnemonic> m = new TreeMap<>();
        Map<String, String> byKind = new LinkedHashMap<>();
        source.forEach((code, def) -> {
            String c = code.toUpperCase(Locale.ROOT);
            m.put(c, def);
            byKind.putIfAbsent(def.kind(), c);
        });
        return new Table(Collections.unmodifiableMap(m), Collections.unmodifiableMap(byKind));
    }

    /** Swaps in a new table of mnemonics (packs were loaded, changed or unloaded while the server runs). */
    public void replace(Map<String, CommandsProperties.Mnemonic> source) {
        this.table = table(source);
    }

    public Optional<CommandsProperties.Mnemonic> of(String code) {
        return Optional.ofNullable(table.byCode().get(code.toUpperCase(Locale.ROOT)));
    }

    public String codeFor(String kind) {
        return table.codeByKind().get(kind);
    }

    public Map<String, CommandsProperties.Mnemonic> all() {
        return table.byCode();
    }
}
