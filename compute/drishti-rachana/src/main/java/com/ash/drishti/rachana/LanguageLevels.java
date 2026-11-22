/*
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary: see LICENSE.
 */
package com.ash.drishti.rachana;

import com.ash.rachana.parse.SutraParser;
import com.ash.rachana.profile.Grammar;
import com.ash.rachana.tools.Upgrade;

/**
 * The language level of a Sutra or fragment against the server's ({@link DrishtiRachana#level()}): every shipped one declares the
 * server's level; a user's Sutra written for an older level still compiles at its own level, and the designer and {@code drishti.py
 * sutra lint} say so and offer {@link #upgrade} (Rachana's {@code rachana upgrade}: the same meaning, every place to review flagged).
 */
public final class LanguageLevels {

    private LanguageLevels() {
    }

    /**
     * The level the text declares ({@code rachana: 1} is 1.0, {@code rachana: 1.2} is 1.2), 1.0 for a fragment without the key, or null
     * when it declares none a Sutra can have (the compile says why).
     */
    public static String declared(String text) {
        if (text == null) {
            return null;
        }
        String l = SutraParser.declaredLevel(text);
        if (l == null) {
            return null;
        }
        return Grammar.MAJOR_ONLY.equals(l) ? Grammar.LEVEL_1_0 : l;
    }

    /** Whether the text declares a level below the server's (and so is read at that older level). */
    public static boolean older(String text) {
        String d = declared(text);
        return d != null && !d.equals(DrishtiRachana.level()) && Grammar.LEVELS.indexOf(d) >= 0
                && Grammar.LEVELS.indexOf(d) < Grammar.LEVELS.indexOf(DrishtiRachana.level());
    }

    /** One line that says what to do about a Sutra of an older level, or null when it is at the server's. */
    public static String advice(String text, String file) {
        if (!older(text)) {
            return null;
        }
        return "written for Rachana language level " + declared(text) + "; this server runs " + DrishtiRachana.level()
                + " and reads it at " + declared(text) + ": upgrade it with `drishti.py sutra upgrade " + (file == null ? "<file>" : file)
                + " --write` (or Upgrade in the designer), then review the flagged places";
    }

    /**
     * A Sutra the server writes (inference, auto-design: Core's writer declares the major only, level 1.0) at the server's level, upgraded
     * with the same meaning; the text as it was when the upgrade refuses it.
     */
    public static String written(String text) {
        if (!older(text)) {
            return text;
        }
        Upgrade.Result r = upgrade("written.sutra.yaml", text);
        return r.writable() ? r.text() : text;
    }

    /** The text upgraded to the server's level with the server's functions (Core's and the profile's); nothing is written. */
    public static Upgrade.Result upgrade(String file, String text) {
        return Upgrade.upgrade(file, text, DrishtiRachana.level(), DrishtiRachana.current().functions());
    }
}
