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
package com.ash.drishti.server;

import com.ash.drishti.engine.EngineConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * The Drishti backend. Drishti ships only as this Spring Boot application; it is never embedded as a library.
 * Each module contributes its beans through its own {@code @Configuration} class, imported here.
 */
// The MongoDB driver is on the class path for the mongodb plugin, which makes its own client only when configured;
// Spring Boot's MongoDB auto-configuration would otherwise open an unused client to localhost:27017 on every start
@SpringBootApplication(excludeName = "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration")
@Import({EngineConfiguration.class, com.ash.drishti.server.security.SecurityConfiguration.class,
        com.ash.drishti.identity.IdentityConfiguration.class})
public class DrishtiApplication {

    /**
     * Runs the server; when an administrator loads or unloads a pack ({@link Restarter}), closes it and runs it again in
     * the same process, so the new configuration (the pack overlay) is read as at start-up.
     */
    public static void main(String[] args) throws InterruptedException {
        Restarter.enable();
        while (true) {
            org.springframework.context.ConfigurableApplicationContext ctx;
            try {
                ctx = SpringApplication.run(DrishtiApplication.class, args);
            } catch (RuntimeException e) {
                Runnable undo = Restarter.takeUndo();         // a restart that failed: put the configuration back, start again
                if (undo == null) {
                    throw e;
                }
                undo.run();
                continue;
            }
            Restarter.takeUndo();                           // started: the change is kept
            Restarter.await();
            ctx.close();
        }
    }
}
