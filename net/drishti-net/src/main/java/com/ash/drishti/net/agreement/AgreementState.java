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
package com.ash.drishti.net.agreement;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The agreed state of leases and the register as a deterministic state machine (CLUSTER_I5_DESIGN.md section 3): every replica that
 * applies the same commands in the same order holds the same state. It never reads a clock: time is a command ({@link #tick}), proposed
 * by the referee (the Raft leader, or the local referee before each command), so every replica agrees on when a lease expires.
 *
 * <p>Commands and queries are small byte arrays (built by the static helpers here), so a provider carries them as they are: the ratis
 * state machine applies them from its log, {@link LocalAgreement} applies them in process. Not thread-safe: the provider applies one
 * command at a time.
 */
public final class AgreementState {

    private static final byte TICK = 1;
    private static final byte CANDIDATES = 2;
    private static final byte ASK = 3;
    private static final byte RELEASE = 4;
    private static final byte CAS = 5;
    private static final byte GET = 10;
    private static final byte DUMP = 11;
    private static final int SNAPSHOT_FORMAT = 1;

    /** One lease's agreed row: its candidates, its holder and term, and when each candidate last asked (the referee's clock). */
    private static final class Row {
        List<String> candidates = List.of();
        long candidatesSince;
        String holder = "";
        long term;
        long expiresAt;
        final Map<String, Long> asked = new TreeMap<>();
    }

    /** Everything a node needs to refresh its view: the revision, the referee's time, every lease and every register entry. */
    public record Dump(long revision, long now, Map<String, Leases.Lease> leases, Map<String, Register.Entry> entries) {}

    private final Map<String, Row> leases = new TreeMap<>();
    private final Map<String, Register.Entry> register = new TreeMap<>();
    private long now;
    private long revision;

    // -- commands -------------------------------------------------------------------------------------------------------

    /** The referee's time moves on (never back): leases past their expiry become free. */
    public static byte[] tick(long now) {
        return write(o -> {
            o.writeByte(TICK);
            o.writeLong(now);
        });
    }

    /** A lease's candidates, in order; an empty list forgets the lease. */
    public static byte[] candidates(String lease, List<String> nodes) {
        return write(o -> {
            o.writeByte(CANDIDATES);
            o.writeUTF(lease);
            o.writeInt(nodes.size());
            for (String n : nodes) {
                o.writeUTF(n);
            }
        });
    }

    /** {@code node} asks for the lease (acquire, or renew when it holds it) for {@code ttlMillis}; answers the lease as it then stands. */
    public static byte[] ask(String lease, String node, long ttlMillis) {
        return write(o -> {
            o.writeByte(ASK);
            o.writeUTF(lease);
            o.writeUTF(node);
            o.writeLong(ttlMillis);
        });
    }

    /** {@code node} gives up the lease it holds under {@code term}; answers the lease as it then stands. */
    public static byte[] release(String lease, String node, long term) {
        return write(o -> {
            o.writeByte(RELEASE);
            o.writeUTF(lease);
            o.writeUTF(node);
            o.writeLong(term);
        });
    }

    /** A compare-and-set of a register key; answers true or false. */
    public static byte[] compareAndSet(String key, long expected, byte[] value, String by) {
        if (value != null && value.length > Register.MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("a register value is at most " + Register.MAX_VALUE_BYTES + " bytes; " + key + " has " + value.length);
        }
        return write(o -> {
            o.writeByte(CAS);
            o.writeUTF(key);
            o.writeLong(expected);
            byte[] v = value == null ? new byte[0] : value;
            o.writeInt(v.length);
            o.write(v);
            o.writeUTF(by == null ? "" : by);
        });
    }

    // -- queries --------------------------------------------------------------------------------------------------------

    /** One register key; answers the entry or nothing. */
    public static byte[] get(String key) {
        return write(o -> {
            o.writeByte(GET);
            o.writeUTF(key);
        });
    }

    /** Everything, when the revision is newer than {@code since}; answers a {@link Dump} or nothing (unchanged). */
    public static byte[] dump(long since) {
        return write(o -> {
            o.writeByte(DUMP);
            o.writeLong(since);
        });
    }

    /** True when the bytes are a query (read-only), not a command. */
    public static boolean isQuery(byte[] bytes) {
        return bytes != null && bytes.length > 0 && (bytes[0] == GET || bytes[0] == DUMP);
    }

    // -- applying -------------------------------------------------------------------------------------------------------

    /** Applies one command and answers its result (see each helper); a query is answered as {@link #query} does. */
    public byte[] apply(byte[] command) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(command))) {
            byte type = in.readByte();
            return switch (type) {
                case TICK -> {
                    long t = in.readLong();
                    if (t > now) {
                        now = t;
                        for (Row r : leases.values()) {
                            if (!r.holder.isEmpty() && r.expiresAt <= now) {
                                r.holder = "";                     // expired: free, the term stays until the next grant raises it
                                revision++;
                            }
                        }
                    }
                    yield new byte[0];
                }
                case CANDIDATES -> {
                    String lease = in.readUTF();
                    int n = in.readInt();
                    List<String> nodes = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        nodes.add(in.readUTF());
                    }
                    if (nodes.isEmpty()) {
                        if (leases.remove(lease) != null) {
                            revision++;
                        }
                        yield lease(lease, null);
                    }
                    Row r = leases.computeIfAbsent(lease, k -> new Row());
                    if (!nodes.equals(r.candidates)) {
                        if (r.candidates.isEmpty()) {
                            r.candidatesSince = now;
                        }
                        r.candidates = List.copyOf(nodes);
                        if (!r.holder.isEmpty() && !nodes.contains(r.holder)) {
                            r.holder = "";                         // the holder is no longer a candidate: free at once
                        }
                        r.asked.keySet().retainAll(nodes);
                        revision++;
                    }
                    yield lease(lease, r);
                }
                case ASK -> {
                    String lease = in.readUTF();
                    String node = in.readUTF();
                    long ttl = Math.max(1, in.readLong());
                    Row r = leases.get(lease);
                    if (r == null || !r.candidates.contains(node)) {
                        yield lease(lease, r);
                    }
                    r.asked.put(node, now);
                    boolean held = !r.holder.isEmpty() && r.expiresAt > now;
                    if (held && r.holder.equals(node)) {
                        r.expiresAt = now + ttl;                    // a renewal: no change of holder, no new revision
                    } else if (!held && node.equals(firstLive(r, ttl))
                            && (node.equals(r.candidates.get(0)) || r.term > 0 || now - r.candidatesSince >= ttl)) {
                        r.holder = node;                            // the first live candidate takes a free lease; before the first grant
                        r.term++;                                   // only the first candidate may, until it has had one time-to-live to ask
                        r.expiresAt = now + ttl;
                        revision++;
                    }
                    yield lease(lease, r);
                }
                case RELEASE -> {
                    String lease = in.readUTF();
                    String node = in.readUTF();
                    long term = in.readLong();
                    Row r = leases.get(lease);
                    if (r != null) {
                        r.asked.remove(node);
                        if (node.equals(r.holder) && r.term == term) {
                            r.holder = "";
                            r.expiresAt = now;
                            revision++;
                        }
                    }
                    yield lease(lease, r);
                }
                case CAS -> {
                    String key = in.readUTF();
                    long expected = in.readLong();
                    byte[] value = new byte[in.readInt()];
                    in.readFully(value);
                    String by = in.readUTF();
                    Register.Entry e = register.get(key);
                    long version = e == null ? 0 : e.version();
                    boolean ok = version == expected;
                    if (ok) {
                        register.put(key, new Register.Entry(key, value, version + 1, by, now));
                        revision++;
                    }
                    yield new byte[] {(byte) (ok ? 1 : 0)};
                }
                case GET, DUMP -> query(command);
                default -> throw new IllegalArgumentException("not an agreement command: " + type);
            };
        } catch (IOException e) {
            throw new UncheckedIOException("an agreement command could not be read", e);
        }
    }

    /** Answers a query without changing anything. */
    public byte[] query(byte[] query) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(query))) {
            byte type = in.readByte();
            if (type == GET) {
                Register.Entry e = register.get(in.readUTF());
                return write(o -> writeEntry(o, e));
            }
            if (type == DUMP) {
                long since = in.readLong();
                if (since >= revision && since > 0) {
                    return new byte[0];
                }
                return write(this::writeAll);
            }
            throw new IllegalArgumentException("not an agreement query: " + type);
        } catch (IOException e) {
            throw new UncheckedIOException("an agreement query could not be read", e);
        }
    }

    /** The first candidate, in order, whose last ask is younger than {@code ttl}; null when none is live. */
    private String firstLive(Row r, long ttl) {
        for (String c : r.candidates) {
            Long at = r.asked.get(c);
            if (at != null && now - at < ttl) {
                return c;
            }
        }
        return null;
    }

    private byte[] lease(String name, Row r) {
        return write(o -> writeLease(o, name, r));
    }

    private void writeLease(DataOutputStream o, String name, Row r) throws IOException {
        boolean held = r != null && !r.holder.isEmpty() && r.expiresAt > now;
        o.writeBoolean(held);
        if (held) {
            o.writeUTF(name);
            o.writeUTF(r.holder);
            o.writeLong(r.term);
            o.writeLong(r.expiresAt);
        }
    }

    private static void writeEntry(DataOutputStream o, Register.Entry e) throws IOException {
        o.writeBoolean(e != null);
        if (e != null) {
            o.writeUTF(e.key());
            byte[] v = e.value();
            o.writeInt(v.length);
            o.write(v);
            o.writeLong(e.version());
            o.writeUTF(e.by());
            o.writeLong(e.at());
        }
    }

    private void writeAll(DataOutputStream o) throws IOException {
        o.writeLong(revision);
        o.writeLong(now);
        o.writeInt(leases.size());
        for (Map.Entry<String, Row> l : leases.entrySet()) {
            o.writeUTF(l.getKey());
            writeLease(o, l.getKey(), l.getValue());
        }
        o.writeInt(register.size());
        for (Register.Entry e : register.values()) {
            writeEntry(o, e);
        }
    }

    // -- reading answers ------------------------------------------------------------------------------------------------

    /** The lease an {@code ask}, {@code release} or {@code candidates} answered: empty when it is free. */
    public static Optional<Leases.Lease> readLease(byte[] answer) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(answer))) {
            return readLease(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Optional<Leases.Lease> readLease(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return Optional.empty();
        }
        return Optional.of(new Leases.Lease(in.readUTF(), in.readUTF(), in.readLong(), in.readLong()));
    }

    /** The answer of a compare-and-set. */
    public static boolean readBoolean(byte[] answer) {
        return answer != null && answer.length == 1 && answer[0] == 1;
    }

    /** The entry a {@code get} answered. */
    public static Optional<Register.Entry> readEntry(byte[] answer) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(answer))) {
            return readEntry(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Optional<Register.Entry> readEntry(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return Optional.empty();
        }
        String key = in.readUTF();
        byte[] v = new byte[in.readInt()];
        in.readFully(v);
        return Optional.of(new Register.Entry(key, v, in.readLong(), in.readUTF(), in.readLong()));
    }

    /** The dump a {@code dump} answered, or empty when nothing changed since the revision asked about. */
    public static Optional<Dump> readDump(byte[] answer) {
        if (answer == null || answer.length == 0) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(answer))) {
            long rev = in.readLong();
            long at = in.readLong();
            Map<String, Leases.Lease> ls = new LinkedHashMap<>();
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                String name = in.readUTF();
                readLease(in).ifPresent(l -> ls.put(name, l));
            }
            Map<String, Register.Entry> es = new LinkedHashMap<>();
            int m = in.readInt();
            for (int i = 0; i < m; i++) {
                readEntry(in).ifPresent(e -> es.put(e.key(), e));
            }
            return Optional.of(new Dump(rev, at, ls, es));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // -- snapshots ------------------------------------------------------------------------------------------------------

    /** The whole state, for a snapshot. */
    public byte[] snapshot() {
        return write(o -> {
            o.writeInt(SNAPSHOT_FORMAT);
            o.writeLong(now);
            o.writeLong(revision);
            o.writeInt(leases.size());
            for (Map.Entry<String, Row> l : leases.entrySet()) {
                Row r = l.getValue();
                o.writeUTF(l.getKey());
                o.writeInt(r.candidates.size());
                for (String c : r.candidates) {
                    o.writeUTF(c);
                }
                o.writeLong(r.candidatesSince);
                o.writeUTF(r.holder);
                o.writeLong(r.term);
                o.writeLong(r.expiresAt);
                o.writeInt(r.asked.size());
                for (Map.Entry<String, Long> a : r.asked.entrySet()) {
                    o.writeUTF(a.getKey());
                    o.writeLong(a.getValue());
                }
            }
            o.writeInt(register.size());
            for (Register.Entry e : register.values()) {
                writeEntry(o, e);
            }
        });
    }

    /** Replaces the state with a snapshot's. */
    public void restore(byte[] snapshot) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(snapshot))) {
            int format = in.readInt();
            if (format != SNAPSHOT_FORMAT) {
                throw new IllegalStateException("an agreement snapshot of format " + format + "; this build reads " + SNAPSHOT_FORMAT);
            }
            leases.clear();
            register.clear();
            now = in.readLong();
            revision = in.readLong();
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                String name = in.readUTF();
                Row r = new Row();
                int c = in.readInt();
                List<String> cs = new ArrayList<>(c);
                for (int j = 0; j < c; j++) {
                    cs.add(in.readUTF());
                }
                r.candidates = List.copyOf(cs);
                r.candidatesSince = in.readLong();
                r.holder = in.readUTF();
                r.term = in.readLong();
                r.expiresAt = in.readLong();
                int a = in.readInt();
                for (int j = 0; j < a; j++) {
                    r.asked.put(in.readUTF(), in.readLong());
                }
                leases.put(name, r);
            }
            int m = in.readInt();
            for (int i = 0; i < m; i++) {
                readEntry(in).ifPresent(e -> register.put(e.key(), e));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("an agreement snapshot could not be read", e);
        }
    }

    /** The referee's time as last ticked. */
    public long now() {
        return now;
    }

    /** Raised by every change of a holder or a register key. */
    public long revision() {
        return revision;
    }

    private interface Writer {
        void to(DataOutputStream o) throws IOException;
    }

    private static byte[] write(Writer w) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream o = new DataOutputStream(b)) {
            w.to(o);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return b.toByteArray();
    }
}
