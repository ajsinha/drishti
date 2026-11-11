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
package com.ash.drishti.iceberg.io;

import com.ash.drishti.iceberg.types.UnsupportedFeature;
import com.ash.drishti.lakeio.RangeReader;
import com.ash.drishti.lakeio.S3Settings;
import com.ash.drishti.lakeio.Storage;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.PositionOutputStream;
import org.apache.iceberg.io.SeekableInputStream;

/**
 * Apache Iceberg's {@link FileIO} over Drishti's file access: {@code java.nio} and the AWS SDK, no Hadoop, so it runs on Windows without
 * {@code winutils.exe}. Iceberg's own code reads and writes metadata and manifests through it. Parsed manifests are kept by Iceberg's
 * manifest cache, switched on by {@link #properties()}.
 *
 * <p>A REST catalog creates one per table ({@code io-impl}), passing the connector's settings and the credentials it vends for the table.
 */
public final class DrishtiFileIO implements FileIO {

    private static final long serialVersionUID = 1L;
    private static final Set<DrishtiFileIO> ALL = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Map<String, String> CACHE = Map.of("io.manifest.cache-enabled", "true",
            "io.manifest.cache.expiration-interval-ms", String.valueOf(24 * 3_600_000L),
            "io.manifest.cache.max-total-bytes", String.valueOf(Long.getLong("drishti.iceberg.manifest-cache-bytes", 512L << 20)),
            "io.manifest.cache.max-content-length", String.valueOf(64L << 20));

    private transient volatile FileAccess access;
    private transient boolean owned;
    private Map<String, String> properties = CACHE;
    private long expiresAt = Long.MAX_VALUE;

    /** For Iceberg's {@code io-impl}: {@link #initialize} follows. */
    public DrishtiFileIO() {
        ALL.add(this);
    }

    /** Over file access someone else closes. */
    public DrishtiFileIO(FileAccess access) {
        this();
        this.access = access;
    }

    /** Forgets every kept manifest. */
    public static void purge() {
        List<DrishtiFileIO> all;
        synchronized (ALL) {
            all = List.copyOf(ALL);
        }
        all.forEach(ManifestFiles::dropCache);
    }

    @Override
    public void initialize(Map<String, String> given) {
        Map<String, String> p = new HashMap<>(given);
        p.putAll(CACHE);
        properties = Map.copyOf(p);
        if (access != null) {
            return;
        }
        if (Boolean.parseBoolean(given.getOrDefault("s3.remote-signing-enabled", "false"))) {
            throw new UnsupportedFeature("S3 remote signing (s3.remote-signing-enabled)");
        }
        S3Settings s = S3Settings.from(given);
        if (given.containsKey("s3.access-key-id")) {
            s = s.withCredentials(given.get("s3.access-key-id"), given.getOrDefault("s3.secret-access-key", ""), given.getOrDefault("s3.session-token", ""));
        }
        if (given.containsKey("client.region") || given.containsKey("s3.path-style-access")) {
            s = s.withStore(s.endpoint(), given.getOrDefault("client.region", s.region()),
                    Boolean.parseBoolean(given.getOrDefault("s3.path-style-access", String.valueOf(s.pathStyle()))));
        }
        expiresAt = Long.parseLong(given.getOrDefault("s3.session-token-expires-at-ms", String.valueOf(Long.MAX_VALUE)));
        access = new FileAccess(s, Relocation.parse(given.getOrDefault("relocate", "")), given.get("lz4-decoder"));
        owned = true;
    }

    @Override
    public Map<String, String> properties() {
        return properties;
    }

    /** The files behind this. */
    public FileAccess access() {
        return access;
    }

    /** Whether the catalog's vended credentials expire within {@code withinMs}. */
    public boolean expiring(long withinMs) {
        return expiresAt != Long.MAX_VALUE && System.currentTimeMillis() > expiresAt - withinMs;
    }

    @Override
    public InputFile newInputFile(String path) {
        return new In(path, -1);
    }

    @Override
    public InputFile newInputFile(String path, long length) {
        return new In(path, length);
    }

    @Override
    public OutputFile newOutputFile(String path) {
        return new Out(path);
    }

    @Override
    public void deleteFile(String path) {
        try {
            String p = access.resolve(path);
            access.storage(p).delete(p);
        } catch (IOException e) {
            throw new UncheckedIOException("deleting " + path, e);
        }
    }

    @Override
    public void close() {
        if (owned && access != null) {
            access.close();
        }
    }

    private final class In implements InputFile {
        private final String path;
        private long length;

        In(String path, long length) {
            this.path = path;
            this.length = length;
        }

        @Override
        public long getLength() {
            if (length < 0) {
                try {
                    String p = access.resolve(path);
                    length = access.storage(p).status(p).size();
                } catch (FileNotFoundException e) {
                    throw new NotFoundException(e, "File does not exist: %s", access.resolve(path));
                } catch (IOException e) {
                    throw new UncheckedIOException("reading " + path, e);
                }
            }
            return length;
        }

        @Override
        public SeekableInputStream newStream() {
            try {
                String p = access.resolve(path);
                return new Stream(access.storage(p).open(p, length));
            } catch (FileNotFoundException e) {
                throw new NotFoundException(e, "File does not exist: %s", access.resolve(path));
            } catch (IOException e) {
                throw new UncheckedIOException("reading " + path, e);
            }
        }

        @Override
        public String location() {
            return path;
        }

        @Override
        public boolean exists() {
            try {
                String p = access.resolve(path);
                access.storage(p).status(p);
                return true;
            } catch (FileNotFoundException e) {
                return false;
            } catch (IOException e) {
                throw new UncheckedIOException("reading " + path, e);
            }
        }
    }

    private static final class Stream extends SeekableInputStream {
        private final RangeReader reader;
        private long position;

        Stream(RangeReader reader) {
            this.reader = reader;
        }

        @Override
        public long getPos() {
            return position;
        }

        @Override
        public void seek(long to) {
            position = to;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (position >= reader.length()) {
                return -1;
            }
            int n = reader.read(position, ByteBuffer.wrap(b, off, len));
            if (n > 0) {
                position += n;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    private final class Out implements OutputFile {
        private final String path;

        Out(String path) {
            this.path = path;
        }

        @Override
        public PositionOutputStream create() {
            return new Buffered(path, true);
        }

        @Override
        public PositionOutputStream createOrOverwrite() {
            return new Buffered(path, false);
        }

        @Override
        public String location() {
            return path;
        }

        @Override
        public InputFile toInputFile() {
            return newInputFile(path);
        }
    }

    /** Metadata and manifest files are small: held, then written as one file (an exclusive create when asked). */
    private final class Buffered extends PositionOutputStream {
        private final String path;
        private final boolean exclusive;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 16);
        private boolean closed;

        Buffered(String path, boolean exclusive) {
            this.path = path;
            this.exclusive = exclusive;
        }

        @Override
        public long getPos() {
            return bytes.size();
        }

        @Override
        public void write(int b) {
            bytes.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            bytes.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            String p = access.resolve(path);
            Storage s = access.storage(p);
            if (exclusive) {
                if (!s.createIfAbsent(p, bytes.toByteArray())) {
                    throw new AlreadyExistsException("File already exists: %s", path);
                }
            } else {
                try (OutputStream o = s.create(p)) {
                    bytes.writeTo(o);
                }
            }
        }
    }
}
