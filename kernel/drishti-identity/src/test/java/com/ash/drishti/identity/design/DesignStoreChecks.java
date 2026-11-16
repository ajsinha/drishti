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
package com.ash.drishti.identity.design;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.identity.design.StoredDesign.SampleInfo;
import java.util.List;

/** What every {@link DesignStore} must do the same way: the file store and the database store both run this. */
public final class DesignStoreChecks {

    private DesignStoreChecks() {}

    public static StoredDesign design(String owner, String id, String name) {
        StoredDesign d = new StoredDesign();
        d.owner = owner;
        d.id = id;
        d.name = name;
        d.scratch = name.isEmpty();
        d.sutra = "rachana: 1\nsutra: t\nversion: 1\n";
        d.rev = 1;
        d.notes = "# notes";
        d.created = d.updated = 1_000L;
        return d;
    }

    public static void roundTrip(DesignStore s) {
        StoredDesign d = design("ann", "a1b2c3d4e5f6", "Rates screen");
        d.samples.add(new SampleInfo("t1.json", StoredDesign.DOCUMENT, null, null, 14));
        d.samples.add(new SampleInfo("trade IRS-1", StoredDesign.REF, "trade", "IRS-1", 0));
        s.save(d);
        s.putSample("ann", d.id, "t1.json", "{\"tradeId\":\"T1\"}");
        s.save(design("bob", "ffffffffffff", "Bob's"));

        StoredDesign back = s.get("ann", d.id).orElseThrow();
        assertThat(back.name).isEqualTo("Rates screen");
        assertThat(back.sutra).isEqualTo(d.sutra);
        assertThat(back.samples).extracting(SampleInfo::name).containsExactly("t1.json", "trade IRS-1");
        assertThat(back.samples.get(1).refKind()).isEqualTo("trade");
        assertThat(s.sample("ann", d.id, "t1.json")).contains("{\"tradeId\":\"T1\"}");
        assertThat(s.sample("ann", d.id, "nope.json")).isEmpty();

        // owner isolation: the same id under another user is nothing
        assertThat(s.get("bob", d.id)).isEmpty();
        assertThat(s.sample("bob", d.id, "t1.json")).isEmpty();
        assertThat(s.list("ann")).extracting(x -> x.id).containsExactly(d.id);
        assertThat(s.list("bob")).hasSize(1);
        assertThat(s.users()).contains("ann", "bob");

        // replacing a sample, then removing it
        s.putSample("ann", d.id, "t1.json", "{\"tradeId\":\"T2\"}");
        assertThat(s.sample("ann", d.id, "t1.json")).contains("{\"tradeId\":\"T2\"}");
        s.removeSample("ann", d.id, "t1.json");
        assertThat(s.sample("ann", d.id, "t1.json")).isEmpty();

        // deleting a Design deletes its samples at once
        s.putSample("ann", d.id, "again.json", "{}");
        assertThat(s.delete("ann", d.id)).isTrue();
        assertThat(s.get("ann", d.id)).isEmpty();
        assertThat(s.sample("ann", d.id, "again.json")).isEmpty();
        assertThat(s.delete("ann", d.id)).isFalse();
        s.forget("bob");
        assertThat(s.list("bob")).isEqualTo(List.of());
    }
}
