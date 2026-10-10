<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
# MCP client: other servers' tools, behind Drishti's policy

Drishti can act as a client of **MCP (Model Context Protocol)** servers: list their tools, resources and prompts, call a tool, read a
resource. Like the LLM layer ([AI_PROVIDERS.md](AI_PROVIDERS.md)) it is interface-driven, configured by one file per server,
**off by default** (`drishti.mcp.enabled=false`), and exists on both layers: the server (`drishti-ai`, package `com.ash.drishti.ai`)
and the console (`drishti-console/core/ai/mcp.py`).

## 1. Interfaces

| Type | Role |
|---|---|
| `McpClient` | One server connection: `connect()`, `tools()`, `resources()`, `prompts()`, `callTool(name, args)`, `readResource(uri)`, `close()`. |
| `McpClientFactory` | The extension point per `transport:` value (ServiceLoader `META-INF/services/com.ash.drishti.ai.McpClientFactory`, or a Spring bean). |
| `McpRegistry` | Servers by logical name, health, `reload()` (watched like connectors). Connections open on first use. |
| `McpGateway` | The one door: policy, masking, audit, health around every call. |

**Implementation choice.** Spring AI has MCP client starters, but they bring their own transports and sessions and an
auto-configuration per server; the protocol part Drishti needs is small (JSON-RPC over HTTP), so `StreamableHttpMcpClient` is a
~250-line client over the JDK HTTP client (httpx in the console) that shares the `tls.*` module and the policy. A heavier SDK can
be added later as another `McpClientFactory` without touching callers.

Transports: `streamable-http` (the MCP Streamable HTTP transport: one POST endpoint, JSON or Server-Sent-Events answers, the
`Mcp-Session-Id` kept and sent back, `MCP-Protocol-Version` negotiated at `initialize`, session closed with DELETE), `mock` (in-process,
scripted), and in the console `server` (delegates to the Drishti server). The legacy HTTP+SSE transport and stdio are not
implemented (stdio would run a local process; not wanted on a server by default).

## 2. Server files

One YAML per server in `drishti.mcp.dir` (`DRISHTI_MCP_DIR`, default `./config/mcp`), reloaded live:

```yaml
transport: streamable-http
url: https://tools.example/mcp
timeout: 30s
headers:
  Authorization: Bearer ${TOOLS_TOKEN}     # ${ENV} placeholders only; a credential header without one is refused
allowed-tools: [calc_percentage_change]    # empty = all the server lists; others can be neither listed nor called
tls: {ca-file: /etc/drishti/ca.pem}        # the shared tls.* keys
```

## 3. Policy (`drishti.mcp.*`)

| Setting | Meaning |
|---|---|
| `server-roles.[server]` | Roles allowed to use a server (absent = every signed-in user). |
| `tool-roles.[server/tool]` | Roles allowed to see and call one tool. |
| `server-features.[server]` | Features allowed to use a server. |
| `audit` (true) | Access-log row per call, action `mcp`: user, feature, `server/tool`, sizes, outcome, ms. Never arguments or results. |
| masking | Arguments named in `drishti.security.redact` are replaced by `•••`; string arguments and every result pass through the same masker (`drishti.ai.policy.mask-patterns`). |
| timeouts | the file's `timeout`. |

REST: `GET /api/v1/admin/mcp/{server}/tools` (admin), `POST /api/v1/ai/mcp/{server}/tools/{tool}` `{arguments, feature}` (signed in; token
scope `ai:use`). Errors as the LLM layer (DRS-4010..4014). Admin -> AI lists the servers and, by link, their tools.

## 4. The mock server

`transport: mock`: scripted tools (`mock.tools.N.{name, description, reply, error}`; `${arg}` in `reply` is replaced), resources
(`uri`, `text`) and prompts, no network. `config/mcp/mock.yaml.example`.

## 5. Connecting to SAJHA (the user's MCP server)

SAJHA (`sajhamcpserver`) serves one `/mcp` endpoint (Streamable HTTP, also SSE, WebSocket and stdio) for both spec eras, chosen per
request; Drishti speaks the session-based 2025-11-25 one. Default `http://localhost:3002/mcp`. Anonymous callers see **no tools**
by default, so Drishti needs a credential: an `X-API-Key` (Admin -> API keys in SAJHA) or `Authorization: Bearer <JWT>`. Copy
`config/mcp/sajha.yaml.example` to `config/mcp/sajha.yaml`, export `SAJHA_API_KEY`, set `drishti.mcp.enabled=true`.

Verified live (a scratch copy of SAJHA on a spare port, its own virtualenv; the project itself untouched), with
`drishti-ai/src/test/java/com/ash/drishti/ai/SajhaMcpLiveTest.java` (skipped unless `DRISHTI_SAJHA_MCP_URL` and `DRISHTI_SAJHA_MCP_KEY`
are set) and the console client:

```
$ DRISHTI_SAJHA_MCP_URL=http://127.0.0.1:18991/mcp DRISHTI_SAJHA_MCP_KEY=<test key> ./mvnw -o -pl drishti-ai test -Dtest=SajhaMcpLiveTest
SAJHA tools: 501, e.g. [av_ad_line, av_adx, av_aroon, av_atr, av_balance_sheet]
calc_percentage_change -> { "old_value": 80, "new_value": 100, "percentage_change": 25.0 }
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0

>>> console client (core/ai/mcp.py StreamableHttpClient, same server)
501 tools; first: ['av_ad_line', 'av_adx', 'av_aroon', 'av_atr', 'av_balance_sheet', 'av_bbands', 'av_cash_flow', 'av_cci']
call: Result(text='{\n  "old_value": 80,\n  "new_value": 100,\n  "percentage_change": 25.0\n}', error=False)
```

To repeat it by hand: copy the SAJHA project to a scratch folder (it writes `data/` and `logs/` next to itself), create a virtualenv,
`pip install -r requirements.txt` (at the time of writing `sse-starlette<3` in that file conflicts with `mcp>=2.3`; relax it in the
copy), seed `config/apikeys.json` from `config/apikeys.json.example`, then
`SERVER_HOST=127.0.0.1 SERVER_PORT=18991 python run_sajha_web.py`, run the test above, and stop the process by its PID.

## 6. Tests

`McpLayerTest` (mock under policy: roles, masking, `allowed-tools`, credential headers; the Streamable HTTP client against a loopback
stub: handshake, session id, JSON and event-stream answers, DELETE on close), `AiApiTest` (REST), `tests/test_ai_layer.py` (console).
