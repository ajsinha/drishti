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
package com.ash.drishti.net.config;

import java.util.List;
import java.util.Map;

/**
 * One seed peer ({@code config/net/peers/<name>.yaml}): where to knock when joining. A deleted file is no longer a seed;
 * gossip may still know the node.
 *
 * @param name the file's name without {@code .yaml}
 * @param url its address
 * @param tags the tags it is expected to carry (Admin → Net warns when gossip says otherwise)
 * @param tls mutual TLS overrides for this peer, flattened
 */
public record SeedPeer(String name, String url, List<String> tags, Map<String, String> tls) {

    public SeedPeer {
        url = url == null ? "" : url.trim().replaceAll("/+$", "");
        tags = tags == null ? List.of() : List.copyOf(tags);
        tls = tls == null ? Map.of() : Map.copyOf(tls);
    }
}
