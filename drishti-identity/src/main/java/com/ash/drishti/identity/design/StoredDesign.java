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
    /**
     * The operation log, oldest first: each entry is {@code {ops, before, after, at}} (the operations as JSON, the Sutra text
     * before and after, when). Undo and redo move {@link #opsAt} along it.
     */
    public List<JsonNode> ops = new ArrayList<>();
    /** How many log entries are applied: the entries from here on are what redo brings back. */
    public int opsAt;
    public String notes = "";
    public String status = "draft";
    /** A file under a Sutra directory this Design is bound to (development servers only), relative to that directory, or null. */
    public String boundFile;
    /** SHA-256 of the file text last written or read for {@link #boundFile}; a different hash on disk means the file was edited elsewhere. */
    public String boundSync;
    /** SHA-256 of the read-only share token, or null when the Design is not shared. */
    public String shareHash;
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
        c.opsAt = opsAt;
        c.notes = notes;
        c.status = status;
        c.boundFile = boundFile;
        c.boundSync = boundSync;
        c.shareHash = shareHash;
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
