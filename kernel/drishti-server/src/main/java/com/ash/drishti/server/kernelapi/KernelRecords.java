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

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.kernel.api.services.Person;
import com.ash.drishti.kernel.api.services.RawRecord;
import com.ash.drishti.kernel.api.services.Records;
import com.ash.drishti.kernel.api.services.SearchAnswer;
import com.ash.drishti.server.api.AccessRecorder;
import com.ash.drishti.server.api.ApiDtos;
import com.ash.drishti.server.api.RecordReads;
import com.ash.drishti.server.api.Searches;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** {@link Records} in process: the bodies of {@code GET /api/v1/search} and {@code GET /api/v1/entities/{kind}/{id}/raw}. */
@Component
public class KernelRecords implements Records {

    private final Searches searches;
    private final RecordReads reads;
    private final KernelDoors doors;
    private final ObjectMapper json;
    private final ObjectProvider<AccessRecorder> access;

    public KernelRecords(Searches searches, RecordReads reads, KernelDoors doors, ObjectMapper json, ObjectProvider<AccessRecorder> access) {
        this.searches = searches;
        this.reads = reads;
        this.doors = doors;
        this.json = json;
        this.access = access;
    }

    @Override
    public SearchAnswer search(Person person, String query) {
        RequestPerson p = RequestPerson.of(person);
        Map<String, Object> m = doors.call(p, "GET", "/api/v1/search", () -> searches.search(query, p.asOf(), p.principal()));
        SearchAnswer a = json.convertValue(m, SearchAnswer.class);
        recorded(p, "search", null, null, query);
        return a;
    }

    @Override
    public Optional<RawRecord> raw(Person person, EntityRef ref) {
        RequestPerson p = RequestPerson.of(person);
        String path = "/api/v1/entities/" + ref.kind() + "/" + URLEncoder.encode(ref.id(), StandardCharsets.UTF_8).replace("+", "%20") + "/raw";
        ApiDtos.RawEntity r;
        try {
            r = doors.call(p, "GET", path, () -> reads.raw(ref.kind(), ref.id(), p.asOf(), p.principal()));
        } catch (Kernel.Refused e) {
            if (e.code().equals(ErrorCode.ENTITY_NOT_FOUND.code())) {
                return Optional.empty();                                    // no source holds it: the REST path's 404
            }
            throw e;
        }
        recorded(p, "raw", ref.kind(), ref.id(), null);
        return Optional.of(new RawRecord(ref, r.provenance(), r.data()));
    }

    private void recorded(RequestPerson p, String action, String kind, String id, String detail) {
        AccessRecorder a = access.getIfAvailable();
        if (a != null) {
            a.record(p.principal(), action, kind, id, detail, p.dateBox());
        }
    }
}
