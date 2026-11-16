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

/** Walks simple document paths: {@code $.a.b[2].c}, {@code legs[-1]}. No allocation beyond substrings. */
final class PathWalker {

    private PathWalker() {}

    static DataNode walk(DataNode root, String path) {
        if (path == null || path.isEmpty() || "$".equals(path)) {
            return root;
        }
        int i = path.startsWith("$.") ? 2 : path.startsWith("$") ? 1 : 0;
        DataNode node = root;
        int n = path.length();
        while (i < n && !node.isMissing()) {
            char c = path.charAt(i);
            if (c == '.') {
                i++;
            } else if (c == '[') {
                int end = path.indexOf(']', i);
                if (end < 0) {
                    return DataNode.missing();
                }
                String inner = path.substring(i + 1, end).trim();
                if (!inner.isEmpty() && (inner.charAt(0) == '\'' || inner.charAt(0) == '"')) {
                    node = node.get(inner.substring(1, inner.length() - 1));
                } else {
                    try {
                        node = node.get(Integer.parseInt(inner));
                    } catch (NumberFormatException e) {
                        return DataNode.missing();
                    }
                }
                i = end + 1;
            } else {
                int j = i;
                while (j < n && path.charAt(j) != '.' && path.charAt(j) != '[') {
                    j++;
                }
                node = node.get(path.substring(i, j));
                i = j;
            }
        }
        return node;
    }
}
