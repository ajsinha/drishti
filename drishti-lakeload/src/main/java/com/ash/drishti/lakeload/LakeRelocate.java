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
package com.ash.drishti.lakeload;

import com.ash.drishti.lakeconfig.Lake;
import com.ash.drishti.lakeconfig.LakeTable;
import com.ash.drishti.lakeconfig.Lakes;
import com.ash.drishti.deltalake.write.LakeWriter;
import com.ash.drishti.lakeio.LocalPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The one-time move of a lake built in the older layout, its tables directly in {@code <data>/delta/<domain>/<table>}, into its own folder
 * {@code <data>/delta/<lake>/} (docs/admin/OPERATIONS.md, "The lakes' folders"). Safe by construction:
 * <ul>
 *   <li>only the lake's known top folders move (the first folder of each of its tables' paths, and its state folder {@code _drishti-lake}),
 *       by a rename on the same file system; another lake's folders at the same place stay where they are;</li>
 *   <li>it refuses, saying why, when the target exists, when anything at the old place belongs to no lake, when a folder is on another file
 *       system, when a table there is Iceberg (its metadata holds absolute paths), when a table does not open, or when a build or a load holds
 *       the lake's lock;</li>
 *   <li>every Delta table is opened and measured before and after (its version, data files, rows from each data file's Parquet footer, and that every
 *       data file it names is there); any difference, or any error, moves everything back;</li>
 *   <li>it never deletes a file; it runs once, and the record ({@code <lake>/_drishti-lake/relocated.json}) says when and what moved.</li>
 * </ul>
 * The server runs it for the demo lake at start ({@code drishti.lakes.relocate-on-start}); {@code drishti.py lake relocate demo} runs it by hand
 * ({@code --dry-run} says what it would do).
 */
public final class LakeRelocate {

    /** The record of a done move, under the lake's state folder. */
    public static final String RECORD = "relocated.json";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** What happened: {@code moved}, {@code would-move} (a dry run), {@code nothing} (nothing to move), {@code done-before}, {@code refused}, {@code rolled-back}, {@code failed}. */
    public record Outcome(String state, String message, String lake, Path from, Path to, List<String> folders, Map<String, Measure> tables) {

        public boolean refusedOrFailed() {
            return state.equals("refused") || state.equals("rolled-back") || state.equals("failed");
        }
    }

    /** One Delta table measured: its version, its data files, its rows (each data file's own row count, from its Parquet footer) and data files not there. */
    public record Measure(long version, long files, long rows, long missing) {}

    /** A test's way to fail the move after {@code n} folders were moved, or the check after the move ({@code verify}). */
    @FunctionalInterface
    public interface Faults {
        void at(String step) throws IOException;

        Faults NONE = s -> { };
    }

