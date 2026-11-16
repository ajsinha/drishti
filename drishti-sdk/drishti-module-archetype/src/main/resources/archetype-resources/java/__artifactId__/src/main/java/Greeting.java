/*
 * Copyright (c) the publisher of ${artifactId}. All rights reserved.
 */
package ${package};

/** The function ${artifactId.toUpperCase()}: a greeting for a name. A bean of the module's own Spring context, no state of its own. */
public class Greeting {

    /** The greeting for {@code name}. */
    public String answer(String name) {
        String who = name == null || name.isBlank() ? "world" : name.trim();
        return "Hello, " + who + ", from the ${artifactId} module";
    }
}
