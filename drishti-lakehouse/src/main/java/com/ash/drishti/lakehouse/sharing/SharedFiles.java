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
package com.ash.drishti.lakehouse.sharing;

import com.ash.drishti.lakeio.FileEntry;
import com.ash.drishti.lakeio.RangeReader;
import com.ash.drishti.lakeio.Storage;
import com.ash.drishti.lakeio.StorageProvider;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The files of shared tables, read over HTTPS by their pre-signed URLs, under the scheme {@value #SCHEME}: a file is named
 * {@code delta-sharing://<table key>/<file id>}, a name that stays the same while its URL is renewed, so the reader's caches (footers,
 * id indexes, documents) survive a renewal. The table ({@link SharingTableOps}) registers how to find a file's current URL; a URL within a
 * minute of its expiry, or one the store refuses (403), is renewed by asking the server for the table's files again, once, then the read is
 * retried. Ranged GETs of at least {@code 1 MiB}. Found by {@link java.util.ServiceLoader} as a {@link StorageProvider}.
 */
public final class SharedFiles implements StorageProvider {

    /** The scheme of shared files. */
    public static final String SCHEME = "delta-sharing";

    private static final int BLOCK = 1 << 20;
    private static final Map<String, Source> TABLES = new ConcurrentHashMap<>();
    private static final Storage STORAGE = new HttpStorage();

    /** How a table's files are found: the file's current URL, and a renewal of all of them. */
    interface Source {
        SharingClient.SharedFile file(String id) throws IOException;

        void renew() throws IOException;

        HttpClient http();
    }

    static void register(String key, Source s) {
        TABLES.put(key, s);
    }

    static void unregister(String key) {
        TABLES.remove(key);
    }

    static String path(String key, String fileId) {
        return SCHEME + "://" + key + "/" + java.net.URLEncoder.encode(fileId, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public String scheme() {
        return SCHEME;
    }

    @Override
    public Storage storage(String path) {
        return STORAGE;
    }

    /** The table and the file of a path. */
    private record Named(Source source, String id) {}

    private static Named named(String path) throws IOException {
        String rest = path.substring(SCHEME.length() + 3);
        int slash = rest.indexOf('/');
        Source s = slash < 0 ? null : TABLES.get(rest.substring(0, slash));
        if (s == null) {
            throw new FileNotFoundException("no open shared table serves " + path);
        }
        return new Named(s, java.net.URLDecoder.decode(rest.substring(slash + 1), java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Shared files: only read, by name; no listings. */
    private static final class HttpStorage implements Storage {

        @Override
        public String qualify(String path) {
            return path;
        }

        @Override
        public List<FileEntry> listFrom(String path) throws IOException {
            throw new FileNotFoundException("shared files are not listed: " + path);
        }

        @Override
        public FileEntry status(String path) throws IOException {
            Named n = named(path);
            return new FileEntry(path, n.source().file(n.id()).size(), 0);
        }

        @Override
        public RangeReader open(String path, long knownLength) throws IOException {
            Named n = named(path);
            long length = knownLength >= 0 ? knownLength : n.source().file(n.id()).size();
            return new Reader(n, length);
        }

        @Override
        public List<String> directories(String path) {
            return List.of();
        }

        @Override
        public boolean isDirectory(String path) {
            return false;
        }
    }

    /** Ranged GETs of one shared file, a block at a time. Used by one thread. */
    private static final class Reader implements RangeReader {
        private final Named named;
        private final long length;
        private byte[] block = new byte[0];
        private long blockAt = -1;

        Reader(Named named, long length) {
            this.named = named;
            this.length = length;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public int read(long position, ByteBuffer dst) throws IOException {
            if (position >= length) {
                return -1;
            }
            if (blockAt < 0 || position < blockAt || position >= blockAt + block.length) {
                long end = Math.min(length, position + Math.max(BLOCK, dst.remaining())) - 1;
                block = get(position, end);
                blockAt = position;
            }
            int off = (int) (position - blockAt);
            int n = Math.min(dst.remaining(), block.length - off);
            dst.put(block, off, n);
            return n;
        }

        private byte[] get(long from, long to) throws IOException {
            SharingClient.SharedFile f = named.source().file(named.id());
            if (System.currentTimeMillis() > f.expiresAtMs() - 60_000) {
                named.source().renew();
                f = named.source().file(named.id());
            }
            HttpResponse<byte[]> r = send(f.url(), from, to);
            if (r.statusCode() == 403 || r.statusCode() == 400 && new String(r.body(), java.nio.charset.StandardCharsets.UTF_8).contains("xpire")) {
                named.source().renew();                         // the URL expired early (or the store says so): once more, with a new one
                f = named.source().file(named.id());
                r = send(f.url(), from, to);
            }
            if (r.statusCode() == 206) {
                return r.body();
            }
            if (r.statusCode() == 200) {                        // a store that ignores Range: the whole file came
                byte[] all = r.body();
                return java.util.Arrays.copyOfRange(all, (int) Math.min(from, all.length), (int) Math.min(to + 1, all.length));
            }
            throw new IOException("reading the shared file " + named.id() + " answered HTTP " + r.statusCode() + " (" + com.ash.drishti.lakehouse.JsonHttp.strip(f.url())
                    + ")");
        }

        private HttpResponse<byte[]> send(String url, long from, long to) throws IOException {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).header("Range", "bytes=" + from + "-" + to)
                    .GET().build();
            try {
                return named.source().http().send(req, HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted reading a shared file", e);
            }
        }

        @Override
        public void close() {
            block = new byte[0];
        }
    }
}
