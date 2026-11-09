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
package com.ash.drishti.server.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ash.drishti.common.Branding;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.api.ProcessResources;
import com.ash.drishti.server.api.RequestActivity;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.kernel.api.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Admin → Capacity's three endpoints (CLUSTER_GUIDE.md 9.7): administrators only; the planner file served whole; the workbook holds
 * the inputs given in its Inputs cells while Results, Coefficients and the defined names are the template's, untouched (every figure a live
 * formula); each download audited; the live figures and the resident memory read from {@code /proc/self/status}.
 */
class CapacityControllerTest {

    private static final Principal ROOT = new Principal("root", List.of("admin"));
    private static final Principal VIC = new Principal("vic", List.of("viewer"));

    private final Entitlements entitlements = mock(Entitlements.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final CapacityPlanner planner = CapacityPlanner.builtIn("9.9.9");
    private final CapacityWorkbook workbook = CapacityWorkbook.builtIn();
    private final RequestActivity activity = new RequestActivity();
    private final CapacityController controller = new CapacityController(entitlements, audit, planner, workbook,
            new CapacityLive(() -> java.util.List.of(new com.ash.drishti.banking.var.VarCapacityFigures(null)), () -> null, () -> activity, () -> null));

    {
        doThrow(new DrishtiException(ErrorCode.FORBIDDEN, "vic is not an administrator")).when(entitlements).requireAdmin(VIC);
    }

    private static Map<String, String> unzip(byte[] xlsx) throws Exception {
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                parts.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }

    private byte[] download(Map<String, String> q) throws Exception {
        Map<String, String> all = new LinkedHashMap<>(q);
        var answer = controller.download(all.getOrDefault("inputs", "plan"), all, ROOT);
        assertThat(answer.getHeaders().getContentType().toString()).isEqualTo(CapacityController.XLSX);
        assertThat(answer.getHeaders().getFirst("Content-Disposition")).contains("attachment").contains(".xlsx");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        answer.getBody().writeTo(out);
        return out.toByteArray();
    }

    /** The value of a cell of a sheet: a number's text or an inline string. */
    private static String cell(String sheet, String ref) {
        Matcher m = Pattern.compile("<c r=\"" + ref + "\"[^>]*>(?:<v>([^<]*)</v>|<is><t[^>]*>([^<]*)</t></is>)</c>").matcher(sheet);
        return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
    }

    @Test
    void onlyAdministratorsMayAsk() {
        assertThatThrownBy(() -> controller.plan(VIC)).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> controller.live(VIC)).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> controller.download("plan", Map.of(), VIC)).isInstanceOf(DrishtiException.class);
        verify(audit, never()).record(eq("vic"), eq(CapacityController.AUDIT_ACTION), eq("capacity"), contains("inputs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePlannerFileIsServedWholeWithTheServersVersion() {
        Map<String, Object> plan = controller.plan(ROOT);
        assertThat(plan).containsKeys("inputs", "coefficients", "results", "settings", "jvm", "warnings", "examples");
        List<Map<String, Object>> inputs = (List<Map<String, Object>>) plan.get("inputs");
        Map<String, Object> jar = inputs.stream().filter(i -> "ServerJar".equals(i.get("name"))).findFirst().orElseThrow();
        assertThat(jar.get("default")).isEqualTo("drishti-server-9.9.9-exec.jar");
        assertThat(inputs).anySatisfy(i -> assertThat(i).containsEntry("name", "Trades").containsEntry("default", 300000));
    }

    @Test
    void theWorkbookHoldsTheGivenInputsAndEveryFormulaIntact() throws Exception {
        Map<String, String> parts = unzip(download(Map.of("inputs", "plan", "Trades", "450,000", "HotDates", "12", "Collector", "ZGC")));
        assertThat(parts.keySet()).containsExactlyElementsOf(workbook.partNames());
        String inputs = parts.get("xl/worksheets/sheet1.xml");
        assertThat(cell(inputs, "B" + workbook.row("Trades"))).isEqualTo("450000");
        assertThat(cell(inputs, "B" + workbook.row("HotDates"))).isEqualTo("12");
        assertThat(cell(inputs, "B" + workbook.row("Collector"))).isEqualTo("ZGC");
        assertThat(cell(inputs, "B" + workbook.row("Positions"))).isEqualTo("300000");
        assertThat(cell(inputs, "B" + workbook.row("ServerJar"))).isEqualTo("drishti-server-9.9.9-exec.jar");
        Branding b = Branding.current();
        for (String part : List.of("xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml", "xl/workbook.xml", "xl/styles.xml")) {
            assertThat(parts.get(part)).as(part).isEqualTo(CapacityWorkbook.brand(workbook.part(part), b));
        }
        assertThat(parts.get("xl/worksheets/sheet2.xml")).contains("<f>CEILING(XmxRawMB/1024,1)</f>");
        assertThat(parts.get("xl/workbook.xml")).contains("<definedName name=\"Trades\">Inputs!$B$" + workbook.row("Trades") + "</definedName>")
                .contains("fullCalcOnLoad=\"1\"");
        assertThat(String.join("", parts.values())).doesNotContain("{{PRODUCT}}").doesNotContain("{{COPYRIGHT}}");
        verify(audit).record(eq("root"), eq(CapacityController.AUDIT_ACTION), eq("capacity"), contains("Trades=450000"));
    }

    @Test
    void bothPutsTheLiveFiguresBesideThePlanWithTheirTime() throws Exception {
        activity.seen("ann", System.currentTimeMillis());
        Map<String, String> parts = unzip(download(Map.of("inputs", "both", "Users", "50")));
        String inputs = parts.get("xl/worksheets/sheet1.xml");
        assertThat(cell(inputs, "F3")).startsWith("Live, measured ");
        assertThat(cell(inputs, "B" + workbook.row("Users"))).isEqualTo("50");
        assertThat(cell(inputs, "F" + workbook.row("Users"))).isEqualTo("1");
        assertThat(cell(inputs, "F" + workbook.row("JavaVersion"))).isEqualTo(String.valueOf(Runtime.version().feature()));
        assertThat(inputs).contains("Measured on this server (not inputs)").contains("Heap max (-Xmx)");
        verify(audit).record(eq("root"), eq(CapacityController.AUDIT_ACTION), eq("capacity"), contains("inputs=both"));

        String live = unzip(download(Map.of("inputs", "live"))).get("xl/worksheets/sheet1.xml");
        assertThat(cell(live, "B" + workbook.row("Users"))).isEqualTo("1");
        assertThat(cell(live, "F3")).isEqualTo("Plan (as on the page)");
        assertThat(cell(live, "F" + workbook.row("Users"))).isEqualTo("50");
    }

    @Test
    void anUnknownInputOrAWrongNumberIsRefusedInWords() {
        assertThatThrownBy(() -> controller.download("plan", Map.of("Tardes", "1"), ROOT)).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("Tardes").hasMessageContaining("Trades");
        assertThatThrownBy(() -> controller.download("plan", Map.of("Trades", "many"), ROOT)).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("Trades is a number");
        assertThatThrownBy(() -> controller.download("all", Map.of(), ROOT)).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("plan, live or both");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLiveFiguresSayWhenAndWhatTheyStandFor() {
        Map<String, Object> m = controller.live(ROOT);
        assertThat(m).containsKeys("measuredAt", "server", "var", "disk", "load", "inputs");
        Map<String, Object> server = (Map<String, Object>) m.get("server");
        assertThat(server).containsKeys("heapMaxMb", "heapAfterGcMb", "rssMb", "cpus", "javaVersion");
        assertThat((Map<String, Object>) m.get("inputs")).containsEntry("JavaVersion", Runtime.version().feature()).containsKey("Users");
    }

    @Test
    void residentMemoryIsReadFromProcSelfStatus(@TempDir Path dir) throws Exception {
        Path status = dir.resolve("status");
        Files.writeString(status, "Name:\tjava\nVmPeak:\t 9000000 kB\nVmRSS:\t  2048000 kB\nThreads:\t80\n");
        assertThat(ProcessResources.rssMb(status)).isEqualTo(2000L);
        Files.writeString(status, "Name:\tjava\n");
        assertThat(ProcessResources.rssMb(status)).isNull();
        assertThat(ProcessResources.rssMb(dir.resolve("absent"))).isNull();
    }

    @Test
    void theTemplateNamesARowForEveryInputOfThePlanner() throws Exception {
        JsonNode meta = new ObjectMapper().readTree(getClass().getClassLoader().getResourceAsStream("capacity/template/template.json"));
        assertThat(meta.get("inputs").size()).isEqualTo(planner.inputNames().size());
        for (String n : planner.inputNames()) {
            assertThat(workbook.row(n)).as(n).isPositive();
        }
    }
}
