/*
 * Copyright (c) the publisher of ${artifactId}. All rights reserved.
 */
package ${package};

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GreetingTest {

    @Test
    void greetsTheNameItIsGiven() {
        assertThat(new Greeting().answer("Asha")).isEqualTo("Hello, Asha, from the ${artifactId} module");
        assertThat(new Greeting().answer(" ")).startsWith("Hello, world");
    }
}
