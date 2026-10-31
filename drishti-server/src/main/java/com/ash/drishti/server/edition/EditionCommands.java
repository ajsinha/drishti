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
package com.ash.drishti.server.edition;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.packs.edition.Edition;

/**
 * The edition's command allow-list, separate from kind access (docs/admin/EDITIONS.md › The design as built, section 5.3): a command the edition hides is
 * left out of what people see and type (the command line, FUNC, the type-ahead, the packs' examples, the entity page and search), and
 * answers {@code 404 DRS-4023}; its kind stays readable by the engines (the VaR engine reads PNLV, SPV, FXS ...; the exposure engine
 * reads paths and agreements) because nothing here touches {@code PackAccess.kindAllowed}. A kind no mnemonic names is never hidden.
 */
public final class EditionCommands {

    /** No edition: every command shown (the default of a bean that is not injected, e.g. in a narrow test context). */
    public static final EditionCommands NONE = new EditionCommands(EditionContext.NONE, null);

    private final EditionContext context;
    private final Mnemonics mnemonics;

    public EditionCommands(EditionContext context, Mnemonics mnemonics) {
        this.context = context;
        this.mnemonics = mnemonics;
    }

    /** The edition the current request is seen through (the instance's, or the person's lens). */
    public Edition edition() {
        return context.current();
    }

    /** True when the command (a mnemonic, any case) is shown to people. */
    public boolean codeShown(String code) {
        return edition().commandShown(code);
    }

    /** True when the kind's command is shown (or no mnemonic names the kind). */
    public boolean kindShown(String kind) {
        Edition edition = edition();
        if (!edition.active() || kind == null) {
            return true;
        }
        String code = mnemonics.codeFor(kind);
        return code == null || edition.commandShown(code);
    }

    /** Refuses a command the edition hides ({@code 404 DRS-4023}). */
    public void requireCode(String code) {
        if (!codeShown(code)) {
            throw hidden(code, mnemonics.of(code.toUpperCase(java.util.Locale.ROOT)).map(m -> m.kind()).orElse(""));
        }
    }

    /** Refuses a kind whose command the edition hides ({@code 404 DRS-4023}). */
    public void requireKind(String kind) {
        if (!kindShown(kind)) {
            throw hidden(mnemonics.codeFor(kind), kind);
        }
    }

    private DrishtiException hidden(String code, String kind) {
        return new DrishtiException(ErrorCode.EDITION_COMMAND_HIDDEN, "the command " + code + (kind.isEmpty() ? "" : " (" + kind + ")")
                + " is not part of the edition '" + edition().name() + "' (FUNC lists the commands you can use)");
    }
}
