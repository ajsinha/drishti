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
package com.ash.drishti.server.modules;

import com.ash.drishti.packs.Modules;
import com.ash.drishti.registry.ModuleState;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** {@link ConditionalOnModule}: asks the environment's access registry whether the module is on. */
final class OnModuleCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(ConditionalOnModule.class.getName());
        String id = attrs == null ? "" : String.valueOf(attrs.get("value"));
        ModuleState s = Modules.of(context.getEnvironment()).module(id).orElse(null);
        if (s == null) {
            return ConditionOutcome.noMatch("the module '" + id + "' is not known here (no manifest on the class path)");
        }
        return s.on() ? ConditionOutcome.match("the module '" + id + "' is on") : ConditionOutcome.noMatch("the module '" + id + "' is off: " + s.reason());
    }
}
