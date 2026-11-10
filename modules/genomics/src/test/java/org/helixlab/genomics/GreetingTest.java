/*
 * Copyright (c) the publisher of genomics. All rights reserved.
 */
package org.helixlab.genomics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GreetingTest {

    @Test
    void greetsTheNameItIsGiven() {
        assertThat(new Greeting().answer("Asha")).isEqualTo("Hello, Asha, from the genomics module");
        assertThat(new Greeting().answer(" ")).startsWith("Hello, world");
    }
}
