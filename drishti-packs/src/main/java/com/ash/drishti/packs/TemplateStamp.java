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
package com.ash.drishti.packs;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fingerprint a connector file generated from a pack template carries in its own text (CONNECTOR_FILES.md, "Upgrades"): one comment
 * line after the header,
 *
 * <pre>
 * # drishti-template: pack=market-risk template=1f2e3d4c5b6a7980 text=0a1b2c3d4e5f6071
 * </pre>
 *
 * naming the pack, the fingerprint of the template it was generated from ({@link ConnectorBootstrap#fingerprint}) and the etag of the text
 * below the line as it was written. A file whose text below the line still has that etag was never edited: at an upgrade it follows the
 * pack and is replaced in full by the new template. Kept in the text, the stamp travels wherever the text does: a file on disk, a shared
 * edit in the content store (the overlay keeps the whole text), the history, a copy to another server. Line endings do not count (a file
 * saved on Windows with CRLF is not an edit). Stateless.
 */
public final class TemplateStamp {

    public static final String PREFIX = "# drishti-template: ";
    private static final Pattern LINE = Pattern.compile("^# drishti-template: pack=(\\S*) template=(\\S*) text=(\\S*)\\s*$");

    /**
     * The stamp of a file.
     *
     * @param pack the pack whose template the file was generated from
     * @param template the fingerprint of that template
     * @param text the etag of the text below the stamp line when it was written
     */
    public record Stamp(String pack, String template, String text) {}

    private TemplateStamp() {
    }

    /** {@code text} with its stamp: after its leading comment lines (an earlier stamp replaced), naming the pack and the template's fingerprint. */
    public static String stamp(String text, String pack, String templateFingerprint) {
        String[] parts = split(text);
        String body = parts[1];
        return parts[0] + PREFIX + "pack=" + pack + " template=" + templateFingerprint + " text=" + ConnectorFiles.etag(normal(body)) + "\n" + body;
    }

    /** The stamp the text carries, or empty. */
    public static Optional<Stamp> of(String text) {
        if (text == null) {
            return Optional.empty();
        }
        for (String line : text.split("\r?\n", -1)) {
            if (!line.startsWith("#")) {
                break;
            }
            Matcher m = LINE.matcher(line);
            if (m.matches()) {
                return Optional.of(new Stamp(m.group(1), m.group(2), m.group(3)));
            }
        }
        return Optional.empty();
    }

    /** True when the text carries a stamp and the text below it is as it was written: never edited. */
    public static boolean pristine(String text) {
        Optional<Stamp> s = of(text);
        return s.isPresent() && s.get().text().equals(ConnectorFiles.etag(normal(split(text)[1])));
    }

    /** The text without its stamp line (what a person reads and edits). */
    public static String withoutStamp(String text) {
        String[] parts = split(text);
        return parts[0] + parts[1];
    }

    /** The leading comment lines without a stamp, and the text after them (after the stamp line when there is one). */
    private static String[] split(String text) {
        StringBuilder head = new StringBuilder();
        int at = 0;
        while (at < text.length() && text.charAt(at) == '#') {
            int nl = text.indexOf('\n', at);
            int end = nl < 0 ? text.length() : nl + 1;
            String line = text.substring(at, end);
            if (!line.startsWith(PREFIX)) {
                head.append(line);
            }
            at = end;
            if (line.startsWith(PREFIX)) {
                break;                                           // the stamp ends the header: what follows is the body as written
            }
        }
        if (head.length() > 0 && head.charAt(head.length() - 1) != '\n') {
            head.append('\n');
        }
        return new String[] {head.toString(), text.substring(at)};
    }

    private static String normal(String text) {
        return text.replace("\r\n", "\n");
    }
}
