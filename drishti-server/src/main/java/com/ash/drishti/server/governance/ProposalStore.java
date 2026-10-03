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
package com.ash.drishti.server.governance;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Proposals on disk, one JSON file each ({@code <dir>/P-000042.json}), written atomically (temporary file, then
 * move), and kept in memory for reads. Decisions are a compare-and-set on the current record under one lock, so two
 * approvers cannot both decide the same proposal. The lock is a ReentrantLock: it spans file I/O.
 */
public final class ProposalStore {

    private final Path dir;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Map<String, Proposal> byId = new ConcurrentHashMap<>();
    private final AtomicLong next = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();

    public ProposalStore(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.dir);
            try (Stream<Path> files = Files.list(this.dir)) {
                for (Path f : files.filter(p -> p.getFileName().toString().matches("P-\\d+\\.json")).toList()) {
                    Proposal p = json.readValue(f.toFile(), Proposal.class);
                    byId.put(p.id(), p);
                    next.accumulateAndGet(Long.parseLong(p.id().substring(2)), Math::max);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read Sutra proposals in " + this.dir, e);
        }
    }

    public Proposal create(String name, int version, String text, String baseText, String note, String author) {
        return create(name, version, text, baseText, note, author, null);
    }

    public Proposal create(String name, int version, String text, String baseText, String note, String author,
            com.fasterxml.jackson.databind.JsonNode evidence) {
        lock.lock();
        try {
            Proposal p = new Proposal(String.format("P-%06d", next.incrementAndGet()), name, version, text, baseText, note, author, Instant.now(),
                    Proposal.PENDING, null, null, null, evidence);
            write(p);
            return p;
        } finally {
            lock.unlock();
        }
    }

    public Optional<Proposal> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Proposal require(String id) {
        return find(id).orElseThrow(() -> new DrishtiException(ErrorCode.PROPOSAL_NOT_FOUND, "no Sutra proposal '" + id + "'"));
    }

    /** Newest first; {@code status} and {@code name} filter when given. */
    public List<Proposal> list(String status, String name) {
        List<Proposal> out = new ArrayList<>(byId.values());
        out.removeIf(p -> (status != null && !status.isBlank() && !status.equals(p.status())) || (name != null && !name.isBlank() && !name.equals(p.name())));
        out.sort(Comparator.comparing(Proposal::createdAt).reversed().thenComparing(Proposal::id, Comparator.reverseOrder()));
        return out;
    }

    /**
     * Applies a decision to a pending proposal: {@code check} may refuse (throw) after seeing the current record, and
     * {@code publish} runs before the new status is written (approval makes the Sutra live there). Serialised.
     */
    public Proposal decide(String id, java.util.function.Consumer<Proposal> check, Runnable publish, String status, String by, String comment) {
        lock.lock();
        try {
            Proposal current = require(id);
            if (!current.pending()) {
                throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, id + " is already " + current.status());
            }
            check.accept(current);                          // throws when the decision is not allowed
            publish.run();
            Proposal decided = current.decided(status, by, Instant.now(), comment == null ? "" : comment.trim());
            write(decided);
            return decided;
        } finally {
            lock.unlock();
        }
    }

    private void write(Proposal p) {
        Path target = dir.resolve(p.id() + ".json");
        Path tmp = dir.resolve(p.id() + ".json.tmp");
        try {
            json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), p);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            byId.put(p.id(), p);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write Sutra proposal " + p.id(), e);
        }
    }
}
