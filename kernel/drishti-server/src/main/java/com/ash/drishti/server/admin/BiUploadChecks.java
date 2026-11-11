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
import com.ash.drishti.server.bi.BiFolder;
import com.ash.drishti.server.bi.layer.DatasetDefParser;
import com.ash.drishti.server.bi.layer.ModelDefParser;
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
 * whose own key agrees with the file name, and the reader of its kind finding no error. A table ({@code <name>.table.yaml}) names itself
 * {@code table: <domain>.<name>} and a model ({@code <name>.model.yaml}) {@code model: <name>}, both in the table layer's form
 * ({@code rachana: 3}, read by the layer's readers); a dataset file in BI's old form is refused with the migration's command
 * ({@code DRS-9196}), as the BI folder refuses it. Reports and boards are read by their parsers; notebooks and Python items get the structure
 * checks only (the Python sandbox checks run when they are published or used, as for any Python item).
 */
final class BiUploadChecks {

    private static final Pattern FILE = Pattern.compile("([A-Za-z0-9][A-Za-z0-9_-]{0,80})\\.(table|model|report|board|notebook|python)\\.yaml");
    private static final Pattern OLD = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,80}\\.dataset\\.yaml");
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
        if (OLD.matcher(base).matches()) {
            problems.add(BiFolder.MIGRATE_FIRST + ": a dataset file in BI's old form is not read; run drishti.py tables migrate --dry-run, then without it, "
                    + "and upload the tables and model it writes (<name>.table.yaml, <name>.model.yaml)");
            return new Outcome("", "", "", problems, warnings);
        }
        Matcher m = FILE.matcher(base);
        if (!m.matches()) {
            problems.add("the file name is <name>.<kind>.yaml with kind table, model, report, board, notebook or python (letters, digits, hyphen, underscore in the name), not '"
                    + base + "'");
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
        if ("table".equals(sub)) {
            Matcher t = ArtifactPaths.TABLE_NAME.matcher(own);
            if (!t.matches()) {
                problems.add("a table names itself table: <domain>.<name> (lower case, letters, digits and underscores), not '" + own + "'");
                return new Outcome(name, sub, text, problems, warnings);
            }
            if (!t.group(2).equals(name)) {
                problems.add("the file says table: " + own + " but its name is " + name + "; the file of " + own + " is " + t.group(2) + ".table.yaml");
            }
            name = own;                                                     // a table is known by <domain>.<name>
        } else if ("model".equals(sub) && own.endsWith("." + name)) {
            name = own;                                                     // a model with its domain: <domain>.<name>, in the domain's folder
        } else if (own.isEmpty()) {
            warnings.add("the file has no '" + sub + ": " + name + "' line; the file name is used");
        } else if (!own.equals(name)) {
            problems.add("the file says " + sub + ": " + own + " but its name is " + name + "; rename the file or the line so they agree");
        }
        try {
            switch (sub) {
                case "table" -> DatasetDefParser.parse(text).problems().forEach(p -> (p.error() ? problems : warnings).add(p.path() + ": " + p.message()));
                case "model" -> ModelDefParser.parse(text).problems().forEach(p -> (p.error() ? problems : warnings).add(p.path() + ": " + p.message()));
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
