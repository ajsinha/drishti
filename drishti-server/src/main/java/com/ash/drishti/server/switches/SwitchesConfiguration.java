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
package com.ash.drishti.server.switches;

import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.identity.SwitchLedger;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.net.NetService;
import com.ash.drishti.server.security.CommandSwitches;
import com.ash.drishti.server.security.PackAccess;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The commands' and packs' switches (Admin → Commands, docs/admin/USER_MANAGEMENT.md): the ledger (the identity database) gives the access checks
 * which commands are off, names its changes after the Drishti*net node, is spread by gossip, and every change tells open consoles.
 */
@Configuration(proxyBeanMethods = false)
public class SwitchesConfiguration {

    @Bean
    public CommandSwitches commandSwitches(SwitchLedger ledger, Mnemonics mnemonics, PackAccess access) {
        CommandSwitches c = new CommandSwitches(ledger, mnemonics);
        access.commands(c);
        return c;
    }

    @Bean
    public CommandCatalog commandCatalog(PackRegistry registry, Mnemonics mnemonics, com.ash.drishti.packs.edition.Edition edition) {
        return new CommandCatalog(registry, mnemonics, edition);
    }

    @Bean
    public SwitchEvents switchEvents(SwitchLedger ledger, CommandSwitches commands) {
        SwitchEvents events = new SwitchEvents();
        ledger.onChange(moved -> events.publish(changed(ledger, commands)));
        return events;
    }

    @Bean(destroyMethod = "close")
    public SwitchSync switchSync(ObjectProvider<NetService> net, SwitchLedger ledger) {
        NetService n = net.getIfAvailable();
        if (n == null) {
            return null;
        }
        ledger.node(n::plannedNodeId);                       // a change is stamped with the node id the cluster knows this node by
        return new SwitchSync(n, ledger);
    }

    /** What is off now, for the consoles' live event. */
    static SwitchEvents.Changed changed(SwitchLedger ledger, CommandSwitches commands) {
        List<SwitchLedger.Entry> off = ledger.entries().stream().filter(e -> !e.on() && SwitchLedger.EVERYONE.equals(e.grantee())).toList();
        List<String> codes = off.stream().filter(e -> SwitchLedger.COMMAND.equals(e.scope())).map(SwitchLedger.Entry::name).toList();
        List<String> kinds = codes.stream().map(c -> commands.mnemonics().of(c).map(m -> m.kind()).orElse(null)).filter(k -> k != null).toList();
        List<String> packs = off.stream().filter(e -> SwitchLedger.PACK.equals(e.scope())).map(SwitchLedger.Entry::name).toList();
        return new SwitchEvents.Changed(ledger.hash(), codes, kinds, packs);
    }
}
