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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Tells open consoles that the switches moved (Admin → Commands): every user's live stream ({@code /api/v1/me/alerts/stream}) carries a
 * {@code switches} event, so a page of a kind just turned off is left at once and links and menus are drawn again. The event names only
 * the commands and kinds that are off, which every signed-in user is told anyway ("not active").
 */
public final class SwitchEvents {

    /**
     * The event: the ledger's hash and what is off now.
     *
     * @param hash the switches hash of this node
     * @param commands the commands turned off
     * @param kinds their kinds
     * @param packs the packs turned off
     */
    public record Changed(String hash, List<String> commands, List<String> kinds, List<String> packs) {}

    private final List<Consumer<Object>> listeners = new CopyOnWriteArrayList<>();

    /** Listens until the returned handle is closed. */
    public AutoCloseable listen(Consumer<Object> l) {
        listeners.add(l);
        return () -> listeners.remove(l);
    }

    public void publish(Changed c) {
        for (Consumer<Object> l : listeners) {
            try {
                l.accept(c);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(SwitchEvents.class).debug("switches: a listener failed: {}", e.toString());
            }
        }
    }

    public int listeners() {
        return listeners.size();
    }
}
