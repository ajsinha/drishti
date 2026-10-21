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
package com.ash.drishti.identity.content;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A content store with multi-document transactions (docs/architecture/CONTENT_STORE.md section 9). Inside {@link #run} every write is
 * buffered and reads see it; nothing reaches the store until the work returns, so a failure writes nothing. The commit then:
 * <ol>
 *   <li>checks every buffered document still has the version the work read (a mismatch is {@code 409 DRS-5057}, nothing written);</li>
 *   <li>with more than one write, puts an intent record {@code system/tx/<time>-<uuid>.json} holding every write (key, expected version,
 *       new bytes);</li>
 *   <li>applies the writes in order, each a compare-and-set; when another node wins one of them, the writes already applied are put back
 *       (compare-and-set against what this commit wrote) and the commit is {@code 409 DRS-5057};</li>
 *   <li>removes the intent record.</li>
 * </ol>
 * A process that dies between 2 and 4 leaves its intent record; {@link #recover} (at start and on the refresh interval, on any node)
 * rolls it forward: a write whose document still has the expected version is applied, one already applied is skipped, and one whose
 * document someone else changed since is left out and logged. Outside {@link #run} every call passes straight through.
 */
public final class TxContentStore implements ContentStore {

    private static final Logger LOG = LoggerFactory.getLogger(TxContentStore.class);
    public static final String TX = "system/tx/";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ContentStore base;
    private final ThreadLocal<Map<String, Write>> current = new ThreadLocal<>();
    private final ReentrantLock local = new ReentrantLock();
    private final long recoverAfterMs;
    private volatile int crashAfter = -1;

    /** One buffered write: the version the work saw ({@code base}, null = absent) and the new bytes (null = delete). */
    private static final class Write {
        final String base;
        byte[] bytes;
        int gen;
        byte[] before;

        Write(String base, byte[] bytes) {
            this.base = base;
            this.bytes = bytes;
        }

        String pseudo() {
            return "~tx" + gen;
        }
    }

    public TxContentStore(ContentStore base) {
        this(base, 60_000);
    }

    /** {@code recoverAfterMs}: an intent record older than this belongs to a commit that died (a live commit takes milliseconds). */
    public TxContentStore(ContentStore base, long recoverAfterMs) {
        this.base = base instanceof TxContentStore t ? t.base : base;
        this.recoverAfterMs = recoverAfterMs;
    }

    /** The store underneath, for writes that must not wait for (or roll back with) a transaction, such as sequence counters. */
    public ContentStore base() {
        return base;
    }

    /** Tests: the next multi-document commit stops dead after {@code writes} writes, leaving its intent record (a crashed process). */
    public void simulateCrashAfter(int writes) {
        crashAfter = writes;
    }

    public boolean inTransaction() {
        return current.get() != null;
    }

    /** Runs {@code work} as one transaction (joins the caller's when there is one). Transactions on this node run one at a time. */
    public <T> T run(Supplier<T> work) {
        if (current.get() != null) {
            return work.get();
        }
        local.lock();
        try {
            Map<String, Write> buffer = new LinkedHashMap<>();
            current.set(buffer);
            T result;
            try {
                result = work.get();
            } finally {
                current.remove();
            }
            commit(buffer);
            return result;
        } finally {
            local.unlock();
        }
    }

    /**
     * {@link #run}, run again (up to 10 times) when another node changed a document first. Only for work with no effect outside the
     * store, since it may run more than once; inside the caller's transaction it runs once and the caller's commit decides.
     */
    public <T> T runRetrying(Supplier<T> work) {
        if (current.get() != null) {
            return work.get();
        }
        for (int attempt = 0; ; attempt++) {
            try {
                return run(work);
            } catch (DrishtiException e) {
                if (e.errorCode() != ErrorCode.CONTENT_CONFLICT || attempt >= 9) {
                    throw e;
                }
            }
        }
    }

    // ---- ContentStore ------------------------------------------------------------------------------------------------

    @Override
    public Optional<Item> get(String key) {
        Map<String, Write> tx = current.get();
        Write w = tx == null ? null : tx.get(key);
        if (w == null) {
            return base.get(key);
        }
        return w.bytes == null ? Optional.empty() : Optional.of(new Item(w.bytes.clone(), w.pseudo()));
    }

    @Override
    public String put(String key, byte[] bytes, String expectedVersion) {
        Map<String, Write> tx = current.get();
        if (tx == null) {
            return base.put(key, bytes, expectedVersion);
        }
        ContentStore.check(key);
        Write w = tx.get(key);
        if (w == null) {
            tx.put(key, w = new Write(expectedVersion, bytes.clone()));
            return w.pseudo();
        }
        boolean ok = w.bytes == null ? expectedVersion == null : w.pseudo().equals(expectedVersion);
        if (!ok) {
            throw ContentStore.conflict(key);
        }
        w.bytes = bytes.clone();
        w.gen++;
        return w.pseudo();
    }

    @Override
    public List<String> list(String prefix) {
        Map<String, Write> tx = current.get();
        if (tx == null) {
            return base.list(prefix);
        }
        TreeSet<String> out = new TreeSet<>(base.list(prefix));
        tx.forEach((k, w) -> {
            if (k.startsWith(prefix)) {
                if (w.bytes == null) {
                    out.remove(k);
                } else {
                    out.add(k);
                }
            }
        });
        return new ArrayList<>(out);
    }

    @Override
    public boolean delete(String key, String expectedVersion) {
        Map<String, Write> tx = current.get();
        if (tx == null) {
            return base.delete(key, expectedVersion);
        }
        Write w = tx.get(key);
        if (w == null) {
            Optional<Item> it = base.get(key);
            if (it.isEmpty()) {
                return false;
            }
            if (!it.get().version().equals(expectedVersion)) {
                throw ContentStore.conflict(key);
            }
            tx.put(key, new Write(expectedVersion, null));
            return true;
        }
        if (w.bytes == null) {
            return false;
        }
        if (!w.pseudo().equals(expectedVersion)) {
            throw ContentStore.conflict(key);
        }
        w.bytes = null;
        w.gen++;
        return true;
    }

    @Override
    public Changes changes(long since) {
        return base.changes(since);
    }

    @Override
    public String root() {
        return base.root();
    }

    // ---- commit and recovery -----------------------------------------------------------------------------------------

    private void commit(Map<String, Write> buffer) {
        List<Map.Entry<String, Write>> writes = new ArrayList<>();
        for (Map.Entry<String, Write> e : buffer.entrySet()) {
            Write w = e.getValue();
            if (w.bytes == null && w.base == null) {
                continue;                                                   // created and deleted again: nothing to write
            }
            Optional<Item> now = base.get(e.getKey());
            if (!Objects.equals(now.map(Item::version).orElse(null), w.base)) {
                throw ContentStore.conflict(e.getKey());
            }
            w.before = now.map(Item::bytes).orElse(null);
            writes.add(e);
        }
        if (writes.isEmpty()) {
            return;
        }
        if (writes.size() == 1) {
            apply(writes.get(0).getKey(), writes.get(0).getValue().base, writes.get(0).getValue().bytes);
            return;
        }
        String intent = TX + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".json";
        base.put(intent, intentOf(writes), null);
        List<String> applied = new ArrayList<>();                            // the version each applied write left
        try {
            for (Map.Entry<String, Write> e : writes) {
                if (applied.size() == crashAfter) {
                    crashAfter = -1;
                    throw new IllegalStateException("simulated crash after " + applied.size() + " write(s)");
                }
                applied.add(apply(e.getKey(), e.getValue().base, e.getValue().bytes));
            }
        } catch (DrishtiException ex) {
            if (ex.errorCode() == ErrorCode.CONTENT_CONFLICT) {
                undo(writes, applied);
                base.deleteLatest(intent);
            }
            throw ex;                                                       // any other failure: recover() finishes it later
        }
        base.deleteLatest(intent);
    }

    /** One compare-and-set write; returns the new version (null for a delete). */
    private String apply(String key, String expected, byte[] bytes) {
        if (bytes == null) {
            if (!base.delete(key, expected)) {
                throw ContentStore.conflict(key);
            }
            return null;
        }
        return base.put(key, bytes, expected);
    }

    private void undo(List<Map.Entry<String, Write>> writes, List<String> applied) {
        for (int i = applied.size() - 1; i >= 0; i--) {
            String key = writes.get(i).getKey();
            Write w = writes.get(i).getValue();
            String written = applied.get(i);
            try {
                if (w.before == null) {
                    base.delete(key, written);                              // it was a create
                } else {
                    base.put(key, w.before, written);                      // an update or a delete: put the old bytes back
                }
            } catch (DrishtiException e) {
                LOG.warn("content transaction: could not undo {}: {}", key, e.getMessage());
            }
        }
    }

    private static byte[] intentOf(List<Map.Entry<String, Write>> writes) {
        ObjectNode root = JSON.createObjectNode();
        root.put("at", System.currentTimeMillis());
        ArrayNode list = root.putArray("writes");
        for (Map.Entry<String, Write> e : writes) {
            ObjectNode w = list.addObject();
            w.put("key", e.getKey());
            w.put("expected", e.getValue().base);
            w.put("bytes", e.getValue().bytes == null ? null : Base64.getEncoder().encodeToString(e.getValue().bytes));
        }
        try {
            return JSON.writeValueAsBytes(root);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Rolls forward every commit that died between its intent record and its end; returns how many. Safe on any node at any time. */
    public int recover() {
        int done = 0;
        for (String key : base.list(TX)) {
            Optional<Item> it = base.get(key);
            if (it.isEmpty()) {
                continue;
            }
            try {
                JsonNode root = JSON.readTree(it.get().bytes());
                if (System.currentTimeMillis() - root.path("at").asLong() < recoverAfterMs) {
                    continue;                                               // a commit that may still be running
                }
                for (JsonNode w : root.path("writes")) {
                    rollForward(w.path("key").asText(), w.path("expected").isNull() ? null : w.path("expected").asText(),
                            w.path("bytes").isNull() ? null : Base64.getDecoder().decode(w.path("bytes").asText()));
                }
                base.delete(key, it.get().version());
                done++;
            } catch (IOException | RuntimeException e) {
                LOG.warn("content transaction: could not recover {}: {}", key, e.toString());
            }
        }
        if (done > 0) {
            LOG.info("content transaction: finished {} interrupted commit(s)", done);
        }
        return done;
    }

    private void rollForward(String key, String expected, byte[] bytes) {
        Optional<Item> now = base.get(key);
        boolean there = bytes == null ? now.isEmpty() : now.isPresent() && Arrays.equals(now.get().bytes(), bytes);
        if (there) {
            return;
        }
        if (!Objects.equals(now.map(Item::version).orElse(null), expected)) {
            LOG.warn("content transaction: {} was changed elsewhere after an interrupted commit; that write is left out", key);
            return;
        }
        try {
            apply(key, expected, bytes);
        } catch (DrishtiException e) {
            LOG.warn("content transaction: {} could not be rolled forward: {}", key, e.getMessage());
        }
    }
}
