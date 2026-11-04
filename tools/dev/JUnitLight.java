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

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * Runs the named JUnit 5 test classes without Maven (tools/dev/junit_light.sh compiles them first): for a quick check of a few unit tests
 * while a release drill holds the machine. Prints the summary and each failure; exits 1 on a failure.
 */
public final class JUnitLight {

    private JUnitLight() {}

    public static void main(String[] args) throws Exception {
        LauncherDiscoveryRequestBuilder b = LauncherDiscoveryRequestBuilder.request();
        for (String c : args) {
            b.selectors(selectClass(Class.forName(c)));
        }
        LauncherDiscoveryRequest req = b.build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(req, listener);
        TestExecutionSummary s = listener.getSummary();
        s.printTo(new java.io.PrintWriter(System.out, true));
        s.printFailuresTo(new java.io.PrintWriter(System.out, true), 20);
        System.exit(s.getTotalFailureCount() == 0 && s.getTestsFoundCount() > 0 ? 0 : 1);
    }
}
