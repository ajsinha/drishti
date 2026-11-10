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
package com.ash.drishti.module.tool;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the conformance checks need of a class file, read from its bytes (no class is loaded): the types it names (every constant-pool
 * string, which covers imports, signatures and descriptors) and its static fields.
 */
final class ClassFiles {

    /**
     * A static field: its name, its type descriptor, whether it is final, and whether the class's initialiser only ever stores an
     * unmodifiable collection in it ({@code List.of}, {@code Map.of}, {@code Set.copyOf}, {@code Collections.unmodifiable...},
     * {@code Stream.toList} ...): a constant, safe for two people at once.
     */
    record StaticField(String name, String descriptor, boolean isFinal, boolean unmodifiable, boolean engineWide) {
    }

    /** The kernel API's mark of engine-wide state on purpose ({@code @EngineWide}). */
    static final String ENGINE_WIDE = "Lcom/ash/drishti/kernel/api/module/EngineWide;";

    /** True when an annotations attribute holds an annotation of the type. */
    private static boolean annotated(byte[] body, String[] utf8, String type) throws IOException {
        DataInputStream a = new DataInputStream(new ByteArrayInputStream(body));
        int n = a.readUnsignedShort();
        boolean found = false;
        for (int i = 0; i < n; i++) {
            found |= type.equals(utf8[annotation(a)]);
        }
        return found;
    }

    /** Reads one annotation; returns its type's constant index. */
    private static int annotation(DataInputStream a) throws IOException {
        int typeIndex = a.readUnsignedShort();
        int pairs = a.readUnsignedShort();
        for (int i = 0; i < pairs; i++) {
            a.readUnsignedShort();
            elementValue(a);
        }
        return typeIndex;
    }

    private static void elementValue(DataInputStream a) throws IOException {
        int tag = a.readUnsignedByte();
        switch (tag) {
            case 'e' -> {
                a.readUnsignedShort();
                a.readUnsignedShort();
            }
            case '@' -> annotation(a);
            case '[' -> {
                int n = a.readUnsignedShort();
                for (int i = 0; i < n; i++) {
                    elementValue(a);
                }
            }
            default -> a.readUnsignedShort();                       // a constant or a class
        }
    }

