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
package com.ash.drishti.identity.collab;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Hashes and the decorators that keep seals current: a thread store that seals every {@code append} (the thread's revision count and
 * head hash, the live comment row) and a hold store that seals every hold row and the number of holds placed.
 */
public final class Seals {

    private Seals() {}

    public static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String join(Object... parts) {
        StringBuilder b = new StringBuilder();
        for (Object o : parts) {
            b.append(o == null ? "" : o instanceof Instant i ? Long.toString(i.toEpochMilli()) : o).append('\u001f');
        }
        return b.toString();
    }

    /** The hash of a comment's live row: what readers are shown (text, author, state, the masked ranges). */
    public static String commentHash(Comment c) {
        return sha(join(c.id(), c.threadId(), c.author(), c.createdAt(), c.editedAt(), c.revision(), c.body(), c.state(), c.stateReason(),
                c.maskedSpans().stream().map(s -> s.start() + "-" + s.end()).toList()));
    }

    /** The hash of a hold row (released state included). */
    public static String holdHash(Hold h) {
        return sha(join(h.id(), h.scope(), h.kind(), h.entityId(), h.username(), h.threadId(), h.from(), h.to(), h.reason(), h.placedBy(),
                h.placedAt(), h.releasedBy(), h.releasedAt()));
    }

    /** The thread store, sealing every {@code append}. */
    public static ThreadStore sealing(ThreadStore inner, Seal.Store seals) {
        return (ThreadStore) Proxy.newProxyInstance(ThreadStore.class.getClassLoader(), new Class<?>[] {ThreadStore.class}, new Wrapped(inner) {
            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) throws Throwable {
            Object result = call(inner, m, args);
            if ("append".equals(m.getName())) {
                Comment c = (Comment) args[0];
                Revision r = (Revision) result;
                String key = "thread:" + c.threadId();
                long count = seals.get(key).map(Seal::count).orElseGet(() -> (long) inner.chain(c.threadId()).size() - 1) + 1;
                seals.put(key, new Seal(count, r.hash()));
                seals.put("comment:" + c.id(), new Seal(c.revision(), commentHash(c)));
            }
            return result;
            }
        });
    }

    /** The hold store, sealing every {@code place} and {@code release}. */
    public static HoldStore sealing(HoldStore inner, Seal.Store seals) {
        return (HoldStore) Proxy.newProxyInstance(HoldStore.class.getClassLoader(), new Class<?>[] {HoldStore.class}, new Wrapped(inner) {
            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) throws Throwable {
            Object result = call(inner, m, args);
            if ("place".equals(m.getName())) {
                Hold h = (Hold) result;
                seals.put("hold:" + h.id(), new Seal(h.id(), holdHash(h)));
                seals.put("holds:head", new Seal(inner.list(false).size(), ""));
            } else if ("release".equals(m.getName()) && Boolean.TRUE.equals(result)) {
                inner.find((Long) args[0]).ifPresent(h -> seals.put("hold:" + h.id(), new Seal(h.id(), holdHash(h))));
            }
            return result;
            }
        });
    }

    /** A decorator's handler, which knows what it wraps. */
    private abstract static class Wrapped implements java.lang.reflect.InvocationHandler {
        final Object target;

        Wrapped(Object target) {
            this.target = target;
        }
    }

    /** The store a sealing decorator wraps (the store itself when it is not one); for tests that reach into a concrete store. */
    public static Object unwrap(Object store) {
        return store != null && Proxy.isProxyClass(store.getClass()) && Proxy.getInvocationHandler(store) instanceof Wrapped w ? w.target : store;
    }

    private static Object call(Object target, java.lang.reflect.Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
