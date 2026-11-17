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
package com.ash.drishti.it;

import com.ash.drishti.testroots.TestRoots;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The few tests that still serve the repository's module SOURCES (drishti.modules.home = modules/, packages off) while the module path cannot
 * give them what they read: the economy packs' samples through the package (MapPanelTest, the equivalence and explain tests of world-economy)
 * and the module host test's own folder beside the shipped modules. I8 removes this class
 * when the packages serve them. Properties are put back when the class ends.
 */
public final class ModuleSources implements BeforeAllCallback, AfterAllCallback {

    private static final String[] KEYS = {"drishti.modules.home", "drishti.modules.packages"};
    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(ModuleSources.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        for (String k : KEYS) {
            String v = System.getProperty(k);
            context.getStore(NS).put(k, v == null ? "\u0000" : v);
        }
        System.setProperty(KEYS[0], java.nio.file.Path.of(TestRoots.repo(), "modules").toAbsolutePath().normalize().toString());
        System.setProperty(KEYS[1], "false");
    }

    @Override
    public void afterAll(ExtensionContext context) {
        for (String k : KEYS) {
            String v = context.getStore(NS).get(k, String.class);
            if ("\u0000".equals(v)) {
                System.clearProperty(k);
            } else {
                System.setProperty(k, v);
            }
        }
    }
}
