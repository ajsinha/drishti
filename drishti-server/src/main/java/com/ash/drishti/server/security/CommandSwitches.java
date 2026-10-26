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
package com.ash.drishti.server.security;

import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.identity.SwitchLedger;
import java.util.Optional;

/**
 * The commands administrators turned off (Admin → Commands, docs/admin/COMMANDS.md), read from the switches ledger. A command is its
 * mnemonic ({@code FRTB}), which names one kind ({@code frtb-sensitivity}); when the command is off its kind is not active anywhere:
 * {@link PackAccess#kindAllowed} is false for everyone, so every surface that asks {@link Entitlements#mayOpen} leaves it out, and
 * {@link Entitlements#requireOpen} answers {@code 403 DRS-1016}. Kinds no mnemonic names are never off. Lock-free: two map lookups.
 */
public final class CommandSwitches {

    private final SwitchLedger ledger;
    private final Mnemonics mnemonics;

    public CommandSwitches(SwitchLedger ledger, Mnemonics mnemonics) {
        this.ledger = ledger;
        this.mnemonics = mnemonics;
    }

    /** The command of the kind (its mnemonic), or null when none names it. */
    public String commandOf(String kind) {
        return kind == null ? null : mnemonics.codeFor(kind);
    }

    /** The ledger's entry when an administrator turned the kind's command off for everyone. */
    public Optional<SwitchLedger.Entry> off(String kind) {
        String code = commandOf(kind);
        return code == null ? Optional.empty() : ledger.find(SwitchLedger.COMMAND, code).filter(e -> !e.on());
    }

    /** False when the kind's command is turned off. */
    public boolean active(String kind) {
        return off(kind).isEmpty();
    }

    /** False when the command (a mnemonic, any case) is turned off. */
    public boolean commandOn(String code) {
        return code == null || ledger.on(SwitchLedger.COMMAND, code.toUpperCase(java.util.Locale.ROOT));
    }

    /** The refusal for a kind whose command is off ({@code 403 DRS-1016}), naming the command and the day it was turned off. */
    public CommandOffException refusal(String kind) {
        SwitchLedger.Entry e = off(kind).orElse(null);
        String since = e == null || e.at() == null ? "" : e.at().toString().substring(0, 10);
        return new CommandOffException(commandOf(kind), kind, since);
    }

    public SwitchLedger ledger() {
        return ledger;
    }

    public Mnemonics mnemonics() {
        return mnemonics;
    }
}
