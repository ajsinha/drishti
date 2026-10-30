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
package com.ash.drishti.api;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Maps a region of a file read-only and unmaps it on request, never leaving it to the garbage collector when the JVM can do better
 * (VAR_GUIDE.md section 26: a hot file is unmapped when its date is evicted, so its pages and, on Windows, the file itself are released at
 * once). On Java 22 and later the region is mapped in a shared {@code Arena} of the foreign-memory API (looked up at run time: the code
 * is compiled for Java 21, where that API is a preview); closing the arena unmaps it, and a read after that fails with an exception
 * instead of touching freed memory. On Java 21 the mapping is a {@link MappedByteBuffer} released through the JDK's cleaner. Either way the
 * callers (the VaR hot files' MappedMatrix, the connectors' {@link MappedIdFile}) count their readers and unmap only when none is reading.
 */
public final class FileMaps {

    /** A mapped region: its little-endian buffer and how to release it. */
    public record Mapping(ByteBuffer buffer, Releaser releaser) {
        public void release() {
            releaser.release();
        }
    }

    @FunctionalInterface
    public interface Releaser {
        void release();
    }

    private static final Method ARENA_SHARED;
    private static final Method ARENA_CLOSE;
    private static final Method CHANNEL_MAP;
    private static final Method SEGMENT_BUFFER;
    private static final MethodHandle CLEANER;

    static {
        Method shared = null;
        Method close = null;
        Method map = null;
        Method buffer = null;
        if (Runtime.version().feature() >= 22) {
            try {
                Class<?> arena = Class.forName("java.lang.foreign.Arena");
                Class<?> segment = Class.forName("java.lang.foreign.MemorySegment");
                shared = arena.getMethod("ofShared");
                close = arena.getMethod("close");
                map = FileChannel.class.getMethod("map", FileChannel.MapMode.class, long.class, long.class, arena);
                buffer = segment.getMethod("asByteBuffer");
            } catch (ReflectiveOperationException | RuntimeException e) {
                shared = null;
            }
        }
        ARENA_SHARED = shared;
        ARENA_CLOSE = shared == null ? null : close;
        CHANNEL_MAP = shared == null ? null : map;
        SEGMENT_BUFFER = shared == null ? null : buffer;
        CLEANER = shared == null ? cleaner() : null;
    }

    private FileMaps() {}

    /** How regions are mapped and released on this JVM (for the status page and the log). */
    public static String method() {
        return ARENA_SHARED != null ? "shared arena (unmapped on close)" : CLEANER != null ? "mapped buffer (unmapped on close)"
                : "mapped buffer (released by the garbage collector)";
    }

    /** Maps {@code length} bytes (at most {@link Integer#MAX_VALUE}) of the channel's file from {@code offset}, read-only. */
    public static Mapping map(FileChannel ch, long offset, long length) throws IOException {
        if (ARENA_SHARED != null) {
            try {
                Object arena = ARENA_SHARED.invoke(null);
                try {
                    Object segment = CHANNEL_MAP.invoke(ch, FileChannel.MapMode.READ_ONLY, offset, length, arena);
                    ByteBuffer b = ((ByteBuffer) SEGMENT_BUFFER.invoke(segment)).order(ByteOrder.LITTLE_ENDIAN);
                    return new Mapping(b, () -> closeArena(arena));
                } catch (InvocationTargetException | IllegalAccessException | RuntimeException e) {
                    closeArena(arena);
                    throw e;
                }
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof IOException io) {
                    throw io;
                }
                throw new IOException("cannot map " + length + " bytes: " + e.getCause(), e.getCause());
            } catch (IllegalAccessException e) {
                throw new IOException("cannot map " + length + " bytes: " + e, e);
            }
        }
        MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, offset, length);
        m.order(ByteOrder.LITTLE_ENDIAN);
        return new Mapping(m, () -> clean(m));
    }

    private static void closeArena(Object arena) {
        try {
            ARENA_CLOSE.invoke(arena);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // already closed, or a reader still inside it: the guard of MappedMatrix keeps that from happening
        }
    }

    private static void clean(MappedByteBuffer m) {
        if (CLEANER == null) {
            return;                                                    // the collector releases it when nothing refers to it
        }
        try {
            CLEANER.invokeExact((ByteBuffer) m);
        } catch (Throwable t) {
            // not releasable here: the collector will
        }
    }

    private static MethodHandle cleaner() {
        try {
            Class<?> u = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field f = u.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            return MethodHandles.lookup().findVirtual(u, "invokeCleaner", MethodType.methodType(void.class, ByteBuffer.class)).bindTo(unsafe);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
