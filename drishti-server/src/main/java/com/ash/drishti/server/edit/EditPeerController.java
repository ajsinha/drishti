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
package com.ash.drishti.server.edit;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.net.NetCallFilter;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Write-back between the nodes of a cluster (WRITE_BACK.md section 11), on the signed node-only paths ({@code NetCallFilter}): a change
 * forwarded to its lane's owner, appended there as the person who made it (on behalf of them) and acknowledged once committed; a notice
 * that a change was applied elsewhere (caches dropped, the views and consoles here told); and the applied changes an edit primary's
 * followers copy.
 */
@RestController
@RequestMapping("/api/v1/net/edits")
public class EditPeerController {

    private final EditRuntime runtime;

    public EditPeerController(EditRuntime runtime) {
        this.runtime = runtime;
    }

    private static void requirePeer(String peer) {
        if (peer == null) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "between nodes only");
        }
    }

    @PostMapping("/append")
    public Map<String, Object> append(@RequestBody JsonNode body, @RequestAttribute(value = NetCallFilter.PEER, required = false) String peer,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        requirePeer(peer);
        Change c = Change.JSON.convertValue(body, Change.class);
        long[] at = runtime.appendForwarded(c, p);
        return Map.of("id", c.id(), "lane", at[0], "seq", at[1], "node", runtime.node());
    }

    @PostMapping("/changed")
    public Map<String, Object> changed(@RequestBody Map<String, Object> notice, @RequestAttribute(value = NetCallFilter.PEER, required = false) String peer) {
        requirePeer(peer);
        runtime.peerChanged(new HashMap<>(notice));
        return Map.of("ok", true);
    }

    /** {@code after=<lane>:<seq>,...} (the follower's cursors), or {@code change=<id>} (one change again). */
    @GetMapping("/follow")
    public Map<String, Object> follow(@RequestParam(required = false) String after, @RequestParam(required = false) String change,
            @RequestAttribute(value = NetCallFilter.PEER, required = false) String peer) {
        requirePeer(peer);
        if (change != null && !change.isBlank()) {
            return Map.of("changes", runtime.log().get(change).filter(r -> EditLog.APPLIED.equals(r.state())).map(r -> List.of(runtime.entry(r))).orElse(List.of()));
        }
        Map<Integer, Long> cursors = new HashMap<>();
        if (after != null && !after.isBlank()) {
            for (String part : after.split(",")) {
                String[] kv = part.split(":");
                if (kv.length == 2) {
                    cursors.put(Integer.parseInt(kv[0].strip()), Long.parseLong(kv[1].strip()));
                }
            }
        }
        return runtime.follow(cursors, 500);
    }
}
