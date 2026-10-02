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

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.graph.ReferenceCatalog;
import java.util.Locale;
import java.util.Optional;

/**
 * Parses {@code TRD MX-20000001 <GO>}, {@code nset ns-north-01}, or a bare {@code MX-20000001} (the kind is then
 * taken from the identifier patterns). {@code <GO>} and case in the mnemonic are ignored.
 */
public final class CommandParser {

    /** Most mnemonics an unreadable command's message names. */
    private static final int HINT_MNEMONICS = 6;

    private final Mnemonics mnemonics;
    private final ReferenceCatalog catalog;

    public CommandParser(Mnemonics mnemonics, ReferenceCatalog catalog) {
        this.mnemonics = mnemonics;
        this.catalog = catalog;
    }

    public Optional<EntityRef> parse(String text) {
        String t = text == null ? "" : text.replaceAll("(?i)<\\s*GO\\s*>", " ").trim();
        if (t.isEmpty()) {
            return Optional.empty();
        }
        String[] parts = t.split("\\s+", 2);
        var m = mnemonics.of(parts[0]);
        if (m.isPresent()) {
            return parts.length < 2 ? Optional.empty() : Optional.of(EntityRef.of(m.get().kind(), parts[1].trim().toUpperCase(Locale.ROOT)));
        }
        if (parts.length == 1) {
            String id = parts[0].toUpperCase(Locale.ROOT);
            return catalog.kindOf(id).map(k -> EntityRef.of(k, id));
        }
        return Optional.empty();
    }

    public EntityRef require(String text) {
        return parse(text).orElseThrow(() -> new DrishtiException(ErrorCode.COMMAND_UNKNOWN,
                "cannot read command '" + (text == null ? "" : text) + "'; " + hint()));
    }

    /**
     * How to write a command, with the mnemonics configured here (from the packs loaded), never an entity of a pack that
     * may not be installed.
     */
    private String hint() {
        var codes = mnemonics.all().keySet();
        if (codes.isEmpty()) {
            return "type <MNEMONIC> <ID> <GO>, but no mnemonics are configured: is a pack loaded?";
        }
        String some = String.join(", ", codes.stream().limit(HINT_MNEMONICS).toList());
        return "type <MNEMONIC> <ID> <GO> with a mnemonic such as " + some
                + (codes.size() > HINT_MNEMONICS ? " (" + codes.size() + " in all; type a letter for suggestions)" : "");
    }
}
