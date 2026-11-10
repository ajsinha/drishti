/*
 * Copyright (c) the publisher of genomics. All rights reserved.
 */
package org.helixlab.genomics;

import com.ash.drishti.kernel.api.function.FunctionProvider;
import java.util.List;

/** The function this module adds to Rachana: {@code genomics_greet(name)} in any expression while the module is loaded. */
public class GreetingFunctions implements FunctionProvider {

    private final Greeting greeting;

    public GreetingFunctions(Greeting greeting) {
        this.greeting = greeting;
    }

    @Override
    public String source() {
        return "the genomics module";
    }

    @Override
    public List<Provided> functions() {
        return List.of(new Provided("genomics_greet", 0, 1, "[name]", "the module's greeting for a name",
                args -> greeting.answer(args.isEmpty() || args.get(0) == null ? null : String.valueOf(args.get(0)))));
    }
}
