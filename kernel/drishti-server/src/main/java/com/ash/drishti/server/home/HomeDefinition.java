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

import java.util.List;
import java.util.Map;

/**
 * One Home: the file {@code <home>.home.yaml} and the {@code <home>.measures.yaml} it names (ROLE_HOME_SUTRAS.md 1 and 4). The screen
 * is a Sutra of library fragments only.
 *
 * @param name the Home's name (also the Sutra's name, {@code home-<name>})
 * @param modules the modules that must be on for the Home to be offered
 * @param sutra the Sutra file beside the Home file
 * @param arrange {@code allow} or {@code locked}: whether a person may arrange their own Home (a locked Home ignores a stored arrangement)
 * @param requires mnemonics a Home needs at least one of to be offered (empty: always); panels carry their own {@code requires:}
 * @param measures what the Home follows
 */
public record HomeDefinition(String name, String title, String description, List<String> modules, String sutra, boolean arrange,
        List<String> requires, Measures measures, String sutraText) {

    public HomeDefinition {
        modules = modules == null ? List.of() : List.copyOf(modules);
        requires = requires == null ? List.of() : List.copyOf(requires);
    }

    /** The Sutra's name: what the person's arrangement is kept under. */
    public String sutraName() {
        return "home-" + name;
    }

    /** One KPI of the strip: answered by {@code engine} now, its history kept {@code history} days. */
    public record Kpi(String label, String engine, String measure, Map<String, Object> args, String fmt, int history) {}

    /** One measure the change detection compares by subject. */
    public record Follow(String measure, String engine, String subjects, double minAbs, double minRel, List<String> drivers) {
        public Follow {
            drivers = drivers == null ? List.of() : List.copyOf(drivers);
        }
    }

    /** A rule of "Exceptions first". {@code when} is {@code MEASURE >= number} or a state word ({@code load-failed}, {@code feed-late}). */
    public record Rule(String when, String severity, String text, String open) {}

    /** What the Home follows: KPIs, measures, event kinds, exception rules, the quick mnemonics; the reference cut-off ({@code HH:mm}, null: the site's). */
    public record Measures(String cutoff, List<Kpi> kpis, List<Follow> follow, List<String> events, List<Rule> exceptions,
            List<Map<String, String>> mnemonics) {
        public Measures {
            kpis = kpis == null ? List.of() : List.copyOf(kpis);
            follow = follow == null ? List.of() : List.copyOf(follow);
            events = events == null ? List.of() : List.copyOf(events);
            exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
            mnemonics = mnemonics == null ? List.of() : List.copyOf(mnemonics);
        }
    }
}