    private LakeRelocate() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    /** {@code --lake NAME [--dry-run]}: the outcome as JSON; exit 0 moved, nothing or done before, 1 refused, rolled back or failed. */
    static int run(String[] args, PrintStream out) {
        String name = null;
        boolean dry = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--lake" -> name = i + 1 < args.length ? args[++i] : null;
                case "--dry-run" -> dry = true;
                default -> {
                    out.println("{\"state\": \"refused\", \"message\": \"usage: LakeRelocate --lake NAME [--dry-run]\"}");
                    return 1;
                }
            }
        }
        if (name == null) {
            out.println("{\"state\": \"refused\", \"message\": \"give --lake NAME (demo)\"}");
            return 1;
        }
        Outcome o;
        try {
            o = relocate(Lakes.read(Lakes.dir(), System::getenv), name, dry, Faults.NONE);
        } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
            o = new Outcome("refused", e.getMessage(), name, null, null, List.of(), Map.of());
        }
        try {
            out.println(JSON.writeValueAsString(describe(o)));
        } catch (IOException e) {
            out.println("{\"state\": \"" + o.state() + "\"}");
        }
        return o.refusedOrFailed() ? 1 : 0;
    }

    /** The outcome as plain maps (the JSON the CLI prints). */
    public static Map<String, Object> describe(Outcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", o.state());
        m.put("message", o.message());
        m.put("lake", o.lake());
        m.put("from", o.from() == null ? null : o.from().toString());
        m.put("to", o.to() == null ? null : o.to().toString());
        m.put("folders", o.folders());
        Map<String, Object> t = new TreeMap<>();
        o.tables().forEach((k, v) -> t.put(k, Map.of("version", v.version(), "files", v.files(), "rows", v.rows())));
        m.put("tables", t);
        return m;
    }

    /** {@link #relocate(Lakes, String, boolean, Faults)} without faults. */
    public static Outcome relocate(Lakes lakes, String name, boolean dryRun) {
        return relocate(lakes, name, dryRun, Faults.NONE);
    }

    /**
     * Moves lake {@code name} from the folder that holds its root into its root, when its tables are there in the older layout (see the
     * class comment). Never throws for a refusal: the outcome says it.
     */
    public static Outcome relocate(Lakes lakes, String name, boolean dryRun, Faults faults) {
        Lake lake = lakes.get(name);
        if (lake.inMemory() || !LocalPaths.isLocal(lake.root())) {
            return new Outcome("nothing", "lake " + name + " is not on a local disk (" + lake.root() + "): nothing to move", name, null, null, List.of(), Map.of());
        }
        Path to = Path.of(LocalPaths.toLocal(lake.root(), LocalPaths.WINDOWS)).toAbsolutePath().normalize();
        Path from = to.getParent();
        if (from == null || !to.getFileName().toString().equals(name)) {
            return new Outcome("nothing", "lake " + name + "'s root " + to + " is its own (set by its lake file or " + Lakes.DEMO_ROOT_ENV + "), not <data>/delta/"
                    + name + ": nothing to move", name, from, to, List.of(), Map.of());
        }
        Path record = to.resolve(LakeState.STATE).resolve(RECORD);
        if (Files.isRegularFile(record)) {
            return new Outcome("done-before", "lake " + name + " was moved into " + to + " before (" + record + ")", name, from, to, List.of(), Map.of());
        }
        // the lake's own top folders, and what other lakes claim at the same place
        Set<String> mine = new TreeSet<>();
        for (LakeTable t : lake.tables().values()) {
            mine.add(t.path().split("/")[0]);
        }
        Set<String> others = new TreeSet<>();
        for (Lake l : lakes.all().values()) {
            if (l.name().equals(name) || l.inMemory() || !LocalPaths.isLocal(l.root())) {
                continue;
            }
            Path r = Path.of(LocalPaths.toLocal(l.root(), LocalPaths.WINDOWS)).toAbsolutePath().normalize();
            if (r.equals(from)) {
                l.tables().values().forEach(t -> others.add(t.path().split("/")[0]));
            } else if (from.equals(r.getParent())) {
                others.add(r.getFileName().toString());
            }
        }
        if (!Files.isDirectory(from)) {
            return new Outcome("nothing", from + " does not exist: nothing to move", name, from, to, List.of(), Map.of());
        }
        List<String> move = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<String> shared = new ArrayList<>();
        try (Stream<Path> s = Files.list(from)) {
            for (Path p : s.sorted().toList()) {
                String n = p.getFileName().toString();
                if (n.equals(name)) {
                    continue;                                   // the target itself: refused below when it exists
                }
                if (placeholderOnly(p)) {
                    continue;                                   // another lake's folder kept in git with only its README.md (risklake)
                }
                boolean isMine = mine.contains(n) && Files.isDirectory(p);
                if (isMine && others.contains(n)) {
                    shared.add(n);
                } else if (isMine) {
                    move.add(n);
                } else if (n.equals(LakeState.STATE) && Files.isDirectory(p)) {
                    continue;                                   // the state folder: moved with the tables, below
                } else if (!others.contains(n)) {
                    unknown.add(n);
                }
            }
        } catch (IOException e) {
            return refused(name, from, to, "cannot list " + from + ": " + e.getMessage());
        }
        if (move.isEmpty()) {
            return new Outcome("nothing", "no table folder of lake " + name + " in " + from + ": nothing to move", name, from, to, List.of(), Map.of());
        }
        if (Files.exists(to) && !placeholderOnly(to)) {
            return refused(name, from, to, "the target " + to + " exists already, and " + from + " still holds " + move + " of lake " + name
                    + ": move them by hand, or rename the target and run drishti.py lake relocate " + name + " again");
        }
        if (!shared.isEmpty()) {
            return refused(name, from, to, shared + " in " + from + " belong to lake " + name + " and to another lake at the same place: cannot "
                    + "tell whose they are");
        }
        if (!unknown.isEmpty()) {
            return refused(name, from, to, unknown + " in " + from + " belong to no lake (config/lakes/): move them away or describe them in a lake "
                    + "file, then run drishti.py lake relocate " + name + " again; nothing was moved");
        }
        if (Files.isDirectory(from.resolve(LakeState.STATE))) {
            move.add(LakeState.STATE);
        }
        // the same file system: a rename, never a copy
        try {
            FileStore store = Files.getFileStore(from);
            for (String n : move) {
                if (!Files.getFileStore(from.resolve(n)).equals(store)) {
                    return refused(name, from, to, from.resolve(n) + " is on another file system than " + from + ": a move there would be a copy; "
                            + "move the lake by hand");
                }
            }
        } catch (IOException e) {
            return refused(name, from, to, "cannot tell the file systems: " + e.getMessage());
        }
        // every table measured now: a table that does not open is refused before anything moves
        Map<String, Measure> before = new TreeMap<>();
        try {
            for (String n : move) {
                if (n.equals(LakeState.STATE)) {
                    continue;
                }
                for (Path t : tables(from.resolve(n))) {
                    before.put(from.relativize(t).toString().replace('\\', '/'), measure(t));
                }
            }
        } catch (Refusal r) {
            return refused(name, from, to, r.getMessage());
        } catch (IOException | RuntimeException e) {
            return refused(name, from, to, "a table of lake " + name + " does not open now (" + e.getMessage() + "): fix it first; nothing was moved");
        }
        for (var e : before.entrySet()) {
            if (e.getValue().missing() > 0) {
                return refused(name, from, to, "table " + e.getKey() + " names " + e.getValue().missing() + " data files that are not there: fix it "
                        + "first; nothing was moved");
            }
        }
        if (dryRun) {
            return new Outcome("would-move", "would move " + move + " from " + from + " into " + to + " (" + before.size() + " tables)", name, from, to,
                    move, before);
        }
        try (Held held = lock(from)) {
            if (held.refusal != null) {
                return refused(name, from, to, held.refusal);
            }
            List<String> moved = new ArrayList<>();
            try {
                if (!Files.isDirectory(to)) {
                    Files.createDirectory(to);                  // a folder kept in git with only its README.md is used as it is
                }
                for (String n : move) {
                    Files.move(from.resolve(n), to.resolve(n), StandardCopyOption.ATOMIC_MOVE);
                    moved.add(n);
                    faults.at("moved " + moved.size());
                }
                faults.at("verify");
                for (var e : before.entrySet()) {
                    Measure after = measure(to.resolve(e.getKey()));
                    if (!after.equals(e.getValue()) || after.missing() > 0) {
                        throw new IOException("table " + e.getKey() + " differs after the move: before " + e.getValue() + ", after " + after);
                    }
                }
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("lake", name);
                rec.put("from", from.toString());
                rec.put("to", to.toString());
                rec.put("at", Instant.now().toString());
                rec.put("folders", move);
                Map<String, Object> t = new TreeMap<>();
                before.forEach((k, v) -> t.put(k, Map.of("version", v.version(), "files", v.files(), "rows", v.rows())));
                rec.put("tables", t);
                faults.at("record");
                Files.createDirectories(record.getParent());
                Files.writeString(record, JSON.writeValueAsString(rec), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return new Outcome("moved", "moved " + move + " from " + from + " into " + to + "; " + before.size() + " tables open with the same "
                        + "version, files and rows", name, from, to, moved, before);
            } catch (IOException | RuntimeException e) {
                return back(name, from, to, moved, e);
            }
        } catch (IOException e) {
            return refused(name, from, to, "the lake's lock: " + e.getMessage());
        }
    }

    /** Moves back what moved; the target folder goes only when it is empty again (it was made by this move). */
    private static Outcome back(String name, Path from, Path to, List<String> moved, Exception why) {
        List<String> stuck = new ArrayList<>();
        for (int i = moved.size() - 1; i >= 0; i--) {
            String n = moved.get(i);
            try {
                Files.move(to.resolve(n), from.resolve(n), StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                stuck.add(n + " (" + e.getMessage() + ")");
            }
        }
        try (Stream<Path> s = Files.isDirectory(to) ? Files.list(to) : Stream.empty()) {
            if (Files.isDirectory(to) && s.findAny().isEmpty()) {
                Files.delete(to);                               // the empty folder this move made
            }
        } catch (IOException e) {
            stuck.add(to + " (" + e.getMessage() + ")");
        }
        String reason = why.getMessage() == null ? why.toString() : why.getMessage();
        if (!stuck.isEmpty()) {
            return new Outcome("failed", "the move of lake " + name + " failed (" + reason + ") and these could not be moved back from " + to + " to "
                    + from + ": " + stuck + "; move them by hand (nothing was deleted)", name, from, to, moved, Map.of());
        }
        return new Outcome("rolled-back", "the move of lake " + name + " failed (" + reason + "): everything was moved back into " + from
                + "; nothing was deleted", name, from, to, List.of(), Map.of());
    }

    private static Outcome refused(String name, Path from, Path to, String why) {
        return new Outcome("refused", why, name, from, to, List.of(), Map.of());
    }

    /** A refusal found while looking at the tables. */
    private static final class Refusal extends IOException {
        private static final long serialVersionUID = 1L;

        Refusal(String m) {
            super(m);
        }
    }

    /** The Delta tables under a top folder (a folder with {@code _delta_log}, up to four levels down); an Iceberg table refuses. */
    static List<Path> tables(Path top) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(top, 4)) {
            for (Path p : s.filter(Files::isDirectory).sorted().toList()) {
                if (Files.isDirectory(p.resolve("_delta_log"))) {
                    out.add(p);
                } else if (Files.isDirectory(p.resolve("metadata")) && !p.toString().contains("_delta_log")) {
                    try (Stream<Path> m = Files.list(p.resolve("metadata"))) {
                        if (m.anyMatch(f -> f.getFileName().toString().endsWith(".metadata.json"))) {
                            throw new Refusal(p + " is an Iceberg table: its metadata holds absolute paths, so it cannot be moved by a rename; "
                                    + "nothing was moved (rebuild it in the lake's new folder instead)");
                        }
                    }
                }
            }
        }
        return out;
    }

    /** One Delta table: its version, data files, rows (each data file's footer: the rows written) and data files not on the disk. */
    static Measure measure(Path table) throws IOException {
        Engine engine = LakeWriter.engineFor(table.toString(), Map.of());
        Snapshot s = Table.forPath(engine, table.toString()).getLatestSnapshot(engine);
        long files = 0;
        long rows = 0;
        long missing = 0;
        try (CloseableIterator<FilteredColumnarBatch> it = s.getScanBuilder().build().getScanFiles(engine)) {
            while (it.hasNext()) {
                try (CloseableIterator<Row> rs = it.next().getRows()) {
                    while (rs.hasNext()) {
                        Row r = rs.next();
                        FileStatus fs = InternalScanFileUtils.getAddFileStatus(r);
                        files++;
                        Path data = Path.of(LocalPaths.toLocal(fs.getPath(), LocalPaths.WINDOWS));
                        if (!Files.isRegularFile(data)) {
                            missing++;
                        } else {
                            rows += footerRows(data);
                        }
                    }
                }
            }
        }
        return new Measure(s.getVersion(), files, rows, missing);
    }

    /** The rows a Parquet data file holds, from its footer (the sum of its row groups). */
    static long footerRows(Path data) throws IOException {
        org.apache.parquet.io.InputFile in = new org.apache.parquet.io.LocalInputFile(data);
        try (org.apache.parquet.io.SeekableInputStream stream = in.newStream()) {
            return org.apache.parquet.hadoop.ParquetFileReader.readFooter(in,
                    org.apache.parquet.ParquetReadOptions.builder(com.ash.drishti.lakeio.ParquetFooters.PLAIN).build(), stream)
                    .getBlocks().stream().mapToLong(b -> b.getRowCount()).sum();
        }
    }

    /** The lake's lock, held exclusively while the folders move: no build or load writes meanwhile. */
    private static final class Held implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private final String refusal;

        Held(FileChannel channel, FileLock lock, String refusal) {
            this.channel = channel;
            this.lock = lock;
            this.refusal = refusal;
        }

        @Override
        public void close() throws IOException {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
            if (channel != null) {
                channel.close();
            }
        }
    }

    private static Held lock(Path from) throws IOException {
        Path f = from.resolve(LakeState.STATE).resolve("lock");
        if (!Files.isRegularFile(f)) {
            return new Held(null, null, null);
        }
        FileChannel ch = FileChannel.open(f, StandardOpenOption.WRITE);
        FileLock l;
        try {
            l = ch.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException e) {
            l = null;
        }
        if (l == null) {
            ch.close();
            return new Held(null, null, "a build or a load holds the lake's lock (" + f + "): run drishti.py lake relocate again when it is done; "
                    + "nothing was moved");
        }
        if (LocalPaths.WINDOWS) {                              // Windows does not rename a folder with an open file: released before the move
            l.release();
            ch.close();
            return new Held(null, null, null);
        }
        return new Held(ch, l, null);
    }

    /**
     * Whether a target folder holds nothing but its README.md: the lakes' folders are kept in git that way (each with only its
     * README.md), so such a folder is as good as absent and the move goes into it.
     *
     * @param dir the target folder
     * @return true when it is a folder whose one entry is README.md (an empty folder is not: it says nothing)
     */
    static boolean placeholderOnly(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> in = s.toList();
            return in.size() == 1 && in.get(0).getFileName().toString().equals("README.md") && Files.isRegularFile(in.get(0));
        } catch (IOException e) {
            return false;
        }
    }
}
