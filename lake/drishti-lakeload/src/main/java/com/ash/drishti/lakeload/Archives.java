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

import com.ash.drishti.common.FileDates;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.lakeio.RangeReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Archives as loader input: {@code .zip}, {@code .tar.gz}, {@code .tgz} and {@code .tar}, read in place (local or S3; nothing is
 * unpacked to disk). The rule is the one of every Drishti loader, Python and Java (docs/guides/DATA_GUIDE.md section 2.7; the Python
 * side is {@code tools/lake/archives.py}); both test suites run the same fixtures ({@code tools/lake/testdata/archives}):
 *
 * <ul>
 *   <li><b>Members.</b> A member named {@code *.jsonl}, {@code *.ndjson} or {@code *.json}, plain or {@code .gz}/{@code .zst}, is JSON
 *       Lines; a dataset's members are read in name order. Other files, hidden files and {@code __MACOSX/} are skipped with a note.</li>
 *   <li><b>Datasets.</b> No folders: one dataset named after the archive ({@code trade.tar.gz} → {@code trade}) unless
 *       {@code source.dataset} names it. Folders: exactly one level deep, each a dataset. Loose JSON Lines beside folders, a file deeper
 *       than one folder, a folder name that cannot name a table, or one no dataset is configured for: refused (every such folder
 *       listed).</li>
 *   <li><b>Guards.</b> Absolute names, drive letters and {@code ..} parts, links and devices, encrypted members and archives inside the
 *       archive are refused. The caps ({@link Limits}): entries, the declared sizes (at most {@code max-mb} and {@code max-ratio}
 *       times the archive's size, 1 MB always allowed), and while reading the bytes the archive expands to and, apart, the JSON Lines
 *       text of its compressed members, each held to the same caps.</li>
 * </ul>
 */
final class Archives {

    static final List<String> ARCHIVE_SUFFIXES = FileDates.ARCHIVE_SUFFIXES;
    static final List<String> JSONL_SUFFIXES = List.of(".jsonl", ".ndjson", ".json");
    static final List<String> NESTED_SUFFIXES = List.of(".zip", ".tar", ".tgz", ".tar.gz", ".tar.zst", ".tar.bz2", ".tar.xz", ".7z", ".rar");
    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");
    static final long FLOOR = 1 << 20;
    static final long DEFAULT_MAX_MB = 102400;
    static final double DEFAULT_MAX_RATIO = 200;
    static final int DEFAULT_MAX_MEMBERS = 100000;
    /** Names compare as Python's do: character (code point) by character. */
    static final Comparator<String> BY_NAME = FileDates.BY_NAME;

    private Archives() {}

    /** An archive the rule refuses; the message says which member and why. */
    static final class ArchiveException extends IOException {
        private static final long serialVersionUID = 1L;

        ArchiveException(String message) {
            super(message);
        }
    }

    /** The zip-bomb caps: the declared and the expanded bytes at most {@code maxBytes} and {@code maxRatio} times the archive. */
    record Limits(long maxBytes, double maxRatio, int maxMembers) {

        static Limits defaults(Function<String, String> env) {
            return new Limits(mb(env.apply("DRISHTI_ARCHIVE_MAX_MB"), DEFAULT_MAX_MB), num(env.apply("DRISHTI_ARCHIVE_MAX_RATIO"), DEFAULT_MAX_RATIO),
                    (int) num(env.apply("DRISHTI_ARCHIVE_MAX_MEMBERS"), DEFAULT_MAX_MEMBERS));
        }

        long cap(long archiveBytes) {
            return (long) Math.min(maxBytes, maxRatio * Math.max(archiveBytes, FLOOR));
        }

        private static long mb(String v, double dflt) {
            return (long) (num(v, dflt) * 1048576);
        }

        private static double num(String v, double dflt) {
            return v == null || v.isBlank() ? dflt : Double.parseDouble(v.trim());
        }
    }

    enum Kind { ZIP, TGZ, TAR, TARZIP }

    /**
     * One JSON Lines member: its archive (path, file name), its name in the archive (normalised), the dataset, its declared size; for a
     * zip, where its local header is, its method and compressed size.
     */
    record Member(String archive, String archiveName, String name, String dataset, long size, String raw, long offset, int method,
            long compressedSize, java.time.LocalDate date) {

        Member(String archive, String archiveName, String name, String dataset, long size, String raw, long offset, int method, long compressedSize) {
            this(archive, archiveName, name, dataset, size, raw, offset, method, compressedSize, null);
        }

        /** How a reject names it: {@code trade.zip!/trade/a.jsonl}. */
        String label() {
            return archiveName + "!/" + name;
        }

        Member in(String ds) {
            return new Member(archive, archiveName, name, ds, size, raw, offset, method, compressedSize, date);
        }
    }

    /** What an archive holds: its datasets (name → members in name order), whether they are folders, and the notes. */
    record Plan(String archive, String archiveName, Kind kind, long size, boolean folders, Map<String, List<Member>> datasets, List<String> notes) {

        /** Refuses every dataset not in {@code configured}, listing them all. */
        void require(Collection<String> configured) throws ArchiveException {
            List<String> unknown = datasets.keySet().stream().filter(d -> !configured.contains(d)).sorted(BY_NAME).toList();
            if (!unknown.isEmpty()) {
                String what = folders ? "folder" : "dataset";
                throw new ArchiveException(archiveName + ": " + what + (unknown.size() > 1 ? "s " : " ") + String.join(", ", unknown) + " match"
                        + (unknown.size() > 1 ? "" : "es") + " no configured dataset (" + (configured.isEmpty() ? "none" : String.join(", ", configured))
                        + "); nothing was loaded");
            }
        }

        List<Member> members() {
            List<Member> out = new ArrayList<>();
            datasets.values().forEach(out::addAll);
            return out;
        }
    }

    static boolean isArchive(String name) {
        return FileDates.isArchive(name);
    }

    /** {@code trade.tar.gz} → {@code trade}. */
    static String stem(String name) {
        String base = Inputs.name(name);
        String low = base.toLowerCase(Locale.ROOT);
        for (String s : ARCHIVE_SUFFIXES) {
            if (low.endsWith(s)) {
                return base.substring(0, base.length() - s.length());
            }
        }
        return base;
    }

    static boolean validName(String n) {
        return n != null && NAME.matcher(n).matches();
    }

    static boolean isJsonl(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String c : List.of("", ".gz", ".zst")) {
            for (String j : JSONL_SUFFIXES) {
                if (n.endsWith(j + c)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isNested(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return NESTED_SUFFIXES.stream().anyMatch(n::endsWith);
    }

    /** The member's path as parts ('\' read as '/', '.' parts and a leading './' dropped); refuses an unsafe name. */
    static List<String> safeParts(String raw) throws ArchiveException {
        String n = raw.replace('\\', '/');
        if (n.indexOf('\0') >= 0 || n.startsWith("/") || n.matches("^[A-Za-z]:.*")) {
            throw new ArchiveException("member '" + raw + "': an absolute path (unsafe; nothing was read)");
        }
        List<String> parts = new ArrayList<>();
        for (String p : n.split("/")) {
            if (!p.isEmpty() && !p.equals(".")) {
                parts.add(p);
            }
        }
        if (parts.contains("..")) {
            throw new ArchiveException("member '" + raw + "': a '..' in its path (unsafe; nothing was read)");
        }
        return parts;
    }

    static Kind kind(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".tar.zip") ? Kind.TARZIP : n.endsWith(".zip") ? Kind.ZIP : n.endsWith(".tar.gz") || n.endsWith(".tgz") ? Kind.TGZ : Kind.TAR;
    }

    /** One entry of the listing: its name as written, declared size, type ("file", "dir" or why it is refused), and for a zip where it is. */
    private record Entry(String raw, long size, String type, long offset, int method, long compressed) {}

    /** The archive's datasets by the rule, every check that needs no reading made. {@code dataset}: the name of a folderless one. */
    static Plan plan(FileAccess io, String path, long size, String dataset, Limits limits) throws IOException {
        String name = Inputs.name(path);
        Kind kind = kind(name);
        List<Entry> entries;
        try (RangeReader r = open(io, path, size)) {
            entries = kind == Kind.ZIP ? zipEntries(r, name, limits) : tarEntries(r, name, kind, limits, size);
        } catch (ArchiveException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ArchiveException(name + ": not a readable " + kind.name().toLowerCase(Locale.ROOT) + " archive (" + e.getMessage() + ")");
        }
        long declared = entries.stream().mapToLong(Entry::size).sum();
        if (declared > limits.cap(size)) {
            String why = declared > limits.maxBytes() ? "more than max-mb " + fmt(limits.maxBytes() / 1048576.0)
                    : "more than max-ratio " + fmt(limits.maxRatio()) + " times the archive's " + String.format(Locale.ROOT, "%,d", size) + " bytes";
            throw new ArchiveException(name + ": its entries expand to " + String.format(Locale.ROOT, "%,d", declared) + " bytes, " + why
                    + " (a zip bomb?); nothing was read");
        }
        if (kind != Kind.ZIP) {
            return tarFeed(path, name, kind, size, entries);
        }
        List<String> notes = new ArrayList<>();
        List<Member> loose = new ArrayList<>();
        Map<String, List<Member>> foldered = new TreeMap<>(BY_NAME);
        TreeSet<String> otherFolders = new TreeSet<>(BY_NAME);
        for (Entry e : entries) {
            List<String> parts = safeParts(e.raw());
            if (e.type().equals("dir") || parts.isEmpty()) {
                continue;
            }
            if (!e.type().equals("file")) {
                throw new ArchiveException(name + ": member '" + e.raw() + "' is " + e.type() + " (unsafe; nothing was read)");
            }
            if (parts.stream().anyMatch(p -> p.startsWith(".") || p.equals("__MACOSX"))) {
                notes.add(e.raw() + ": skipped (hidden)");
                continue;
            }
            if (parts.size() > 2) {
                throw new ArchiveException(name + ": member '" + e.raw() + "' is " + (parts.size() - 1) + " folders deep; folders are datasets, "
                        + "exactly one level deep");
            }
            String n = String.join("/", parts);
            if (isNested(n)) {
                throw new ArchiveException(name + ": member '" + e.raw() + "' is an archive inside the archive; archives are read one level "
                        + "deep (unpack it first)");
            }
            if (!isJsonl(n)) {
                notes.add(n + ": skipped (not JSON Lines)");
                if (parts.size() == 2) {
                    otherFolders.add(parts.get(0));
                }
                continue;
            }
            Member m = new Member(path, name, n, parts.size() == 1 ? "" : parts.get(0), e.size(), e.raw(), e.offset(), e.method(), e.compressed());
            if (parts.size() == 1) {
                loose.add(m);
            } else {
                foldered.computeIfAbsent(parts.get(0), k -> new ArrayList<>()).add(m);
            }
        }
        if (!loose.isEmpty() && !foldered.isEmpty()) {
            throw new ArchiveException(name + ": JSON Lines both loose (" + String.join(", ", loose.stream().map(Member::name).sorted(BY_NAME).limit(3)
                    .toList()) + ") and in folders (" + String.join(", ", foldered.keySet()) + "): a mixed archive is refused; put every file in "
                    + "a folder (one per dataset) or none");
        }
        if (loose.isEmpty() && foldered.isEmpty()) {
            throw new ArchiveException(name + ": no JSON Lines member (*.jsonl, *.ndjson, *.json, plain or .gz/.zst)");
        }
        for (String f : otherFolders) {
            if (!foldered.containsKey(f)) {
                notes.add(f + "/: holds no JSON Lines member, not a dataset");
            }
        }
        Map<String, List<Member>> datasets = new TreeMap<>(BY_NAME);
        if (!foldered.isEmpty()) {
            throw new ArchiveException(name + ": a .zip is one table and one business date, its JSON Lines files loose; this one has folders ("
                    + String.join(", ", foldered.keySet()) + "): several tables or dates go in a .tar of <table>/<YYYY-MM-DD>/ folders");
        }
        if (!foldered.isEmpty()) {
            if (dataset != null && !dataset.isBlank()) {
                throw new ArchiveException(name + ": --dataset names the one dataset of an archive without folders; this one has folders ("
                        + String.join(", ", foldered.keySet()) + "), each a dataset");
            }
            List<String> bad = foldered.keySet().stream().filter(f -> !validName(f)).map(f -> "'" + f + "'").toList();
            if (!bad.isEmpty()) {
                throw new ArchiveException(name + ": folder" + (bad.size() > 1 ? "s " : " ") + String.join(", ", bad) + " cannot name a table "
                        + "(letters, digits, '.', '_' and '-')");
            }
            foldered.forEach((d, ms) -> datasets.put(d, sorted(ms)));
        } else {
            String ds = dataset != null && !dataset.isBlank() ? dataset : stem(name);
            datasets.put(ds, sorted(loose.stream().map(m -> m.in(ds)).toList()));
        }
        return new Plan(path, name, kind, size, !foldered.isEmpty(), datasets, notes);
    }

    /**
     * A tar feed (DATA_GUIDE.md section 2.8): every member {@code <table>/<YYYY-MM-DD>/<file>.jsonl}, checked by {@link FileDates#tarLayout}
     * (every problem listed, the archive refused whole); one dataset per table, each member dated by its folder.
     */
    private static Plan tarFeed(String path, String name, Kind kind, long size, List<Entry> entries) throws IOException {
        Map<String, Entry> byName = new java.util.HashMap<>();
        List<String[]> listed = new ArrayList<>();
        for (Entry e : entries) {
            List<String> parts = safeParts(e.raw());
            listed.add(new String[] {e.raw(), e.type()});
            if (e.type().equals("file")) {
                byName.put(String.join("/", parts), e);
            }
        }
        FileDates.Layout lay = FileDates.tarLayout(listed, FileDates.today(), null);
        if (!lay.errors().isEmpty()) {
            throw new ArchiveException(name + ": refused, nothing was loaded (" + lay.errors().size() + " problem" + (lay.errors().size() > 1 ? "s" : "")
                    + "): " + String.join("; ", lay.errors()));
        }
        Map<String, List<Member>> datasets = new TreeMap<>(BY_NAME);
        lay.tables().forEach((table, days) -> {
            List<Member> ms = new ArrayList<>();
            days.forEach((day, names) -> names.forEach(n -> {
                Entry e = byName.get(n);
                ms.add(new Member(path, name, n, table, e.size(), e.raw(), e.offset(), e.method(), e.compressed(), day));
            }));
            datasets.put(table, ms);
        });
        for (List<Member> ms : datasets.values()) {
            for (Member m : ms) {
                if (isNested(m.name())) {
                    throw new ArchiveException(name + ": member '" + m.raw() + "' is an archive inside the archive; archives are read one level deep");
                }
            }
        }
        List<String> notes = new ArrayList<>(lay.ignored().stream().map(r -> r + ": skipped (litter)").toList());
        return new Plan(path, name, kind, size, true, datasets, notes);
    }

    private static List<Member> sorted(List<Member> ms) {
        List<Member> out = new ArrayList<>(ms);
        out.sort(Comparator.comparing(Member::name, BY_NAME));
        return out;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    static RangeReader open(FileAccess io, String path, long size) throws IOException {
        String p = io.resolve(Inputs.uri(path));
        return io.storage(p).open(p, size);
    }

    // ---------------------------------------------------------------------------------------------------------------- tar

    /** The tar's bytes as a stream: a .tar as it is, a .tgz decompressed, a .tar.zip's one .tar member inflated. */
    private static InputStream tarBytes(RangeReader r, String name, Kind kind, Limits limits, Meter meter, int blockBytes) throws IOException {
        if (kind == Kind.TARZIP) {
            List<Entry> files = zipEntries(r, name, limits).stream().filter(e -> e.type().equals("file")).toList();
            if (files.size() != 1 || !files.get(0).raw().toLowerCase(Locale.ROOT).endsWith(".tar")) {
                throw new ArchiveException(name + ": a .tar.zip holds exactly one .tar file");
            }
            Entry e = files.get(0);
            ByteBuffer h = read(r, e.offset(), 30);
            if (h.getInt(0) != 0x04034b50) {
                throw new ArchiveException(name + ": a broken local header");
            }
            long data = e.offset() + 30 + (h.getShort(26) & 0xffff) + (h.getShort(28) & 0xffff);
            InputStream raw = new SourceReader.RangeStream(r, data, data + e.compressed(), meter);
            return switch (e.method()) {
                case 0 -> raw;
                case 8 -> inflate(raw, blockBytes);
                default -> throw new ArchiveException(name + ": zip compression method " + e.method() + " (only stored and deflate are read)");
            };
        }
        InputStream raw = new SourceReader.RangeStream(r, 0, r.length(), meter);
        return kind == Kind.TGZ ? new GzipMembers(raw, blockBytes) : raw;
    }

    private static List<Entry> tarEntries(RangeReader r, String name, Kind kind, Limits limits, long size) throws IOException {
        List<Entry> out = new ArrayList<>();
        InputStream in = tarBytes(r, name, kind, limits, new Meter("list", 1), 1 << 16);
        TarStream t = new TarStream(in);
        long declared = 0;
        long cap = limits.cap(size);
        for (TarStream.Entry e = t.next(); e != null; e = t.next()) {
            if (out.size() >= limits.maxMembers()) {
                throw new ArchiveException(name + ": more than the cap of " + String.format(Locale.ROOT, "%,d", limits.maxMembers())
                        + " entries (max-members)");
            }
            String type = switch (e.type()) {
                case FILE -> "file";
                case DIR -> "dir";
                case LINK -> "a link";
                case OTHER -> "a device or special file";
            };
            declared += e.size();
            if (declared > cap) {
                break;                                       // the declared total is refused by the caller, before more is decompressed
            }
            out.add(new Entry(e.name(), e.size(), type, -1, -1, -1));
        }
        if (declared > cap) {
            out.add(new Entry("(more)", declared - out.stream().mapToLong(Entry::size).sum(), "file", -1, -1, -1));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------- zip

    private static final Charset CP437 = charset("IBM437");

    private static Charset charset(String n) {
        try {
            return Charset.forName(n);
        } catch (RuntimeException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    /** The central directory (ZIP64 included): every entry's name, sizes, flags and where its data is. */
    private static List<Entry> zipEntries(RangeReader r, String name, Limits limits) throws IOException {
        long len = r.length();
        int tail = (int) Math.min(len, 65557);
        ByteBuffer end = read(r, len - tail, tail);
        int at = -1;
        for (int i = tail - 22; i >= 0; i--) {
            if (end.getInt(i) == 0x06054b50) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            throw new IOException("no end of central directory");
        }
        long count = end.getShort(at + 10) & 0xffff;
        long cdSize = end.getInt(at + 12) & 0xffffffffL;
        long cdOffset = end.getInt(at + 16) & 0xffffffffL;
        if (at >= 20 && end.getInt(at - 20) == 0x07064b50) {           // ZIP64
            long eocd64 = end.getLong(at - 20 + 8);
            ByteBuffer z = read(r, eocd64, 56);
            if (z.getInt(0) != 0x06064b50) {
                throw new IOException("a broken ZIP64 end record");
            }
            count = z.getLong(32);
            cdSize = z.getLong(40);
            cdOffset = z.getLong(48);
        }
        if (count > limits.maxMembers()) {
            throw new ArchiveException(name + ": " + String.format(Locale.ROOT, "%,d", count) + " entries, more than the cap of "
                    + String.format(Locale.ROOT, "%,d", limits.maxMembers()) + " (max-members)");
        }
        if (cdSize > Integer.MAX_VALUE - 8) {
            throw new IOException("a central directory of " + cdSize + " bytes");
        }
        ByteBuffer cd = read(r, cdOffset, (int) cdSize);
        List<Entry> out = new ArrayList<>();
        int p = 0;
        for (long i = 0; i < count; i++) {
            if (cd.getInt(p) != 0x02014b50) {
                throw new IOException("a broken central directory at entry " + i);
            }
            int flags = cd.getShort(p + 8) & 0xffff;
            int method = cd.getShort(p + 10) & 0xffff;
            long csize = cd.getInt(p + 20) & 0xffffffffL;
            long usize = cd.getInt(p + 24) & 0xffffffffL;
            int nameLen = cd.getShort(p + 28) & 0xffff;
            int extraLen = cd.getShort(p + 30) & 0xffff;
            int commentLen = cd.getShort(p + 32) & 0xffff;
            long external = cd.getInt(p + 38) & 0xffffffffL;
            long offset = cd.getInt(p + 42) & 0xffffffffL;
            byte[] nb = new byte[nameLen];
            cd.get(p + 46, nb);
            String n = new String(nb, (flags & 0x800) != 0 ? StandardCharsets.UTF_8 : CP437);
            int x = p + 46 + nameLen;
            int xEnd = x + extraLen;
            while (x + 4 <= xEnd) {                                     // the ZIP64 extra field: the sizes and offset too large for 32 bits
                int id = cd.getShort(x) & 0xffff;
                int sz = cd.getShort(x + 2) & 0xffff;
                if (id == 0x0001) {
                    int q = x + 4;
                    if (usize == 0xffffffffL) {
                        usize = cd.getLong(q);
                        q += 8;
                    }
                    if (csize == 0xffffffffL) {
                        csize = cd.getLong(q);
                        q += 8;
                    }
                    if (offset == 0xffffffffL) {
                        offset = cd.getLong(q);
                    }
                }
                x += 4 + sz;
            }
            int mode = (int) (external >> 16) & 0xF000;
            String type = n.endsWith("/") ? "dir" : mode == 0xA000 ? "a link" : (flags & 1) != 0 ? "encrypted" : "file";
            out.add(new Entry(n, n.endsWith("/") ? 0 : usize, type, offset, method, csize));
            p += 46 + nameLen + extraLen + commentLen;
        }
        return out;
    }

    private static ByteBuffer read(RangeReader r, long at, int n) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(n);
        r.readFully(at, b);
        b.flip();
        return b.order(ByteOrder.LITTLE_ENDIAN);
    }

    // ---------------------------------------------------------------------------------------------------------------- reading

    /** Receives each member's JSON Lines (decompressed, held to the caps), in the order {@link #read} gives them. */
    interface MemberSink {
        void accept(Member m, InputStream jsonl) throws IOException, InterruptedException;
    }

    /**
     * Reads {@code members} of the archive {@code plan} describes, giving each to {@code sink}: a zip's in the order given (each read
     * at its offset), a tar's in the order they are stored (one pass over the archive). {@code meter}: the reads' throughput.
     */
    static void read(FileAccess io, Plan plan, List<Member> members, Limits limits, Meter meter, int blockBytes, MemberSink sink)
            throws IOException, InterruptedException {
        long cap = limits.cap(plan.size());
        long[] expanded = {0};
        long[] text = {0};
        try (RangeReader r = open(io, plan.archive(), plan.size())) {
            if (plan.kind() == Kind.ZIP) {
                for (Member m : members) {
                    ByteBuffer h = read(r, m.offset(), 30);
                    if (h.getInt(0) != 0x04034b50) {
                        throw new ArchiveException(m.label() + ": a broken local header");
                    }
                    long data = m.offset() + 30 + (h.getShort(26) & 0xffff) + (h.getShort(28) & 0xffff);
                    InputStream raw = new SourceReader.RangeStream(r, data, data + m.compressedSize(), meter);
                    InputStream entry = switch (m.method()) {
                        case 0 -> raw;
                        case 8 -> inflate(raw, blockBytes);
                        default -> throw new ArchiveException(m.label() + ": zip compression method " + m.method() + " (only stored and deflate are read)");
                    };
                    try (InputStream in = member(m, new Capped(entry, expanded, cap, plan.archiveName()), text, cap, blockBytes)) {
                        sink.accept(m, in);
                    }
                }
                return;
            }
            Map<String, Member> wanted = new java.util.HashMap<>();
            members.forEach(m -> wanted.put(m.raw(), m));
            InputStream tar = new Capped(tarBytes(r, plan.archiveName(), plan.kind(), limits, meter, blockBytes), expanded, cap, plan.archiveName());
            TarStream t = new TarStream(tar);
            int left = wanted.size();
            for (TarStream.Entry e = t.next(); e != null && left > 0; e = t.next()) {
                Member m = wanted.remove(e.name());
                if (m != null && e.type() == TarStream.Type.FILE) {
                    left--;
                    try (InputStream in = member(m, t.data(), text, cap, blockBytes)) {
                        sink.accept(m, in);
                    }
                }
            }
        }
    }

    private static InputStream inflate(InputStream raw, int blockBytes) {
        Inflater inf = new Inflater(true);
        return new InflaterInputStream(raw, inf, blockBytes) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    inf.end();
                }
            }
        };
    }

    /** A member's JSON Lines: its own .gz or .zst decompressed (and that text held to the caps), else as stored. */
    private static InputStream member(Member m, InputStream entry, long[] text, long cap, int blockBytes) throws IOException {
        String n = m.name().toLowerCase(Locale.ROOT);
        if (n.endsWith(".gz")) {
            return new Capped(new GzipMembers(entry, blockBytes), text, cap, m.label() + "'s JSON Lines");
        }
        if (n.endsWith(".zst")) {
            return new Capped(new com.github.luben.zstd.ZstdInputStream(entry), text, cap, m.label() + "'s JSON Lines");
        }
        return entry;
    }

    /** Counts a stream's bytes into a shared tally and stops it the moment the tally passes its cap. */
    static final class Capped extends InputStream {
        private final InputStream in;
        private final long[] tally;
        private final long cap;
        private final String what;

        Capped(InputStream in, long[] tally, long cap, String what) {
            this.in = in;
            this.tally = tally;
            this.cap = cap;
            this.what = what;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                tally[0] += n;
                if (tally[0] > cap) {
                    throw new ArchiveException(what + " expands past its cap of " + String.format(Locale.ROOT, "%,d", cap) + " bytes (max-mb, "
                            + "max-ratio): a zip bomb? Reading stopped");
                }
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
