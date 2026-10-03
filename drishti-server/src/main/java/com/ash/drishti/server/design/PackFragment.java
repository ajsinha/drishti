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
package com.ash.drishti.server.design;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.design.DesignService;
import com.ash.drishti.identity.design.StoredDesign;
import com.ash.drishti.identity.design.StoredDesign.SampleInfo;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.model.Sutra;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;

/**
 * A Design as a pack fragment and back. The export is a zip with one folder: {@code pack.yaml} (a stub: the kind, a mnemonic
 * suggestion, an id pattern and the id field), the Sutra under {@code sutras/}, the samples as tests in
 * {@code tests/<sutra>/*.json} with an {@code expect.yaml} (what {@code sutra test} checks), three samples in
 * {@code samples/<kind>/} and a README. The import reads a zip of the same layout (or any pack folder zipped) into Designs,
 * one per Sutra, with the samples found beside it. Nothing is written to disk; entries are read in memory with limits.
 */
@Component
public class PackFragment {

    /** Documents exported as tests, and as samples. */
    static final int MAX_TESTS = 20;
    static final int SAMPLES = 3;
    static final int MAX_ENTRIES = 1000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DesignService designs;
    private final SutraRegistry sutras;

    public PackFragment(DesignService designs, SutraRegistry sutras) {
        this.designs = designs;
        this.sutras = sutras;
    }

    /** What was made of a zip: the Designs created, and what was left out and why. */
    public record Imported(List<StoredDesign> designs, List<String> skipped) {}

    // ---- export ----------------------------------------------------------------------------------------------------

    /** The fragment's folder name: the Design's name or kind as a pack name. */
    public static String packName(StoredDesign d) {
        String base = d.name == null || d.name.isBlank() ? d.kind : d.name;
        String s = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "my-pack" : (s.length() > 40 ? s.substring(0, 40) : s);
    }

    /** A mnemonic suggestion for a kind: its letters, upper case, at most four (the user can change it in pack.yaml). */
    public static String mnemonic(String kind) {
        String s = kind.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return s.isEmpty() ? "MINE" : s.substring(0, Math.min(4, s.length()));
    }

    /** The field most likely to hold an entity's id: {@code id}, then a name ending in id, else the first scalar. */
    public static String idField(JsonNode doc) {
        if (doc == null || !doc.isObject()) {
            return "id";
        }
        List<String> names = new ArrayList<>();
        doc.fieldNames().forEachRemaining(names::add);
        for (String n : names) {
            if (n.equalsIgnoreCase("id")) {
                return n;
            }
        }
        for (String n : names) {
            String l = n.toLowerCase(Locale.ROOT);
            if (l.endsWith("id") || l.endsWith("_id") || l.endsWith("key") || l.endsWith("code")) {
                return n;
            }
        }
        for (String n : names) {
            if (doc.get(n).isValueNode()) {
                return n;
            }
        }
        return "id";
    }

