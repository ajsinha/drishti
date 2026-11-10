/*
 * Copyright (c) the publisher of genomics. All rights reserved.
 */
package org.helixlab.genomics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The module's Spring configuration: its beans live in a child context of the kernel's, closed when the module unloads. */
@Configuration
public class ModuleConfig {

    @Bean
    public Greeting greeting() {
        return new Greeting();
    }

    /** The kind it computes: the kernel routes {@code genomics-greeting} to it while the module is loaded. */
    @Bean
    public GreetingKinds greetingKinds(Greeting greeting) {
        return new GreetingKinds(greeting);
    }

    /** The Rachana function it adds: {@code genomics_greet(name)}. */
    @Bean
    public GreetingFunctions greetingFunctions(Greeting greeting) {
        return new GreetingFunctions(greeting);
    }

    /** Its REST API, under /api/v1/genomics/. */
    @Bean
    public GreetingController greetingController(Greeting greeting) {
        return new GreetingController(greeting);
    }
}
