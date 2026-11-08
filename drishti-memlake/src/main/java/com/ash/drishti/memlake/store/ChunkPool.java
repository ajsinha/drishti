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
package com.ash.drishti.memlake.store;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A memory store's chunks, off the Java heap, on Java 21 (docs/architecture/DATA_ARCHITECTURE.md, section 3a): direct {@link ByteBuffer}s
 * of one size, allocated on demand up to a hard cap and <b>reused</b>. A file's chunks come back to the free list the moment the file is
 * deleted or evicted, so the cap is exact and never waits for a garbage collection; chunks beyond what is needed (the cap lowered, or
 * {@link #trim(long)} after an idle spell) are released at once with {@code sun.misc.Unsafe.invokeCleaner} ({@code jdk.unsupported}),
 * not left to the collector. No preview feature and nothing of Java 25.
 *
 * <p>Thread-safe; every method is short and holds one lock.
 */
public final class ChunkPool {

    /** {@code Unsafe.invokeCleaner(ByteBuffer)}, or null when this JVM does not offer it (the buffer is then left to the collector). */
    private static final MethodHandle CLEANER = cleaner();

    private final String name;
    private final int chunkBytes;
    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<ByteBuffer> free = new ArrayDeque<>();
    private long capBytes;
    private long allocatedBytes;
    private long inUseBytes;
    private long releasedBytes;
    private volatile String directLimit;

    /**
     * @param name the store's name, for messages
     * @param capBytes the hard cap: no chunk is allocated past it
     * @param chunkBytes the size of every chunk (1 MB by default)
     */
    public ChunkPool(String name, long capBytes, int chunkBytes) {
        if (chunkBytes < 4096) {
            throw new IllegalArgumentException("memory store " + name + ": chunk-kb must be at least 4");
        }
        this.name = name;
        this.chunkBytes = chunkBytes;
        this.capBytes = Math.max(0, capBytes);
    }

    /** The size of a chunk in bytes. */
    public int chunkBytes() {
        return chunkBytes;
    }

    /** A chunk, cleared, or null when the cap (or the JVM's direct-memory limit) leaves no room for one. */
    public ByteBuffer take() {
        lock.lock();
        try {
            ByteBuffer b = free.pollFirst();
            if (b == null) {
                if (allocatedBytes + chunkBytes > capBytes) {
                    return null;
                }
                try {
                    b = ByteBuffer.allocateDirect(chunkBytes);
                } catch (OutOfMemoryError e) {
                    // -XX:MaxDirectMemorySize below the stores' caps: Health turns red on this (the launchers add the caps to it)
                    directLimit = "the JVM's direct-memory limit was reached at " + (allocatedBytes >> 20) + " MB of memory store " + name
                            + " (raise -XX:MaxDirectMemorySize by the stores' max-mb)";
                    return null;
                }
                allocatedBytes += chunkBytes;
            }
            b.clear();
            inUseBytes += chunkBytes;
            return b;
        } finally {
            lock.unlock();
        }
    }

    /** Gives a chunk back: to the free list, or released at once when the pool is over its cap. */
    public void give(ByteBuffer b) {
        if (b == null) {
            return;
        }
        lock.lock();
        try {
            inUseBytes -= chunkBytes;
            if (allocatedBytes > capBytes) {
                release(b);
            } else {
                free.addFirst(b);
            }
        } finally {
            lock.unlock();
        }
    }

    /** A new cap: lowered, the free chunks above it are released at once (chunks in use are released as their files go). */
    public void cap(long bytes) {
        lock.lock();
        try {
            capBytes = Math.max(0, bytes);
            while (allocatedBytes > capBytes && !free.isEmpty()) {
                release(free.pollFirst());
            }
        } finally {
            lock.unlock();
        }
    }

    /** Releases free chunks until at most {@code keepFreeBytes} of them stay allocated; the bytes released. */
    public long trim(long keepFreeBytes) {
        lock.lock();
        try {
            long before = releasedBytes;
            while ((long) free.size() * chunkBytes > Math.max(0, keepFreeBytes)) {
                release(free.pollFirst());
            }
            return releasedBytes - before;
        } finally {
            lock.unlock();
        }
    }

    /** Must hold the lock. */
    private void release(ByteBuffer b) {
        allocatedBytes -= chunkBytes;
        releasedBytes += chunkBytes;
        free(b);
    }

    /** Frees a direct buffer's memory now (no collection needed); a JVM without the cleaner leaves it to the collector. */
    static void free(ByteBuffer b) {
        if (CLEANER != null && b.isDirect()) {
            try {
                CLEANER.invokeExact(b);
            } catch (Throwable t) {
                // a slice or a duplicate cannot be cleaned: the collector frees it with its parent
            }
        }
    }

    private static MethodHandle cleaner() {
        try {
            Class<?> unsafe = Class.forName("sun.misc.Unsafe");
            Field f = unsafe.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object u = f.get(null);
            return MethodHandles.lookup().findVirtual(unsafe, "invokeCleaner", MethodType.methodType(void.class, ByteBuffer.class)).bindTo(u);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Whether chunks are freed explicitly here (false only on a JVM without {@code jdk.unsupported}). */
    public static boolean explicitRelease() {
        return CLEANER != null;
    }

    /** The hard cap. */
    public long capBytes() {
        lock.lock();
        try {
            return capBytes;
        } finally {
            lock.unlock();
        }
    }

    /** Chunks held by files (and by writes in progress), in bytes. */
    public long inUseBytes() {
        lock.lock();
        try {
            return inUseBytes;
        } finally {
            lock.unlock();
        }
    }

    /** Chunks allocated off the heap: in use plus the free list, in bytes. What Native Memory Tracking shows for the store. */
    public long allocatedBytes() {
        lock.lock();
        try {
            return allocatedBytes;
        } finally {
            lock.unlock();
        }
    }

    /** Bytes given back to the operating system so far. */
    public long releasedBytes() {
        lock.lock();
        try {
            return releasedBytes;
        } finally {
            lock.unlock();
        }
    }

    /** Why the last allocation failed below the cap (the JVM's direct-memory limit), or null. */
    public String directLimitProblem() {
        return directLimit;
    }
}
