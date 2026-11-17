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
package com.ash.drishti.server.kernelapi;

import com.ash.drishti.kernel.api.module.InlineFigures;
import com.ash.drishti.kernel.api.services.CellSet;
import com.ash.drishti.kernel.api.services.InlineRequest;
import com.ash.drishti.kernel.api.services.Person;
import com.ash.drishti.kernel.api.services.Pivots;
import com.ash.drishti.module.runtime.InlinePivotFigures;
import com.ash.drishti.server.pivot.PivotInline;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@link Pivots} in process: the body of {@code POST /api/v1/pivot/inline}. */
@Component
public class KernelPivots implements Pivots {

    static final String PATH = "/api/v1/pivot/inline";

    private final PivotInline engine;
    private final KernelDoors doors;

    public KernelPivots(PivotInline engine, KernelDoors doors) {
        this.engine = engine;
        this.doors = doors;
    }

    @Override
    public CellSet inline(Person person, InlineRequest request) {
        return new CellSet(answer(RequestPerson.of(person), body(request)));
    }

    @Override
    public InlineFigures.Figures figures(Person person, List<InlineFigures.Field> fields, List<List<Object>> rows, List<Map<String, Object>> values,
            String by, String across, Map<String, Object> top) {
        RequestPerson p = RequestPerson.of(person);
        return InlinePivotFigures.ask(b -> answer(p, b), fields, rows, values, by, across, top);
    }

    private Map<String, Object> answer(RequestPerson p, Map<String, Object> body) {
        return doors.call(p, "POST", PATH, () -> engine.inline(p.principal(), body));
    }

    /** The request as the REST body says it: {@code {fields: [{name, type, label}], rows, view}} (a label only where it is not the name). */
    static Map<String, Object> body(InlineRequest r) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (InlineRequest.Field f : r.fields()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("type", f.type());
            if (!f.label().equals(f.name())) {
                m.put("label", f.label());
            }
            fields.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fields", fields);
        body.put("rows", r.rows());
        body.put("view", r.view());
        return body;
    }
}