    /**
     * Builds the zip.
     *
     * @param d the Design
     * @param documents its kept sample documents by name (references are not exported: they are data, not design)
     * @param matrix the last check matrix, or null; panels that are {@code ok} on every sample become {@code nonEmpty}
     */
    public byte[] export(StoredDesign d, Map<String, JsonNode> documents, JsonNode matrix) throws IOException {
        String pack = packName(d);
        String kind = d.kind == null || d.kind.isBlank() ? "sample" : d.kind;
        String sutraName = pack;
        String domain = "custom";
        int version = 1;
        try {
            Sutra s = sutras.check(d.sutra);
            sutraName = s.name();
            domain = s.domain() == null || s.domain().isBlank() ? domain : s.domain();
            version = s.version();
            if (s.match() != null && s.match().kind() != null && !s.match().kind().isBlank()) {
                kind = s.match().kind();
            }
        } catch (RuntimeException e) {
            // an unchecked Sutra is exported as it is; the README says to check it
        }
        List<Map.Entry<String, JsonNode>> docs = new ArrayList<>(documents.entrySet());
        String mnemonic = mnemonic(kind);
        String idField = docs.isEmpty() ? "id" : idField(docs.get(0).getValue());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, pack + "/pack.yaml", packYaml(pack, kind, mnemonic, idField, d));
            put(zip, pack + "/sutras/" + domain + "/" + sutraName + ".v" + version + ".sutra.yaml", d.sutra == null ? "" : d.sutra);
            Set<String> used = new LinkedHashSet<>();
            int n = 0;
            for (Map.Entry<String, JsonNode> e : docs) {
                if (n++ >= MAX_TESTS) {
                    break;
                }
                put(zip, pack + "/tests/" + sutraName + "/" + unique(used, e.getKey()), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(e.getValue()));
            }
            if (!docs.isEmpty()) {
                put(zip, pack + "/tests/" + sutraName + "/expect.yaml", expectYaml(matrix));
            }
            Set<String> usedSamples = new LinkedHashSet<>();
            for (int i = 0; i < Math.min(SAMPLES, docs.size()); i++) {
                put(zip, pack + "/samples/" + kind + "/" + unique(usedSamples, docs.get(i).getKey()),
                        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(docs.get(i).getValue()));
            }
            if (d.notes != null && !d.notes.isBlank()) {
                put(zip, pack + "/" + NOTES_FILE, d.notes);
            }
            put(zip, pack + "/README.md", readme(pack, sutraName, kind, mnemonic, idField, docs.size(), d));
        }
        return bytes.toByteArray();
    }

    private static String unique(Set<String> used, String name) {
        // only characters that are unsafe in a path become "_"; letters of any script are kept
        String base = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").replaceAll("^\\.+", "_");
        if (base.endsWith(".json")) {
            base = base.substring(0, base.length() - 5);
        }
        String file = base + ".json";
        for (int i = 2; !used.add(file); i++) {
            file = base + "-" + i + ".json";
        }
        return file;
    }

    private static String q(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String packYaml(String pack, String kind, String mnemonic, String idField, StoredDesign d) {
        StringBuilder b = new StringBuilder();
        b.append("# A pack fragment made by the Build workbench. Rename it, set the version and code, then load it from Admin -> Packs.\n");
        b.append("pack: ").append(pack).append('\n');
        b.append("version: 0.1.0\n");
        b.append("code: ").append(mnemonic).append('\n');
        b.append("title: ").append(q(d.name == null || d.name.isBlank() ? pack : d.name)).append('\n');
        b.append("description: ").append(q("Made in the Build workbench from design " + d.id + ".")).append('\n');
        b.append("extends: []\n");
        b.append("kinds:\n- ").append(kind).append('\n');
        b.append("sutras: sutras\n");
        b.append("# Suggestions: the mnemonic is what users type in the command bar; the id pattern lets the graph recognise the ids.\n");
        b.append("# The documents' id field in the samples is '").append(idField).append("': ids look like <MNEMONIC>-<value>, change the pattern if yours do not.\n");
        b.append("mnemonics:\n  ").append(mnemonic).append(":\n    kind: ").append(kind).append("\n    label: ").append(q(kind)).append('\n');
        b.append("graph:\n  id-patterns:\n  - pattern: ").append(q("^" + mnemonic + "-")).append("\n    kind: ").append(kind).append('\n');
        return b.toString();
    }

    private static String expectYaml(JsonNode matrix) {
        StringBuilder b = new StringBuilder("# What `sutra test` checks on every sample in this folder.\nnoErrors: true\n");
        List<String> ok = new ArrayList<>();
        if (matrix != null && matrix.path("panels").isArray()) {
            for (JsonNode p : matrix.get("panels")) {
                boolean all = p.path("cells").isArray() && p.get("cells").size() > 0;
                for (JsonNode c : p.path("cells")) {
                    all &= "ok".equals(c.path("status").asText());
                }
                if (all && p.path("id").isTextual()) {
                    ok.add(p.get("id").asText());
                }
            }
        }
        if (ok.isEmpty()) {
            b.append("nonEmpty: []\n");
        } else {
            b.append("nonEmpty:\n");
            ok.forEach(id -> b.append("- ").append(q(id)).append('\n'));
        }
        return b.toString();
    }

    /** The design's notes, kept apart from the generated README so that export then import does not grow them. */
    static final String NOTES_FILE = "NOTES.md";
    /** A line only the generated README holds: a README with it is ours and is not imported as notes. */
    private static final String README_MARK = "A pack fragment made in the Build workbench";

    private static String readme(String pack, String sutra, String kind, String mnemonic, String idField, int tests, StoredDesign d) {
        return "# " + (d.name == null || d.name.isBlank() ? pack : d.name) + "\n\n"
                + "A pack fragment made in the Build workbench (design `" + d.id + "`).\n\n"
                + "- `pack.yaml`: a stub for the `" + kind + "` kind, with the mnemonic `" + mnemonic + "` and the id field `" + idField + "` suggested. Edit them.\n"
                + "- `sutras/`: the Sutra `" + sutra + "`.\n"
                + "- `tests/" + sutra + "/`: " + tests + " sample document(s) and `expect.yaml`; run them with\n"
                + "  `java -jar drishti-server-*-exec.jar sutra test " + pack + "`.\n"
                + "- `samples/" + kind + "/`: up to three documents to try the view on.\n\n"
                + "To use it: put this folder under the server's `packs/` directory and load it from Admin -> Packs (or list it in `DRISHTI_PACKS`).\n"
                + (d.notes == null || d.notes.isBlank() ? "" : "\nThe design's notes are in `" + NOTES_FILE + "`.\n");
    }

    private static void put(ZipOutputStream zip, String path, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // ---- import ----------------------------------------------------------------------------------------------------

    /** One file of the zip, in memory. */
    private record Item(String path, String text) {}

    /**
     * Reads a zip of a pack folder into Designs for {@code user}: one Design per Sutra file, its samples being the documents of
     * {@code tests/<sutra>/} and {@code samples/<kind>/}, its notes the README.
     *
     * @param maxBytes the most the unpacked files may add up to
     */
    public Imported importZip(String user, byte[] zip, long maxBytes) throws IOException {
        List<Item> items = new ArrayList<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                if (items.size() >= MAX_ENTRIES) {
                    throw new DrishtiException(ErrorCode.PAYLOAD_TOO_LARGE, "the zip holds more than " + MAX_ENTRIES + " files");
                }
                String path = e.getName().replace('\\', '/');
                if (path.startsWith("/") || path.contains("../") || path.startsWith("__MACOSX/") || path.endsWith(".DS_Store")) {
                    continue;
                }
                byte[] data = in.readNBytes((int) Math.min(maxBytes - total + 1, Integer.MAX_VALUE - 8));
                total += data.length;
                if (total > maxBytes) {
                    throw new DrishtiException(ErrorCode.PAYLOAD_TOO_LARGE, "the zip unpacks to more than " + (maxBytes / 1048576) + " MiB");
                }
                if (path.endsWith(".yaml") || path.endsWith(".yml") || path.endsWith(".json") || path.endsWith(".md")) {
                    items.add(new Item(path, new String(data, StandardCharsets.UTF_8)));
                }
            }
        } catch (java.util.zip.ZipException ex) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "that is not a readable zip: " + ex.getMessage());
        }
        List<String> skipped = new ArrayList<>();
        String readme = items.stream().filter(i -> (i.path().endsWith("/" + NOTES_FILE) || i.path().equals(NOTES_FILE)) && depth(i.path()) <= 2)
                .map(Item::text).findFirst().orElseGet(() -> items.stream()
                        .filter(i -> i.path().toLowerCase(Locale.ROOT).endsWith("readme.md") && depth(i.path()) <= 2)
                        .map(Item::text).filter(t -> !t.contains(README_MARK)).findFirst().orElse(""));
        List<StoredDesign> made = new ArrayList<>();
        for (Item sutraFile : items) {
            if (!isSutra(sutraFile.path())) {
                continue;
            }
            String fileName = sutraFile.path().substring(sutraFile.path().lastIndexOf('/') + 1);
            String name = fileName.replaceAll("\\.sutra\\.ya?ml$", "").replaceAll("\\.ya?ml$", "").replaceAll("\\.v\\d+$", "");
            String kind = null;
            try {
                Sutra s = sutras.check(sutraFile.text());
                name = s.name();
                kind = s.match() == null ? null : s.match().kind();
            } catch (RuntimeException ex) {
                skipped.add(fileName + ": not a valid Sutra yet (" + firstLine(ex.getMessage()) + "), imported anyway so you can fix it");
            }
            Map<String, String> samples = new LinkedHashMap<>();
            for (Item j : items) {
                if (j.path().endsWith(".json") && (inFolder(j.path(), "tests", name) || (kind != null && inFolder(j.path(), "samples", kind)))) {
                    String sampleName = j.path().substring(j.path().lastIndexOf('/') + 1);
                    samples.putIfAbsent(sampleName, j.text());
                }
            }
            StoredDesign d = designs.create(user, name, kind, null, sutraFile.text(), readme);
            List<DesignService.NewSample> add = new ArrayList<>();
            for (Map.Entry<String, String> s : samples.entrySet()) {
                try {
                    JsonNode n = JSON.readTree(s.getValue());
                    if (n != null && n.isObject()) {
                        add.add(new DesignService.NewSample(s.getKey(), StoredDesign.DOCUMENT, null, null, s.getValue()));
                    } else {
                        skipped.add(s.getKey() + ": not a JSON object");
                    }
                } catch (IOException ex) {
                    skipped.add(s.getKey() + ": not valid JSON");
                }
            }
            if (!add.isEmpty()) {
                d = designs.addSamples(user, d.id, add);
            }
            made.add(d);
        }
        if (made.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no Sutra (*.sutra.yaml) was found in the zip");
        }
        return new Imported(made, skipped);
    }

    private static boolean isSutra(String path) {
        String l = path.toLowerCase(Locale.ROOT);
        if (l.endsWith(".sutra.yaml") || l.endsWith(".sutra.yml")) {
            return true;
        }
        return false;
    }

    private static int depth(String path) {
        return path.split("/").length;
    }

    /** Whether {@code path} is a file in a folder {@code leaf} that is itself under a folder called {@code parent}. */
    private static boolean inFolder(String path, String parent, String leaf) {
        String[] seg = path.split("/");
        for (int i = 0; i + 2 < seg.length; i++) {
            if (seg[i].equals(parent) && seg[i + 1].equals(leaf) && i + 3 == seg.length) {
                return true;
            }
        }
        return false;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** The import result as JSON for the API. */
    public static ObjectNode toJson(Imported r, java.util.function.Function<StoredDesign, ObjectNode> view) {
        ObjectNode out = JSON.createObjectNode();
        ArrayNode ds = out.putArray("designs");
        r.designs().forEach(d -> ds.add(view.apply(d)));
        ArrayNode sk = out.putArray("skipped");
        r.skipped().forEach(sk::add);
        return out;
    }

    /** Samples listed for export: kept documents only (references and nothing else are left out). */
    public static List<SampleInfo> exportable(StoredDesign d) {
        return d.samples.stream().filter(s -> !StoredDesign.REF.equals(s.type())).toList();
    }
}
