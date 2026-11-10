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
package com.ash.drishti.sdk;

import com.ash.drishti.module.runtime.Dmod;
import com.ash.drishti.module.runtime.DmodManifest;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** {@code conformance}: the fifteen checks of section 8.6 on the package; a failure stops the build and says what to change. */
@Mojo(name = "conformance", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true, requiresDependencyResolution = ResolutionScope.TEST)
public final class ConformanceMojo extends ModuleMojo {

    /** The load/unload cycles of C15 (the design's 50). */
    @Parameter(property = "drishti.conformance.cycles", defaultValue = "50")
    int cycles;

    /** Skips the checks (never for a release build). */
    @Parameter(property = "drishti.conformance.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoFailureException {
        if (skip) {
            getLog().warn("conformance checks skipped: this package cannot be signed for release");
            return;
        }
        DmodManifest m = ModuleAssembler.manifest(source.toPath());
        Path dmod = target().resolve("dmod").resolve(Dmod.fileName(m.id(), m.version()));
        if (!dmod.toFile().isFile()) {
            throw new MojoFailureException(dmod + " is not there: run the package phase first (mvn verify runs it)");
        }
        List<ModuleConformance.Check> checks = ModuleConformance.run(dmod, project.getBasedir().toPath(), target().resolve("conformance"),
                cycles);
        checks.forEach(c -> getLog().info(c.line()));
        long failed = checks.stream().filter(c -> c.status() == ModuleConformance.Status.FAIL).count();
        if (failed > 0) {
            throw new MojoFailureException(failed + " of " + checks.size() + " conformance checks failed:\n" + String.join("\n",
                    checks.stream().filter(c -> c.status() == ModuleConformance.Status.FAIL).map(ModuleConformance.Check::line).toList()));
        }
        getLog().info("module " + m.label() + ": all " + checks.size() + " conformance checks passed");
    }
}
