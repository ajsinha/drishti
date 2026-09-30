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
package com.ash.drishti.rachana.parse;

import java.util.regex.Pattern;

/**
 * Markdown Sutras ({@code *.sutra.md}), the standard Sutra format: a readable document whose one fenced
 * {@code ```sutra} block holds the layout. Everything outside the block is prose for people and for AI
 * assistants; the engine ignores it. Lines outside the block are blanked rather than removed, so problem
 * locations keep the line numbers of the Markdown file. Stateless and thread-safe.
 */
public final class SutraMarkdown {

    private static final Pattern OPEN = Pattern.compile("^(```|~~~)\\s*sutra\\s*$");
    private static final Pattern FILE = Pattern.compile(".*\\.sutra\\.md$");

    private SutraMarkdown() {
    }

    /** True for a {@code *.sutra.md} file name. */
    public static boolean isFile(String name) {
        return FILE.matcher(name).matches();
    }

    /** True when {@code text} is a Markdown Sutra (it has a {@code ```sutra} fence). */
    public static boolean isMarkdown(String text) {
        for (String line : text.split("\\R", -1)) {
            if (OPEN.matcher(line.strip()).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The YAML inside the {@code ```sutra} fence, with every other line blank so line numbers line up.
     *
     * @return the Sutra YAML, or {@code null} when there is no fence or more than one
     */
    public static String yaml(String markdown) {
        String[] lines = markdown.split("\\R", -1);
        StringBuilder out = new StringBuilder(markdown.length());
        String fence = null;
        int blocks = 0;
        for (String line : lines) {
            String t = line.strip();
            if (fence == null) {
                var m = OPEN.matcher(t);
                if (m.matches()) {
                    fence = m.group(1);
                    blocks++;
                }
                out.append('\n');
            } else if (t.equals(fence)) {
                fence = null;
                out.append('\n');
            } else {
                out.append(line).append('\n');
            }
        }
        return blocks == 1 && fence == null ? out.toString() : null;
    }

    /**
     * Wraps plain Sutra YAML as a Markdown Sutra with a heading and a short note, for layouts written by
     * inference or other tools.
     */
    public static String wrap(String name, int version, String note, String yaml) {
        String body = yaml.endsWith("\n") ? yaml : yaml + "\n";
        return "# " + name + " (`" + name + "` v" + version + ")\n\n" + note + "\n\n```sutra\n" + body + "```\n\n"
                + "## Notes\n\nWhy this layout: what the reader looks at first, and what each panel answers.\n";
    }
}
