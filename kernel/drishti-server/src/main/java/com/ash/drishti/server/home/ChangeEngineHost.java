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

import com.ash.drishti.kernel.api.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * The host of the {@code changes} engine (engines/changes, EN-45): it is handed the reference values, the current values, the events and
 * the attribution rows, all read as the person, and answers {@code home_changes} rows. {@code explain} is the engine's own explanation of
 * one row (the steps of {@code WHY <key>}).
 *
 * <p>TODO(R2): no engine-api host exists in the server yet, so {@link #NOT_HOSTED} is the only implementation and the Home's "Since" panel
 * says so in words. When R2 hosts engines, one bean here calls the engine with the tables below ({@code mode: request}) and nothing else changes.
 * The comparison is deliberately not written a second time in the server (one way per job).
 */
public interface ChangeEngineHost {

    /** Whether the engine runs on this node (its switch in {@code config/engines/changes.yaml} is on and it is hosted). */
    boolean available();

    /** Why it is not available, in words for the panel. */
    String reason();

    List<HomeData.Change> changes(Principal p, List<HomeData.MeasureValue> then, List<HomeData.MeasureValue> now, List<HomeDefinition.Follow> thresholds,
            List<HomeData.Event> events, List<HomeData.Driver> drivers, int limit);

    /** The engine's explanation of one change: the move and each driver's share as steps (label and number). */
    List<Map<String, Object>> explain(Principal p, HomeData.Change change, List<HomeData.Driver> drivers);

    /** The host there is until R2. */
    ChangeEngineHost NOT_HOSTED = new ChangeEngineHost() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String reason() {
            return "the changes engine is not hosted on this node yet";
        }

        @Override
        public List<HomeData.Change> changes(Principal p, List<HomeData.MeasureValue> then, List<HomeData.MeasureValue> now,
                List<HomeDefinition.Follow> thresholds, List<HomeData.Event> events, List<HomeData.Driver> drivers, int limit) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> explain(Principal p, HomeData.Change change, List<HomeData.Driver> drivers) {
            return List.of();
        }
    };
}
