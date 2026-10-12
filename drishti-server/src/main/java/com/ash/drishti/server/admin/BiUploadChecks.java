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
package com.ash.drishti.server.admin;

import com.ash.drishti.server.bi.board.BoardParser;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.report.ReportParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks an uploaded shipped-BI file before anything is written: the file name ({@code <name>.<kind>.yaml}), UTF-8 text, a YAML mapping
 * whose own key (for a dataset, {@code dataset: <name>}) agrees with the file name, and the parser of its kind (datasets, reports,
 * boards) finding no error. Notebooks and Python items get the structure checks only; the Python sandbox checks run when they are
 * published or used, as for any Python item.
 */
final class BiUploadChecks {

    private static final Pattern FILE = Pattern.compile("([A-Za-z0-9][A-Za-z0-9_-]{0,80})\\.(dataset|report|board|notebook|python)\\.yaml");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** @param problems what stops the file being placed; @param warnings what is worth reading but does not */
    record Outcome(String name, String subtype, String text, List<String> problems, List<String> warnings) {

        boolean valid() {
            return problems.isEmpty();
        }
    }

    private BiUploadChecks() {
    }

    static Outcome check(String filename, byte[] body, int maxBytes) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String base = filename == null ? "" : filename.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        Matcher m = FILE.matcher(base);
        if (!m.matches()) {
            problems.add("the file name is <name>.<kind>.yaml with kind dataset, report, board, notebook or python (letters, digits, hyphen, underscore in the name), not '" + base + "'");
            return new Outcome("", "", "", problems, warnings);
        }
        String name = m.group(1);
        String sub = m.group(2);
        if (body == null || body.length == 0) {
            problems.add("the file is empty");
            return new Outcome(name, sub, "", problems, warnings);
        }
        if (body.length > maxBytes) {
            problems.add("the file is " + body.length + " bytes; the limit is " + maxBytes + " (drishti.admin.bi-upload-max-bytes)");
            return new Outcome(name, sub, "", problems, warnings);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            problems.add("the file is not UTF-8 text");
            return new Outcome(name, sub, "", problems, warnings);
        }
        JsonNode root;
        try {
            root = YAML.readTree(text);
        } catch (Exception e) {
            problems.add("not valid YAML: " + firstLine(e.getMessage()));
            return new Outcome(name, sub, text, problems, warnings);
        }
        if (root == null || !root.isObject()) {
            problems.add("the file is YAML with a mapping at the top (key: value)");
            return new Outcome(name, sub, text, problems, warnings);
        }
        String own = root.path(sub).asText("");
        if (own.isEmpty()) {
            warnings.add("the file has no '" + sub + ": " + name + "' line; the file name is used");
        } else if (!own.equals(name)) {
            problems.add("the file says " + sub + ": " + own + " but its name is " + name + "; rename the file or the line so they agree");
        }
        try {
            switch (sub) {
                case "dataset" -> DatasetParser.parse(text).problems().forEach(p -> (p.error() ? problems : warnings).add(p.path() + ": " + p.message()));
                case "report" -> ReportParser.parse(text).problems().forEach(p -> (p.error() ? problems : warnings).add(p.path() + ": " + p.message()));
                case "board" -> BoardParser.parse(text).problems().forEach(p -> (p.error() ? problems : warnings).add(p.path() + ": " + p.message()));
                default -> {
                    // notebooks and Python items: the structure above is what is checked before they are used
                }
            }
        } catch (RuntimeException e) {
            problems.add("could not be checked: " + firstLine(e.getMessage()));
        }
        return new Outcome(name, sub, text, problems, warnings);
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }
}
