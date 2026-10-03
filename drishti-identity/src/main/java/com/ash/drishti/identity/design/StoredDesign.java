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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * One Design of the Build workbench, as kept: who owns it, the Sutra being designed (YAML text and revision), the index of
 * its samples (the sample documents themselves are kept apart, see {@link DesignStore}), its tests and notes. A plain
 * mutable bean for Jackson; the service always works on a fresh copy read from the store, so a bean is never shared.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredDesign {

    /** A sample's kind: a document you brought, a reference to a stored entity (re-read each time), or generated from a schema. */
    public static final String DOCUMENT = "document";
    public static final String REF = "ref";
    public static final String SYNTHETIC = "synthetic";

    /**
     * One entry of the sample index.
     *
     * @param name the sample's name (a file name, or {@code kind id} for a reference)
     * @param type {@link #DOCUMENT}, {@link #REF} or {@link #SYNTHETIC}
     * @param refKind for a reference, the entity kind
     * @param refId for a reference, the entity id
     * @param bytes the size of the kept document (0 for a reference)
     */
    public record SampleInfo(String name, String type, String refKind, String refId, long bytes) {}

    public String id;
    public String owner;
    /** Blank for a scratch design. */
    public String name = "";
    public boolean scratch = true;
    public String kind = "sample";
    /** {@code name@version} of the Sutra this Design edits, or null. */
    public String base;
    public List<SampleInfo> samples = new ArrayList<>();
    public String sutra = "";
    public int rev;
    public List<JsonNode> tests = new ArrayList<>();
    public List<JsonNode> ops = new ArrayList<>();
    public String notes = "";
    public String status = "draft";
    public long created;
    public long updated;

    public StoredDesign() {}

    /** A deep-enough copy: the lists are new, the immutable parts shared. */
    public StoredDesign copy() {
        StoredDesign c = new StoredDesign();
        c.id = id;
        c.owner = owner;
        c.name = name;
        c.scratch = scratch;
        c.kind = kind;
        c.base = base;
        c.samples = new ArrayList<>(samples);
        c.sutra = sutra;
        c.rev = rev;
        c.tests = new ArrayList<>(tests);
        c.ops = new ArrayList<>(ops);
        c.notes = notes;
        c.status = status;
        c.created = created;
        c.updated = updated;
        return c;
    }

    /** The bytes of kept sample documents. */
    public long sampleBytes() {
        long n = 0;
        for (SampleInfo s : samples) {
            n += s.bytes();
        }
        return n;
    }

    public SampleInfo sample(String sampleName) {
        for (SampleInfo s : samples) {
            if (s.name().equals(sampleName)) {
                return s;
            }
        }
        return null;
    }
}
