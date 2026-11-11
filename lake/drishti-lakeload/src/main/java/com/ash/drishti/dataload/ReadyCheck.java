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
package com.ash.drishti.dataload;

/**
 * One finding about a file before it is pushed, in words, with the fix.
 *
 * @param status {@link #PASS}, {@link #WARNING} or {@link #REFUSED} (a refusal blocks Push)
 * @param text what was found
 * @param fix what to do about it (null: nothing to do)
 * @param field a form field a one-click fix sets (null: no such fix)
 * @param value the value it sets
 * @param tick true when Push needs this finding explicitly ticked
 */
public record ReadyCheck(String status, String text, String fix, String field, String value, boolean tick) {

    public static final String PASS = "pass";
    public static final String WARNING = "warning";
    public static final String REFUSED = "refused";

    public static ReadyCheck pass(String text) {
        return new ReadyCheck(PASS, text, null, null, null, false);
    }

    public static ReadyCheck warning(String text, String fix) {
        return new ReadyCheck(WARNING, text, fix, null, null, false);
    }

    public static ReadyCheck refused(String text, String fix) {
        return new ReadyCheck(REFUSED, text, fix, null, null, false);
    }

    /** The same finding with a one-click fix that sets {@code field} to {@code value}. */
    public ReadyCheck offering(String field, String value) {
        return new ReadyCheck(status, text, fix, field, value, tick);
    }

    /** The same finding, to be ticked before a push. */
    public ReadyCheck ticked() {
        return new ReadyCheck(status, text, fix, field, value, true);
    }
}
