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
package com.ash.drishti.api.lifecycle;

import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;

/** {@code 409 DRS-1036}: the part's state does not allow the transition; nothing was changed. The words name the part, its state and what is allowed. */
public final class LifecycleRefused extends DrishtiException {

    private static final long serialVersionUID = 1L;
    private final Lifecycle.Type type;
    private final String name;
    private final LifecycleState state;
    private final Transition transition;

    public LifecycleRefused(Lifecycle.Type type, String name, LifecycleState state, Transition transition) {
        super(ErrorCode.LIFECYCLE_REFUSED, type.word() + " " + name + " is " + state.word() + "; " + transition.word() + " is not allowed now ("
                + allowed(state) + ")");
        this.type = type;
        this.name = name;
        this.state = state;
        this.transition = transition;
    }

    /** A refusal with the part's own words (an engine a pack needs is not running, a pack another one extends). */
    public LifecycleRefused(Lifecycle.Type type, String name, LifecycleState state, Transition transition, String words) {
        super(ErrorCode.LIFECYCLE_REFUSED, type.word() + " " + name + ": " + transition.word() + " refused: " + words);
        this.type = type;
        this.name = name;
        this.state = state;
        this.transition = transition;
    }

    static String allowed(LifecycleState s) {
        java.util.List<String> ok = new java.util.ArrayList<>();
        for (Transition t : Transition.values()) {
            if (s.allows(t) && !(s == LifecycleState.SHUTDOWN && t == Transition.SHUTDOWN)) {
                ok.add(t.word());
            }
        }
        return ok.isEmpty() ? "it is shut down: a new instance starts with init" : "allowed: " + String.join(", ", ok);
    }

    public Lifecycle.Type type() {
        return type;
    }

    public String name() {
        return name;
    }

    public LifecycleState state() {
        return state;
    }

    public Transition transition() {
        return transition;
    }
}
