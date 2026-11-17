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
import com.ash.drishti.kernel.api.services.History;
import com.ash.drishti.kernel.api.services.Person;
import com.ash.drishti.kernel.api.services.Series;
import com.ash.drishti.server.api.AccessRecorder;
import com.ash.drishti.server.api.HistoryController;
import com.ash.drishti.server.api.RecordReads;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** {@link History} in process: the body of {@code GET /api/v1/history/{kind}/{id}/series}. */
@Component
public class KernelHistory implements History {

    private final RecordReads reads;
    private final KernelDoors doors;
    private final ObjectProvider<AccessRecorder> access;

    public KernelHistory(RecordReads reads, KernelDoors doors, ObjectProvider<AccessRecorder> access) {
        this.reads = reads;
        this.doors = doors;
        this.access = access;
    }

    @Override
    public Series series(Person person, EntityRef ref, String path, int days, LocalDate to) {
        RequestPerson p = RequestPerson.of(person);
        String rest = "/api/v1/history/" + ref.kind() + "/" + URLEncoder.encode(ref.id(), StandardCharsets.UTF_8).replace("+", "%20") + "/series";
        HistoryController.Series s = doors.call(p, "GET", rest,
                () -> reads.series(ref.kind(), ref.id(), path, days, to == null ? null : to.toString(), p.asOf(), p.principal()));
        AccessRecorder a = access.getIfAvailable();
        if (a != null) {
            a.record(p.principal(), "history", ref.kind(), ref.id(), path, p.dateBox());
        }
        return new Series(ref, s.path(), s.label(), s.points().stream()
                .map(x -> new Series.Point(x.date(), x.value(), x.dataDate(), x.source(), x.generation())).toList(), s.dated());
    }
}
