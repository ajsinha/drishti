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
package com.ash.drishti.iceberg.write;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.read.ParquetRowReader;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.types.Values;
import com.ash.drishti.lakeio.ParquetInput;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step I4's spike: parquet-java's writer with {@code PlainParquetConfiguration} and Drishti's own codec factory writes
 * a zstd (and a Snappy, GZIP) Parquet file with field ids and Iceberg metrics while a class loader refuses Hadoop's
 * file systems, {@code Shell} and {@code Configuration}; the file reads back by field id.
 */
public class WriterSpikeTest {                                    // public: the probe is called reflectively

    static final Set<String> FORBIDDEN = Set.of("org.apache.hadoop.fs.FileSystem", "org.apache.hadoop.fs.LocalFileSystem",
            "org.apache.hadoop.fs.RawLocalFileSystem", "org.apache.hadoop.fs.ChecksumFileSystem", "org.apache.hadoop.fs.FilterFileSystem",
            "org.apache.hadoop.fs.FileContext", "org.apache.hadoop.fs.AbstractFileSystem", "org.apache.hadoop.util.Shell",
            "org.apache.hadoop.fs.s3a.S3AFileSystem", "org.apache.hadoop.conf.Configuration");

    static final class Isolated extends URLClassLoader {
        final Set<String> refused = ConcurrentHashMap.newKeySet();

        Isolated(URL[] urls) {
            super(urls, ClassLoader.getPlatformClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            String outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
            if (FORBIDDEN.contains(outer)) {
                refused.add(name);
                throw new ClassNotFoundException("forbidden: " + name);
            }
            return super.loadClass(name, resolve);
        }
    }

    static final Schema SCHEMA = new Schema(0, List.of(NestedField.required(1, "id", Type.Primitive.STRING),
            NestedField.optional(2, "doc", Type.Primitive.STRING), NestedField.required(3, "business_date", Type.Primitive.DATE),
            NestedField.optional(4, "mtm", Type.Primitive.DOUBLE), NestedField.optional(5, "notional", Type.Primitive.decimal(20, 2)),
            NestedField.optional(6, "u", Type.Primitive.UUID),
            NestedField.optional(7, "cp", new Type.Struct(List.of(NestedField.optional(8, "code", Type.Primitive.STRING)))),
            NestedField.optional(9, "tags", new Type.ListType(10, false, Type.Primitive.STRING)),
            NestedField.optional(11, "lim", new Type.MapType(12, Type.Primitive.STRING, 13, false, Type.Primitive.LONG))));

    /** Writes and reads one file; runs inside the isolated loader. */
    public static String probe(String dir, String codec) throws Exception {
        FileAccess io = FileAccess.local();
        String path = Path.of(dir).toUri() + "f-" + codec + ".parquet";
        Map<String, String> props = Map.of("write.parquet.compression-codec", codec, "write.metadata.metrics.column.doc", "none",
                "write.parquet.row-group-size-bytes", "4096");
        DataFile f;
        try (ParquetDataWriter w = new ParquetDataWriter(io, path, SCHEMA, props, 0, new Object[] {20_000}, 1)) {
            for (int i = 0; i < 500; i++) {
                Map<String, Long> lim = new LinkedHashMap<>();
                lim.put("usd", (long) i);
                w.write(new Object[] {String.format("T-%04d", i), "{\"n\":" + i + "}", 20_000, i % 7 == 0 ? null : i * 1.5,
                    new BigDecimal(i + ".25"), new UUID(0, i), new Object[] {"CP-" + i % 3}, List.of("a", "b" + i), lim});
            }
            f = w.finish();
        }
        List<String> out = new ArrayList<>();
        out.add("rows " + f.recordCount());
        out.add("groups " + (f.splitOffsets().size() > 1));
        out.add("idlo " + Values.fromBytes(Type.Primitive.STRING, f.lowerBounds().get(1)));
        out.add("idhi " + Values.fromBytes(Type.Primitive.STRING, f.upperBounds().get(1)));
        out.add("nulls " + f.nullValueCounts().get(4));
        out.add("doc " + f.lowerBounds().containsKey(2) + f.valueCounts().containsKey(2));
        out.add("decimal " + Values.fromBytes(Type.Primitive.decimal(20, 2), f.upperBounds().get(5)));
        out.add("nested " + Values.fromBytes(Type.Primitive.STRING, f.upperBounds().get(8)));
        String p = io.resolve(path);
        try (ParquetRowReader r = new ParquetRowReader(ParquetInput.of(io.storage(p), p, f.fileSizeInBytes()), path, f.fileSizeInBytes(),
                SCHEMA.columns(), null, Map.of(), Expr.eq(1, "T-0333"), io.codecs(), false)) {
            while (r.hasNext()) {
                Object[] row = r.next().values();
                if ("T-0333".equals(row[0])) {
                    out.add("read " + row[1] + " " + row[3] + " " + row[4] + " " + row[5] + " " + ((Object[]) row[6])[0] + " " + row[7] + " " + row[8]);
                }
            }
        }
        out.add("size " + (f.fileSizeInBytes() == Files.size(Path.of(Path.of(dir).toString(), "f-" + codec + ".parquet"))));
        return String.join("; ", out);
    }

    @Test
    void parquetWritesWithNoHadoopClassLoaded(@TempDir Path dir) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String entry : System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")).split(File.pathSeparator, -1)) {
            if (!entry.isBlank()) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        }
        for (String codec : List.of("zstd", "snappy", "gzip", "uncompressed")) {
            try (Isolated loader = new Isolated(urls.toArray(new URL[0]))) {
                Class<?> self = loader.loadClass(WriterSpikeTest.class.getName());
                String result;
                try {
                    result = (String) self.getMethod("probe", String.class, String.class).invoke(null, dir.toString(), codec);
                } catch (InvocationTargetException e) {
                    throw new AssertionError("writing " + codec + " failed; refused: " + loader.refused, e.getCause());
                }
                assertThat(loader.refused).as(codec).isEmpty();
                assertThat(result).contains("rows 500", "groups true", "idlo T-0000", "idhi T-0499", "nulls 72", "doc falsefalse", "decimal 499.25",
                        "nested CP-2", "read {\"n\":333} 499.5 333.25 00000000-0000-0000-0000-00000000014d CP-0 [a, b333] {usd=333}", "size true");
            }
        }
    }
}
