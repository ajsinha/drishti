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
package com.ash.drishti.server.home;

import com.ash.drishti.common.Extensions;
import com.ash.drishti.kernel.api.home.HomeTable;
import com.ash.drishti.kernel.api.home.MeasureSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where the Homes find what modules contribute: the sources and tables of the server's own context, then those of the loaded modules (the
 * registration bridge keeps them in {@link Extensions} while a module is on, so they come and go with it). A module's object is looked up at
 * the moment it is needed, never kept.
 */
final class HomeSources {

    private HomeSources() {}

    /** The source of an engine: the server's own first, then a loaded module's; {@code null} when there is none. */
    static MeasureSource find(Map<String, MeasureSource> own, String engine) {
        MeasureSource s = own.get(engine);
        if (s != null) {
            return s;
        }
        for (MeasureSource m : Extensions.of(MeasureSource.class)) {
            if (m.engine().equals(engine)) {
                return m;
            }
        }
        return null;
    }

    /** Every source there is now. */
    static List<MeasureSource> all(Map<String, MeasureSource> own) {
        List<MeasureSource> out = new ArrayList<>(own.values());
        Extensions.of(MeasureSource.class).stream().filter(m -> !own.containsKey(m.engine())).forEach(out::add);
        return out;
    }

    /** The table of a name: the server's own beans first, then a loaded module's. */
    static HomeTable table(Map<String, HomeTable> own, String name) {
        HomeTable t = own.get(name);
        if (t != null) {
            return t;
        }
        for (HomeTable m : Extensions.of(HomeTable.class)) {
            if (m.name().equals(name)) {
                return m;
            }
        }
        return null;
    }
}
