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
 *
 * <p>With a content store ({@code drishti.governance.store=content}, the default; CONTENT_STORE.md section 9) the proposals are
 * {@code governance/proposals/P-NNNNNN.json} there instead, numbered from the cluster-wide sequence {@code governance/counters/proposals},
 * read from the store every time, and a decision is a compare-and-set: two approvers on two nodes cannot both decide one proposal.
 * The folder's proposals are imported once (marker {@code system/imports/governance-files.json}) and the files are left in place.
 */
public final class ProposalStore {

    private final Path dir;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Map<String, Proposal> byId = new ConcurrentHashMap<>();
    private final AtomicLong next = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();

    static final String PROPOSALS = "governance/proposals/";
    static final String COUNTER = "governance/counters/proposals";
    private final com.ash.drishti.identity.content.ContentStore content;
    private final com.ash.drishti.identity.content.ContentDocs docs;

    public ProposalStore(Path dir) {
        this(dir, null);
    }

    /** Proposals in {@code content} (every node of a cluster), after importing {@code dir} once; {@code content} null keeps them in {@code dir}. */
    public ProposalStore(Path dir, com.ash.drishti.identity.content.ContentStore content) {
        this.content = content;
        this.docs = content == null ? null : new com.ash.drishti.identity.content.ContentDocs(content);
        this.dir = dir.toAbsolutePath().normalize();
        if (content != null) {
            importFolder();
            return;
        }
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

    private void importFolder() {
        String marker = "system/imports/governance-files.json";
        if (content.get(marker).isPresent()) {
            return;
        }
        int n = 0;
        long max = 0;
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(x -> x.getFileName().toString().matches("P-\\d+\\.json")).sorted().toList()) {
                    Proposal p = json.readValue(f.toFile(), Proposal.class);
                    max = Math.max(max, Long.parseLong(p.id().substring(2)));
                    if (docs.create(PROPOSALS + p.id() + ".json", p)) {
                        n++;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read Sutra proposals in " + dir, e);
            }
        }
        com.ash.drishti.identity.content.ContentDocs.raise(content, COUNTER, max);
        docs.create(marker, Map.of("imported", n, "at", System.currentTimeMillis()));
    }

    public Proposal create(String name, int version, String text, String baseText, String note, String author,
            com.fasterxml.jackson.databind.JsonNode evidence) {
        if (content != null) {
            Proposal p = new Proposal(String.format("P-%06d", com.ash.drishti.identity.content.ContentDocs.next(content, COUNTER)), name, version, text,
                    baseText, note, author, Instant.now(), Proposal.PENDING, null, null, null, evidence);
            content.put(PROPOSALS + p.id() + ".json", com.ash.drishti.identity.content.ContentDocs.encode(p), null);
            return p;
        }
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
        if (content != null) {
            return id == null || !id.matches("P-\\d+") ? Optional.empty() : docs.value(PROPOSALS + id + ".json", Proposal.class);
        }
        return Optional.ofNullable(byId.get(id));
    }

    public Proposal require(String id) {
        return find(id).orElseThrow(() -> new DrishtiException(ErrorCode.PROPOSAL_NOT_FOUND, "no Sutra proposal '" + id + "'"));
    }

    /** Newest first; {@code status} and {@code name} filter when given. */
    public List<Proposal> list(String status, String name) {
        List<Proposal> out = new ArrayList<>(byId.values());
        if (content != null) {
            out.clear();
            for (String k : content.list(PROPOSALS)) {
                docs.value(k, Proposal.class).ifPresent(out::add);
            }
        }
        out.removeIf(p -> (status != null && !status.isBlank() && !status.equals(p.status())) || (name != null && !name.isBlank() && !name.equals(p.name())));
        out.sort(Comparator.comparing(Proposal::createdAt).reversed().thenComparing(Proposal::id, Comparator.reverseOrder()));
        return out;
    }

    /**
     * Applies a decision to a pending proposal: {@code check} may refuse (throw) after seeing the current record, and
     * {@code publish} runs before the new status is written (approval makes the Sutra live there). Serialised.
     */
    public Proposal decide(String id, java.util.function.Consumer<Proposal> check, Runnable publish, String status, String by, String comment) {
        if (content != null) {
            var read = docs.read(PROPOSALS + id + ".json", Proposal.class)
                    .orElseThrow(() -> new DrishtiException(ErrorCode.PROPOSAL_NOT_FOUND, "no Sutra proposal '" + id + "'"));
            Proposal current = read.value();
            if (!current.pending()) {
                throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, id + " is already " + current.status());
            }
            check.accept(current);
            publish.run();
            Proposal decided = current.decided(status, by, Instant.now(), comment == null ? "" : comment.trim());
            try {
                content.put(PROPOSALS + id + ".json", com.ash.drishti.identity.content.ContentDocs.encode(decided), read.version());
            } catch (DrishtiException e) {
                if (e.errorCode() == ErrorCode.CONTENT_CONFLICT) {
                    throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, id + " was decided on another server meanwhile");
                }
                throw e;
            }
            return decided;
        }
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