    /** A class file's name, the types it names and its static fields. */
    record Info(String name, Set<String> types, List<StaticField> statics) {
    }

    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]*(?:/[a-z0-9_]+)+/[A-Za-z0-9_$]+");
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_FINAL = 0x0010;

    private ClassFiles() {
    }

    /** Reads one class file's bytes. */
    static Info read(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classIndex = new int[count];
        int[] refClass = new int[count];
        int[] refNameType = new int[count];
        int[] ntName = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 7 -> classIndex[i] = in.readUnsignedShort();
                case 8, 16, 19, 20 -> in.readUnsignedShort();
                case 9, 10, 11 -> {
                    refClass[i] = in.readUnsignedShort();
                    refNameType[i] = in.readUnsignedShort();
                }
                case 12 -> {
                    ntName[i] = in.readUnsignedShort();
                    in.readUnsignedShort();
                }
                case 3, 4, 17, 18 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;                                                  // a long or double takes two entries
                }
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                default -> throw new IOException("unknown constant tag " + tag);
            }
        }
        in.readUnsignedShort();
        String name = utf8[classIndex[in.readUnsignedShort()]];
        in.readUnsignedShort();
        int interfaces = in.readUnsignedShort();
        for (int i = 0; i < interfaces; i++) {
            in.readUnsignedShort();
        }
        List<String[]> found = new ArrayList<>();
        int fields = in.readUnsignedShort();
        for (int i = 0; i < fields; i++) {
            int access = in.readUnsignedShort();
            String field = utf8[in.readUnsignedShort()];
            String descriptor = utf8[in.readUnsignedShort()];
            int attributes = in.readUnsignedShort();
            boolean engineWide = false;
            for (int a = 0; a < attributes; a++) {
                String attr = utf8[in.readUnsignedShort()];
                byte[] body = in.readNBytes(in.readInt());
                if ("RuntimeInvisibleAnnotations".equals(attr) || "RuntimeVisibleAnnotations".equals(attr)) {
                    engineWide |= annotated(body, utf8, ENGINE_WIDE);
                }
            }
            boolean synthetic = field.startsWith("$") || (access & 0x1000) != 0;
            if ((access & ACC_STATIC) != 0 && !synthetic) {
                found.add(new String[] {field, descriptor, (access & ACC_FINAL) != 0 ? "final" : "", engineWide ? "engine-wide" : ""});
            }
        }
        // the initialiser: which static fields only ever receive an unmodifiable collection
        java.util.Map<String, Boolean> constant = new java.util.HashMap<>();
        int methods = in.readUnsignedShort();
        for (int i = 0; i < methods; i++) {
            in.readUnsignedShort();
            String method = utf8[in.readUnsignedShort()];
            in.readUnsignedShort();
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                String attr = utf8[in.readUnsignedShort()];
                byte[] body = in.readNBytes(in.readInt());
                if ("<clinit>".equals(method) && "Code".equals(attr)) {
                    DataInputStream c = new DataInputStream(new ByteArrayInputStream(body));
                    c.readUnsignedShort();
                    c.readUnsignedShort();
                    byte[] code = c.readNBytes(c.readInt());
                    initialiser(code, utf8, classIndex, refClass, refNameType, ntName, name, constant);
                }
            }
        }
        List<StaticField> statics = new ArrayList<>();
        for (String[] f : found) {
            statics.add(new StaticField(f[0], f[1], !f[2].isEmpty(), Boolean.TRUE.equals(constant.get(f[0])), !f[3].isEmpty()));
        }
        Set<String> types = new TreeSet<>();
        for (String s : utf8) {
            if (s != null) {
                Matcher m = TYPE.matcher(s);
                while (m.find()) {
                    types.add(m.group());
                }
            }
        }
        return new Info(name, types, statics);
    }

    private static final Set<String> UNMODIFIABLE_OWNERS = Set.of("java/util/List", "java/util/Set", "java/util/Map");

    /** Walks an initialiser's bytecode: a field is a constant when every store to it follows a call that makes an unmodifiable collection. */
    private static void initialiser(byte[] code, String[] utf8, int[] classIndex, int[] refClass, int[] refNameType, int[] ntName, String self,
            java.util.Map<String, Boolean> constant) {
        boolean lastUnmodifiable = false;
        int pc = 0;
        while (pc < code.length) {
            int op = code[pc] & 0xff;
            int len = length(code, pc, op);
            if (op == 0xb3) {                                         // putstatic
                int ref = ((code[pc + 1] & 0xff) << 8) | (code[pc + 2] & 0xff);
                if (utf8[classIndex[refClass[ref]]].equals(self)) {
                    String f = utf8[ntName[refNameType[ref]]];
                    constant.merge(f, lastUnmodifiable, Boolean::logicalAnd);
                }
                lastUnmodifiable = false;
            } else if (op == 0xb8 || op == 0xb9 || op == 0xb6) {      // invokestatic, invokeinterface, invokevirtual
                int ref = ((code[pc + 1] & 0xff) << 8) | (code[pc + 2] & 0xff);
                String owner = utf8[classIndex[refClass[ref]]];
                String m = utf8[ntName[refNameType[ref]]];
                lastUnmodifiable = (UNMODIFIABLE_OWNERS.contains(owner) && (m.equals("of") || m.equals("copyOf") || m.equals("ofEntries")))
                        || (owner.equals("java/util/Collections") && (m.startsWith("unmodifiable") || m.startsWith("empty") || m.startsWith("singleton")))
                        || (owner.equals("java/util/stream/Stream") && m.equals("toList"));
            } else if (op != 0xc0) {                                  // a checkcast keeps what came before
                lastUnmodifiable = false;
            }
            pc += len;
        }
    }

    /** The length of the instruction at {@code pc} (JVMS chapter 6). */
    private static int length(byte[] code, int pc, int op) {
        switch (op) {
            case 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc:
                return 2;
            case 0x11, 0x13, 0x14, 0x84, 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f, 0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8,
                    0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7:
                return 3;
            case 0xc5:
                return 4;
            case 0xb9, 0xba, 0xc8, 0xc9:
                return 5;
            case 0xc4:                                                // wide
                return (code[pc + 1] & 0xff) == 0x84 ? 6 : 4;
            case 0xaa: {                                              // tableswitch
                int p = (pc + 4) & ~3;
                int low = readInt(code, p + 4);
                int high = readInt(code, p + 8);
                return p + 12 + 4 * (high - low + 1) - pc;
            }
            case 0xab: {                                              // lookupswitch
                int p = (pc + 4) & ~3;
                int n = readInt(code, p + 4);
                return p + 8 + 8 * n - pc;
            }
            default:
                return 1;
        }
    }

    private static int readInt(byte[] b, int p) {
        return ((b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
    }
}
