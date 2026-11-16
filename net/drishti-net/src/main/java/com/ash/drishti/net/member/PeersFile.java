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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code <data dir>/net/peers.json}: this node's id and the members it knows with their last-known state, saved every
 * {@code persist-every}, on every join or leave and at shutdown, and read at start to rejoin with no seeds. Written atomically
 * (a temporary file in the same folder moved over the target, as the lake tools write {@code last-run.json}); a file that cannot
 * be read is ignored with a warning, never fatal. What gossip learns is state, so it lives here and never in {@code config/net/}.
 */
public final class PeersFile {

    private static final Logger LOG = LoggerFactory.getLogger(PeersFile.class);

    /** The file's content. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Saved(String nodeId, long savedAt, Member self, List<Member> members) {
        public Saved {
            members = members == null ? List.of() : List.copyOf(members);
        }

        /** The urls of the saved members to try when rejoining: alive ones first, then suspect, never left, never a loopback address. */
        public List<String> rejoinUrls() {
            List<Member> m = new ArrayList<>(members.stream().filter(x -> x.state() != MemberState.LEFT && !x.url().isBlank()
                    && com.ash.drishti.net.config.HostAddress.callableUrl(x.url())).toList());
            m.sort(Comparator.comparing(x -> x.state() == MemberState.ALIVE ? 0 : x.state() == MemberState.SUSPECT ? 1 : 2));
            return m.stream().map(Member::url).distinct().toList();
        }
    }

    private final Path file;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ReentrantLock lock = new ReentrantLock();

    public PeersFile(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public Path path() {
        return file;
    }

    /** Saves the table now; a failure is logged and the previous file is left as it was. */
    public boolean save(MemberTable table, long now) {
        List<Member> entries = table.entries();
        Saved s = new Saved(entries.get(0).nodeId(), now, entries.get(0), entries.subList(1, entries.size()));
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = com.ash.drishti.common.TempFiles.createFile(file.getParent(), "peers-", ".tmp");
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

    /** The saved table, or empty when there is none; a corrupt file is ignored with a warning (the node then joins from its seeds). */
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
