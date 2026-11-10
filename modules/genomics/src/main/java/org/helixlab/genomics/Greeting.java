/*
 * Copyright (c) the publisher of genomics. All rights reserved.
 */
package org.helixlab.genomics;

/** The function GENOMICS: a greeting for a name. A bean of the module's own Spring context, no state of its own. */
public class Greeting {

    /** The greeting for {@code name}. */
    public String answer(String name) {
        String who = name == null || name.isBlank() ? "world" : name.trim();
        return "Hello, " + who + ", from the genomics module";
    }
}
