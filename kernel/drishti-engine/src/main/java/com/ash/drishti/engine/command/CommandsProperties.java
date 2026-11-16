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
package com.ash.drishti.engine.command;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.commands.*}: the command line.
 *
 * @param mnemonics mnemonic ({@code TRD}) to the kind it opens and a label for the suggestion dropdown
 * @param suggestLimit most suggestions returned
 * @param suggestBudget how long type-ahead waits for sources before answering with what it has
 * @param recentSize recently opened entities remembered per user
 */
@ConfigurationProperties("drishti.commands")
public record CommandsProperties(Map<String, Mnemonic> mnemonics, Integer suggestLimit, Duration suggestBudget, Integer recentSize) {

    public CommandsProperties {
        mnemonics = mnemonics == null ? Map.of() : Map.copyOf(mnemonics);
        suggestLimit = suggestLimit == null ? 25 : suggestLimit;
        suggestBudget = suggestBudget == null ? Duration.ofMillis(30) : suggestBudget;
        recentSize = recentSize == null ? 20 : recentSize;
    }

    /**
     * @param kind the entity kind it opens
     * @param label a short description ({@code Trade})
     */
    public record Mnemonic(String kind, String label) {}
}
