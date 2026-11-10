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

import java.util.List;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/** {@code validate}: the manifest reads and names the project's id and version. */
@Mojo(name = "validate", defaultPhase = LifecyclePhase.VALIDATE, threadSafe = true)
public final class ValidateMojo extends ModuleMojo {

    @Override
    public void execute() throws MojoFailureException {
        List<String> problems = ModuleAssembler.validate(source.toPath(), moduleId, project.getVersion());
        if (!problems.isEmpty()) {
            throw new MojoFailureException("the module does not validate:\n  - " + String.join("\n  - ", problems));
        }
        getLog().info("module " + moduleId + " " + project.getVersion() + ": module.yaml is valid");
    }
}
