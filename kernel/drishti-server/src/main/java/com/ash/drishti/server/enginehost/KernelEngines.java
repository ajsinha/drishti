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
package com.ash.drishti.server.enginehost;

import com.ash.drishti.server.engines.EngineRegistry;
import com.ash.drishti.enginehost.EngineHost;
import com.ash.drishti.enginehost.HostedEngine;
import com.ash.drishti.kernel.api.services.Engines;
import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.kernel.api.services.Person;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.security.Entitlements;
import com.ash.engine.Answer;
import com.ash.engine.EngineException;
import com.ash.engine.RequestClass;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The kernel's engines service ({@link Engines}): a module's in-process ask, answered exactly as {@code POST /api/v1/engines/{id}/output}
 * answers it ({@link EngineHostController}): the caller's access, row rules and masks, the engine registry's gate, the plain shape.
 */
@Component
public class KernelEngines implements Engines {

    private final EngineHost host;
    private final Entitlements entitlements;
    private final PackRegistry packs;
    private final EngineRegistry registry;

    public KernelEngines(EngineHost host, Entitlements entitlements, PackRegistry packs, EngineRegistry registry) {
        this.host = host;
        this.entitlements = entitlements;
        this.packs = packs;
        this.registry = registry;
    }

    @Override
    public boolean running(String engine) {
        return host.engine(engine).map(e -> "running".equals(e.state())).orElse(false);
    }

    @Override
    public Map<String, Object> ask(Person p, String engine, List<String> outputs, Map<String, Object> params, Map<String, String> snapshot) {
        return ask(com.ash.drishti.server.kernelapi.RequestPerson.of(p).principal(), engine, outputs, params, snapshot);
    }

    @Override
    public Map<String, Object> ask(com.ash.drishti.kernel.api.security.Principal p, String engine, List<String> outputs, Map<String, Object> params,
            Map<String, String> snapshot) {
        try {
            HostedEngine h = host.require(engine);
            try (AutoCloseable counted = registry.known(engine) ? registry.enter(engine) : () -> { }) {
                Answer a = h.ask(new HostedEngine.Ask(outputs, params, snapshot, RequestClass.INTERACTIVE, false),
                        new PrincipalAccess(p, entitlements, packs));
                return com.ash.drishti.enginehost.AnswerShapes.plain(a);
            }
        } catch (EngineException e) {
            throw new Kernel.Refused(EngineHostController.status(e).value(), e.code(), e.getMessage());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new Kernel.Refused(500, "ENG-9000", engine + " failed: " + e.getMessage());
        }
    }
}
