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

import com.ash.drishti.identity.SwitchLedger;
import com.ash.drishti.server.net.NetService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/net/switches}: this node's switches ledger, for a peer whose gossip showed a different hash ({@link SwitchSync}).
 * A cluster endpoint: only a node calls it (the cluster credential, NetCallFilter), and it does not exist while the cluster is off.
 */
@RestController
@RequestMapping("/api/v1/net")
public class SwitchPeerController {

    private final NetService net;
    private final SwitchLedger ledger;

    public SwitchPeerController(NetService net, SwitchLedger ledger) {
        this.net = net;
        this.ledger = ledger;
    }

    @GetMapping("/switches")
    public Map<String, Object> switches() {
        net.require();
        return SwitchSync.toWire(net.nodeId(), ledger);
    }
}
