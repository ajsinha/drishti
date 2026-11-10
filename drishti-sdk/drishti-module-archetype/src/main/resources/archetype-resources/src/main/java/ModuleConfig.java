/*
 * Copyright (c) the publisher of ${artifactId}. All rights reserved.
 */
package ${package};

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The module's Spring configuration: its beans live in a child context of the kernel's, closed when the module unloads. */
@Configuration
public class ModuleConfig {

    @Bean
    public Greeting greeting() {
        return new Greeting();
    }
}
