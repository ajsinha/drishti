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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * For a test that builds its own modules folder (a drop-in deploy, a module host over fixture modules): the install's modules home is empty
 * while its server starts, so only the test's own modules are there. The property is put back when the class ends; a server of another test
 * class (its own Spring context) reads the shipped packages again.
 */
public final class EmptyModulesHome implements BeforeAllCallback, org.junit.jupiter.api.extension.AfterAllCallback {

    private static final String KEY = "drishti.modules.home";
    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(EmptyModulesHome.class);

    @Override
    public void beforeAll(ExtensionContext context) throws IOException {
        context.getStore(NS).put("before", System.getProperty(KEY));
        Path empty = Files.createTempDirectory(Path.of(System.getProperty("drishti.test.dir", System.getProperty("java.io.tmpdir"))), "empty-modules-");
        context.getStore(NS).put("folder", empty);
        System.setProperty(KEY, empty.toString());
    }

    @Override
    public void afterAll(ExtensionContext context) throws IOException {
        String before = context.getStore(NS).get("before", String.class);
        if (before == null) {
            System.clearProperty(KEY);
        } else {
            System.setProperty(KEY, before);
        }
        Files.deleteIfExists(context.getStore(NS).get("folder", Path.class));
    }
}
