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
package com.ash.drishti.engine.source;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * {@code drishti.offline.*} ({@code DRISHTI_OFFLINE}): a server that must never reach the internet, such as a demonstration machine with
 * no network. While it is on, no connector of an internet plugin starts (the plugins in {@code drishti.offline.plugins}, by default the
 * news, yfinance and feed plugins, whose data comes from public web services), whatever its file says: the connector is reported as
 * {@code OFFLINE} ("off (offline mode)" in Admin, Connectors), never tries the network, never times out and never turns health red. Its
 * kinds are then served by the other connectors that hold them, such as a Delta store built from a captured snapshot (the
 * news-and-markets pack's {@code news-store}). The same holds with offline mode off for such a connector whose file switches it off (its
 * shipped state, such as {@code DRISHTI_NEWS_ENABLED} unset): Admin, Connectors says "off (served from the snapshot)" when another
 * running connector serves its kinds ({@link SourceRegistry#standIns}).
 *
 * @param enabled whether offline mode is on
 * @param plugins the plugins whose connectors reach the internet (lower case)
 */
public record OfflineMode(boolean enabled, Set<String> plugins) {

    /** The state a connector of an internet plugin is reported in while offline mode is on. */
    public static final String STATE = "OFFLINE";

    /** The plugins that reach the internet unless {@code drishti.offline.plugins} names others. */
    public static final String DEFAULT_PLUGINS = "news,yfinance,feed";

    /** Offline mode off: every connector starts as its file says. */
    public static final OfflineMode OFF = new OfflineMode(false, Set.of());

    public OfflineMode {
        plugins = plugins == null ? Set.of() : Set.copyOf(plugins);
    }

    /** Read from the configuration: {@code drishti.offline.enabled} and {@code drishti.offline.plugins} (a comma-separated list). */
    public static OfflineMode of(UnaryOperator<String> property) {
        String on = property.apply("drishti.offline.enabled");
        String list = property.apply("drishti.offline.plugins");
        boolean enabled = on != null && Set.of("true", "on", "yes", "1").contains(on.trim().toLowerCase(Locale.ROOT));
        Set<String> names = Arrays.stream((list == null || list.isBlank() ? DEFAULT_PLUGINS : list).split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        return new OfflineMode(enabled, names);
    }

    /** True when offline mode keeps a connector of {@code plugin} from starting. */
    public boolean blocks(String plugin) {
        return enabled && plugin != null && plugins.contains(plugin.toLowerCase(Locale.ROOT));
    }

    /**
     * True when {@code plugin} reaches the internet (it is in {@code drishti.offline.plugins}), whether or not offline mode is on: such a
     * connector that is switched off has its kinds served by the other connectors that hold them, exactly as in offline mode
     * ({@link SourceRegistry#standIns}).
     */
    public boolean reachesInternet(String plugin) {
        return plugin != null && plugins.contains(plugin.toLowerCase(Locale.ROOT));
    }

    /** Why the connector is not running, as Admin and the logs say it. */
    public String reason(String plugin) {
        return "offline mode (DRISHTI_OFFLINE=true): the " + plugin + " plugin reaches the internet, so it is not started; its kinds are "
                + "served by the other connectors that hold them";
    }
}
