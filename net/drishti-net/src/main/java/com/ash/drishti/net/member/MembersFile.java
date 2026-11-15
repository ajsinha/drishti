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
package com.ash.drishti.net.member;

import com.ash.drishti.net.config.ClusterSettings;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code <data dir>/net/members.json} (CLUSTER_I5_DESIGN.md section 4.1): this node's id, the last view and the members seen in the last
 * {@code forget-after}, written atomically on every view change and at shutdown, and read at start so a node rejoins from the members it
 * knew even with no seeds. A file that cannot be read is ignored with a warning, never fatal. It replaces the gossip-era
 * {@code peers.json}, which is not read.
 */
public final class MembersFile {

    private static final Logger LOG = LoggerFactory.getLogger(MembersFile.class);

    /** The file's content. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Saved(String nodeId, long savedAt, long viewId, Member self, List<Member> members) {
        public Saved {
            members = members == null ? List.of() : List.copyOf(members);
        }

        /** The cluster addresses of the saved members to dial when joining: not left, real addresses only. */
        public List<String> rejoinSeeds() {
            return members.stream().filter(m -> m.state() != MemberState.LEFT && !m.clusterAddress().isBlank())
                    .map(Member::clusterAddress).filter(a -> ClusterSettings.seedProblem(a).isEmpty()).distinct().toList();
        }
    }

    private final Path file;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ReentrantLock lock = new ReentrantLock();

    public MembersFile(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public Path path() {
        return file;
    }

    /** Saves the directory now; a failure is logged and the previous file is left as it was. */
    public boolean save(MemberDirectory members, long now) {
        List<Member> entries = members.entries();
        Saved s = new Saved(entries.get(0).nodeId(), now, members.viewId(), entries.get(0), entries.subList(1, entries.size()));
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = com.ash.drishti.common.TempFiles.createFile(file.getParent(), "members-", ".tmp");
            try {
                Files.writeString(tmp, json.writeValueAsString(s), StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("net: could not save {}: {}", file, e.toString());
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** The saved members, or empty when there is none; a file that cannot be read is ignored with a warning. */
    public Optional<Saved> read() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            Saved s = json.readValue(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
            if (s == null || s.nodeId() == null) {
                throw new IOException("no nodeId");
            }
            return Optional.of(s);
        } catch (IOException | RuntimeException e) {
            LOG.warn("net: {} cannot be read and is ignored ({}); the node joins from its seeds", file, e.getMessage());
            return Optional.empty();
        }
    }
}
