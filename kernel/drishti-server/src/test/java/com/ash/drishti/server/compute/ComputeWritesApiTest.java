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
package com.ash.drishti.server.compute;

import com.ash.tools.codec.Hashes;
import com.ash.lake.tck.TestRoots;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ash.drishti.compute.ComputeHandle;
import com.ash.drishti.compute.ComputeRequest;
import com.ash.drishti.compute.ComputeService;
import com.ash.drishti.server.TestRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Admin → Compute's writes and a peer's compute calls against real workers (a minimal pool of two, as the compute module's own tests run
 * them): a locked pool's environment is refused before its requirements are approved, built offline from the shipped wheel once a second person
 * approves, and repeated without a rebuild; the sample engine is run on its pool and refused on one that does not load it; a running
 * request is cancelled from Admin → Compute (once: the second finds nothing); another node's chunk is run for the viewer it names and its cancel
 * reaches the worker; nothing but a node may call the peer endpoints. Needs a Python 3.10+ with pyarrow and pip
 * ({@code -Ddrishti.compute.test.python}, else DRISHTI_PYTHON, else python3 on the PATH); skipped, saying so, without one.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false"})
@AutoConfigureMockMvc
class ComputeWritesApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FAULTS = "compute_test_engines.faults:FaultEngine";
    private static final String VECTORS = "drishti_compute.samples.vectors:VectorEngine";
    private static Path work;
    private static String python;
    private static String version;

    @Autowired MockMvc mvc;
    @Autowired ComputeService compute;

    static String interpreter() {
        for (String c : new String[] {System.getProperty("drishti.compute.test.python"), System.getenv("DRISHTI_PYTHON"), "python3"}) {
            if (c == null || c.isBlank() || (c.contains("/") && !Files.exists(Path.of(c)))) {
                continue;
            }
            try {
                Process p = new ProcessBuilder(c, "-c", "import pyarrow, pip, sys; print('%d.%d' % sys.version_info[:2])").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                if (p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0 && out.matches("\\d+\\.\\d+")) {
                    version = out;
                    return c;
                }
            } catch (Exception ignored) {
                // the next candidate
            }
        }
        return null;
    }

    static byte[] wheel() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("demo_pkg/__init__.py", "VALUE = 7\n".getBytes(StandardCharsets.UTF_8));
        files.put("demo_pkg-1.0.0.dist-info/METADATA", "Metadata-Version: 2.1\nName: demo-pkg\nVersion: 1.0.0\n".getBytes(StandardCharsets.UTF_8));
        files.put("demo_pkg-1.0.0.dist-info/WHEEL", "Wheel-Version: 1.0\nGenerator: drishti-test\nRoot-Is-Purelib: true\nTag: py3-none-any\n"
                .getBytes(StandardCharsets.UTF_8));
        StringBuilder record = new StringBuilder();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            byte[] h = Hashes.digest("SHA-256").digest(e.getValue());
            record.append(e.getKey()).append(",sha256=").append(Base64.getUrlEncoder().withoutPadding().encodeToString(h)).append(',').append(e.getValue().length).append('\n');
        }
        record.append("demo_pkg-1.0.0.dist-info/RECORD,,\n");
        files.put("demo_pkg-1.0.0.dist-info/RECORD", record.toString().getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @DynamicPropertySource
    static synchronized void pools(DynamicPropertyRegistry r) throws Exception {
        python = interpreter();
        work = TestRun.temp("compute-writes");
        if (python == null) {
            return;                                                     // the test is skipped; the server starts with compute off
        }
        Path shipped = work.resolve("data").resolve("compute").resolve("shipped");
        Files.createDirectories(shipped);
        byte[] w = wheel();
        Files.write(shipped.resolve("demo_pkg-1.0.0-py3-none-any.whl"), w);
        Files.writeString(work.resolve("risk.lock"), "demo-pkg==1.0.0 --hash=sha256:" + Hashes.sha256Hex(w) + "\n");
        String testEngines = Path.of(TestRoots.repo(), "compute", "drishti-compute", "src", "test", "python").toAbsolutePath().normalize().toString().replace("\\", "/");
        Files.writeString(work.resolve("compute.yaml"), """
                compute:
                  enabled: true
                  python: %s
                  pools:
                    sample:
                      engines: [%s, %s]
                      workers: { min: 0, max: 2 }
                      mode: auto
                      python-path: ["%s"]
                      timeout: 30s
                    risk:
                      engines: [demo_pkg.engine:DemoEngine]
                      workers: { min: 0, max: 1 }
                      environment: { python: "%s", lock: risk.lock, index: shipped }
                """.formatted(python.replace("\\", "/"), VECTORS, FAULTS, testEngines, version));
        r.add("drishti.compute.file", () -> work.resolve("compute.yaml").toString());
        r.add("drishti.data.dir", () -> work.resolve("data").toString());
    }

    private static void needPython() {
        assumeTrue(python != null, "no Python 3.10+ with pyarrow and pip: pass -Ddrishti.compute.test.python=<python> or set DRISHTI_PYTHON");
    }

    private JsonNode ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return JSON.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void refused(MvcResult r, int status, String why) throws Exception {
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(status);
        assertThat(body).contains("DRS-");
        if (why != null) {
            assertThat(body).contains(why);
        }
    }

    private MvcResult as(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String user) throws Exception {
        return mvc.perform(request.header("X-Drishti-User", user)).andReturn();
    }

    @Test
    void aLockedPoolBuildsOnlyAfterASecondPersonApprovesAndTheSamplePoolTestsItsWorkers() throws Exception {
        needPython();
        // refused: not approved yet; a pool that runs in the site's interpreter has nothing to build; a pool nobody has
        MvcResult early = as(post("/api/v1/admin/compute/pools/risk/build"), "admin-one");
        refused(early, 409, "nothing is built before it is approved");
        refused(as(post("/api/v1/admin/compute/pools/sample/build"), "admin-one"), 409, "nothing to build");
        MvcResult none = as(post("/api/v1/admin/compute/pools/no-such-pool/build"), "admin-one");
        assertThat(none.getResponse().getStatus()).as(none.getResponse().getContentAsString()).isBetween(400, 499);
        assertThat(none.getResponse().getContentAsString()).contains("DRS-");
        // approved by a second person: built offline from the shipped wheel
        String id = null;
        for (JsonNode a : ok(mvc.perform(get("/api/v1/admin/compute").header("X-Drishti-User", "admin-one")).andReturn()).get("approvals")) {
            if ("pool:risk".equals(a.get("subject").asText()) && "pending".equals(a.get("state").asText())) {
                id = a.get("id").asText();
            }
        }
        assertThat(id).as("the pool's requirements wait for approval").isNotNull();
        assertThat(mvc.perform(post("/api/v1/approvals/compute/" + id + "/approve").header("X-Drishti-User", "admin-two").contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"pure Python, ours\"}")).andReturn().getResponse().getStatus()).isEqualTo(200);
        JsonNode built = ok(as(post("/api/v1/admin/compute/pools/risk/build"), "admin-one"));
        assertThat(built.get("reused").asBoolean()).isFalse();
        assertThat(Files.isDirectory(Path.of(built.get("dir").asText()))).isTrue();
        assertThat(built.get("log").toString()).contains("built");
        // again: the same environment, never rebuilt in place
        JsonNode again = ok(as(post("/api/v1/admin/compute/pools/risk/build"), "admin-one"));
        assertThat(again.get("reused").asBoolean()).isTrue();
        assertThat(again.get("id").asText()).isEqualTo(built.get("id").asText());

        // the sample engine on its pool: a real worker answers; a pool that does not load it refuses
        JsonNode t = ok(as(post("/api/v1/admin/compute/pools/sample/test"), "admin-one"));
        assertThat(t.get("ok").asBoolean()).isTrue();
        assertThat(t.get("worker").asText()).contains("sample");
        assertThat(t.get("rows").size()).isGreaterThan(0);
        refused(as(post("/api/v1/admin/compute/pools/risk/test"), "admin-one"), 409, "does not load the sample engine");
        MvcResult noPool = as(post("/api/v1/admin/compute/pools/no-such-pool/test"), "admin-one");
        assertThat(noPool.getResponse().getStatus()).as(noPool.getResponse().getContentAsString()).isBetween(400, 499);
        assertThat(noPool.getResponse().getContentAsString()).contains("DRS-");
        assertThat(ok(mvc.perform(get("/api/v1/admin/audit").header("X-Drishti-User", "admin-one")).andReturn()).toString()).contains("compute-build");
    }

    private ComputeRequest sleeper(String user, int seconds) {
        return ComputeRequest.on("sample", "sleep", user).engine(FAULTS).param("seconds", seconds).timeout(java.time.Duration.ofSeconds(60)).build();
    }

    private ComputeHandle running(ComputeRequest r) throws Exception {
        ComputeHandle h = compute.submit(r, null);
        assertThat(h.id()).isEqualTo(r.id());
        long until = System.currentTimeMillis() + 15_000;
        while (compute.handle(r.id()).isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        assertThat(compute.handle(r.id())).as("the request is waiting or running here").isPresent();
        return h;
    }

    @Test
    void aRunningRequestIsCancelledFromAdminOnceAndTheSecondCancelFindsNothing() throws Exception {
        needPython();
        ComputeHandle h = running(sleeper("asha", 30));
        JsonNode done = ok(as(post("/api/v1/admin/compute/running/" + h.id() + "/cancel"), "admin-one"));
        assertThat(done.get("cancelled").asText()).isEqualTo(h.id());
        long until = System.currentTimeMillis() + 15_000;
        while (!h.result().isDone() && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        assertThat(h.result().isDone()).as("the worker stopped").isTrue();
        assertThat(h.result().isCompletedExceptionally()).isTrue();
        assertThat(compute.handle(h.id())).isEmpty();
        refused(as(post("/api/v1/admin/compute/running/" + h.id() + "/cancel"), "admin-one"), 404, "no computation " + h.id());
        refused(as(post("/api/v1/admin/compute/running/not-a-request/cancel"), "admin-one"), 404, null);
        assertThat(ok(mvc.perform(get("/api/v1/admin/audit").header("X-Drishti-User", "admin-one")).andReturn()).toString()).contains("compute-cancel");
    }

    /** A call as another node of the cluster makes it (the attribute the cluster-credential filter sets; the filter itself is proved on a real cluster). */
    private MvcResult asPeer(String url, Object body, String user) throws Exception {
        var b = post(url).header("X-Drishti-User", user).requestAttr(com.ash.drishti.server.net.NetCallFilter.PEER, "n-other")
                .requestAttr(com.ash.drishti.kernel.api.security.Principal.ATTRIBUTE, new com.ash.drishti.kernel.api.security.Principal(user, List.of()));
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(b).andReturn();
    }

    @Test
    void anotherNodesChunkIsRunForTheViewerItNamesAndItsCancelReachesTheWorker() throws Exception {
        needPython();
        // a chunk that finishes: the worker's answer comes back in the wire form
        ComputeRequest quick = ComputeRequest.on("sample", "sleep", "asha").engine(FAULTS).param("seconds", 0).build();
        JsonNode result = ok(asPeer("/api/v1/compute/peer/run", ComputeWire.request(quick), "asha"));
        assertThat(result.toString()).contains("slept");
        assertThat(result.has("error")).isFalse();
        // for someone else than the on-behalf-of person: refused in the answer, nothing runs; not from a node: 403
        JsonNode wrongViewer = ok(asPeer("/api/v1/compute/peer/run", ComputeWire.request(quick), "mallory"));
        assertThat(wrongViewer.path("error").path("message").asText()).contains("not the user the call is made for");
        MvcResult notNode = mvc.perform(post("/api/v1/compute/peer/run").header("X-Drishti-User", "asha").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(ComputeWire.request(quick)))).andReturn();
        refused(notNode, 403, "may call this");
        refused(mvc.perform(post("/api/v1/compute/peer/cancel/whatever").header("X-Drishti-User", "asha")).andReturn(), 403, "may call this");
        // a pool nobody has is refused in the answer
        ComputeRequest nowhere = ComputeRequest.on("no-such-pool", "sleep", "asha").engine(FAULTS).build();
        assertThat(ok(asPeer("/api/v1/compute/peer/run", ComputeWire.request(nowhere), "asha")).path("error").path("message").asText()).contains("no-such-pool");

        // a long chunk, cancelled by the node that sent it: the run ends with an error, the second cancel finds nothing
        ComputeRequest slow = sleeper("asha", 30);
        AtomicReference<JsonNode> ended = new AtomicReference<>();
        Thread run = Thread.ofVirtual().start(() -> {
            try {
                ended.set(JSON.readTree(asPeer("/api/v1/compute/peer/run", ComputeWire.request(slow), "asha").getResponse().getContentAsString(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                ended.set(JSON.createObjectNode().put("failed", e.toString()));
            }
        });
        long until = System.currentTimeMillis() + 15_000;
        while (compute.handle(slow.id()).isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        assertThat(compute.handle(slow.id())).as("the chunk runs here").isPresent();
        assertThat(ok(asPeer("/api/v1/compute/peer/cancel/" + slow.id(), null, "asha")).get("cancelled").asBoolean()).isTrue();
        run.join(20_000);
        assertThat(ended.get()).as("the run returned").isNotNull();
        assertThat(ended.get().has("error")).as(ended.get().toString()).isTrue();
        assertThat(compute.handle(slow.id())).isEmpty();
        assertThat(ok(asPeer("/api/v1/compute/peer/cancel/" + slow.id(), null, "asha")).get("cancelled").asBoolean()).isFalse();
    }
}
