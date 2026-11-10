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

    /** A static field: its name, its type descriptor and whether it is final. */
    record StaticField(String name, String descriptor, boolean isFinal) {
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
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 7 -> classIndex[i] = in.readUnsignedShort();
                case 8, 16, 19, 20 -> in.readUnsignedShort();
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
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
        List<StaticField> statics = new ArrayList<>();
        int fields = in.readUnsignedShort();
        for (int i = 0; i < fields; i++) {
            int access = in.readUnsignedShort();
            String field = utf8[in.readUnsignedShort()];
            String descriptor = utf8[in.readUnsignedShort()];
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                in.readUnsignedShort();
                in.skipNBytes(in.readInt());
            }
            boolean synthetic = field.startsWith("$") || (access & 0x1000) != 0;
            if ((access & ACC_STATIC) != 0 && !synthetic) {
                statics.add(new StaticField(field, descriptor, (access & ACC_FINAL) != 0));
            }
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
}
