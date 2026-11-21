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
package com.ash.drishti.server.live;

import com.ash.rachana.live.spi.LiveStream;
import java.util.List;

/**
 * A kind's {@code live:} binding as a live table's stream (LIVE_TABLES 1.2, i8 step 2): every change of the kind, whole, as the router
 * delivers it (the Kafka plug-in and every other live connector unchanged); a deletion by the record's id; a binding whose broker is
 * absent is {@code paused} with its reason, never an error.
 */
public final class KindStream implements LiveStream {

    private final String kind;
    private final KindBindings bindings;
    private final List<String> key;

    public KindStream(String kind, KindBindings bindings, List<String> key) {
        this.kind = kind;
        this.bindings = bindings;
        this.key = List.copyOf(key);
    }

    @Override
    public AutoCloseable subscribe(Listener l) {
        AutoCloseable h = bindings.subscribe(kind, d -> {
            if (d.deleted()) {
                l.delete(KindRows.key(d.ref().id()), null);
            } else {
                l.upsert(KindRows.row(d, key), null);
            }
        });
        List<String> paused = bindings.paused(kind);
        if (!paused.isEmpty()) {
            l.state(false, String.join("; ", paused));
        }
        return h;
    }
}
