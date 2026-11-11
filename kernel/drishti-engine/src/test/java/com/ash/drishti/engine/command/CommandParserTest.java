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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.graph.GraphProperties;
import com.ash.drishti.graph.ReferenceCatalog;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Reading commands, and what an unreadable one is told: the mnemonics configured here, never a pack's sample id. */
class CommandParserTest {

    private static CommandParser parser(Map<String, CommandsProperties.Mnemonic> mnemonics) {
        return new CommandParser(new Mnemonics(new CommandsProperties(mnemonics, null, null, null)),
                new ReferenceCatalog(new GraphProperties(null, null, null, null, null)));
    }

    @Test
    void readsMnemonicAndId() {
        var p = parser(Map.of("TRD", new CommandsProperties.Mnemonic("trade", "Trade")));
        assertThat(p.require("trd mx-20000001 <GO>").id()).isEqualTo("MX-20000001");
        assertThat(p.parse("  ")).isEmpty();
    }

    /** UX-05: the hint named TRD IRS-48213, a finance-pack sample that other packs do not have. */
    @Test
    void anUnreadableCommandIsToldTheConfiguredMnemonicsNotAPackSample() {
        var p = parser(Map.of("CPTY", new CommandsProperties.Mnemonic("counterparty", "Counterparty"),
                "LCR", new CommandsProperties.Mnemonic("lcr", "Liquidity coverage")));
        assertThatThrownBy(() -> p.require(""))
                .isInstanceOf(DrishtiException.class)
                .hasMessageContaining("<MNEMONIC> <ID> <GO>")
                .hasMessageContaining("CPTY, LCR")
                .hasMessageNotContaining("IRS-48213")
                .hasMessageNotContaining("TRD");
        assertThatThrownBy(() -> parser(Map.of()).require("XYZ 1"))
                .hasMessageContaining("no mnemonics are configured")
                .hasMessageNotContaining("IRS-48213");
    }
}
