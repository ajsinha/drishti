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
# LLM providers: one interface, one file per provider

Drishti talks to language models through its **own** interfaces. Which vendor, library or endpoint answers is a file in
`config/ai/`, not a decision in a feature. Spring AI (`spring-ai-starter-model-openai`) is an implementation detail behind the
`openai` provider type; no feature sees it. The layer is **off by default** (`drishti.ai.enabled=false`): while off, no
provider file is read and no model is called. The same shape exists in the console (`drishti-console/core/ai/`, section 8).
MCP servers (tools a model or a feature can call) have a sibling layer: [MCP_CLIENT.md](MCP_CLIENT.md).

## 1. The pieces (server, module `drishti-ai`)

`drishti-ai` has no controllers and depends only on `drishti-api` (the shared `tls.*` module) and `drishti-common`; an ArchUnit
rule (`aiLayerDependsOneWay`) keeps it that way. REST lives in `drishti-server`.

| Type | Role |
|---|---|
| `LlmProvider` | One configured provider: `id()`, `type()`, `capabilities()`, `models()`, `chatModel(name)`, `embeddingModel(name)`. |
| `LlmModel` / `EmbeddingModel` | A model by its logical name: `chat(ChatRequest)` returns a `ChatResponse`; `embed(texts)`. |
| `ChatRequest` / `ChatResponse` | Provider-independent: model reference, system text, messages, tools, temperature, max tokens, JSON mode, timeout; text, tool calls, finish reason, `Usage` (tokens and cost). |
| `Capability` | `chat`, `streaming`, `tool-calls`, `embeddings`, `json-mode`. A model lists what it can do; the gateway refuses a request that needs more (`UNSUPPORTED`). |
| `ProviderFactory` | The extension point: `type()` and `create(ProviderConfig)`. |
| `LlmRegistry` | Providers and models by logical name (`provider/model`, or a bare name only one provider has), health, `reload()`. |
| `LlmPolicy` / `DefaultLlmPolicy` | Hooks before and after every call (section 4). |
| `LlmGateway` | **The one door features use**: resolve the model, run the policy, apply the timeout, record health. |

A feature calls it like this:

```java
ChatResponse r = gateway.chat(new CallContext(user, roles, "ask"),
        ChatRequest.of("provider/model", systemText, userText, maxTokens, Duration.ofSeconds(15)));
```

Failures are `LlmException` with a kind (`CONFIG`, `NOT_FOUND`, `UNSUPPORTED`, `POLICY`, `RATE_LIMITED`, `TIMEOUT`, `FAILED`); the
message never carries a key, a prompt or an upstream response body.

## 2. Provider files

One YAML per provider in `drishti.ai.dir` (`DRISHTI_AI_DIR`, default `./config/ai`), watched and reloaded live like connectors
(`drishti.ai.watch`: `auto` / `poll` / `off`). A bad file is listed with its reason (Admin -> AI) and the others still load.
Files ending `.example` are ignored; copy one and drop the suffix. Ready-made: `config/ai/openai.yaml.example`,
`anthropic.yaml.example`, `mock.yaml.example` (and `server.yaml.example` for the console).

```yaml
type: openai                         # the provider type (the factory); required
enabled: true
base-url: https://api.openai.com
completions-path: /v1/chat/completions
api-key: ${OPENAI_API_KEY}           # an ${ENV_NAME} placeholder ONLY; a literal key is refused and the file does not load
timeout: 60s
retries: 0
default-model: fast                  # used when a call names no model and this is the provider the registry falls to
header: {X-Team: red}                # extra headers; credential-looking ones (auth, key, token, secret) must be ${ENV}
tls: {ca-file: /etc/drishti/ca.pem}  # the shared tls.* keys; https:// trusts the JVM's authorities unless told otherwise
models:
  - name: fast                       # logical name: what features and policy refer to
    id: gpt-4o-mini                  # the provider's own id
    max-tokens: 400
    temperature: 0.2
    capabilities: [chat, tool-calls, json-mode]
    cost-per-1k-input: 0.00015       # drives Usage.cost, audit and the daily cost limit
    cost-per-1k-output: 0.0006
```

The provider name is the file name without its extension (or `name:`). A model is referred to as `provider/model` (for example
`openai/fast`); a bare name works when only one provider has it. The registry default is `drishti.ai.default-model`
(`DRISHTI_AI_DEFAULT_MODEL`), else the only chat model there is.

### A provider that cannot be set up is retried

A file that is well formed can still give a provider that cannot be built (its type is not installed, a setting the type needs is
wrong, an `${ENV}` key is not set). It is listed in Admin -> AI as **ERROR** and **tried again by itself**: after `drishti.retry.ai.initial`
(5 s), then twice as long each time, never longer than `drishti.retry.ai.max` (5 min), with jitter. The row says
*failed - next retry in 20 s (attempt 3)* and the last error; an error that retrying cannot fix (a missing key, an unknown type, three
refusals in a row) is retried only every `max`. A provider that was built is not tested until a call is made: if a call fails it
shows **DOWN** until the next call goes through (every call is attempted afresh; there is no circuit breaker that holds calls back).
A file that cannot be read (bad YAML, a literal key) is read again at every look at the folder (`drishti.ai.poll-ms`), so it clears the moment it is fixed.
**Retry now** (Admin -> AI, `POST /api/v1/admin/retry/ai`, admin, audited as `ai-retry-now`; an API token needs the `packs:admin` scope) builds
every failing provider at once. Failures are logged and audited on attempts 1, 2, 4, 8, ... (`ai-retry-failed`) and a recovery always
(`ai-recovered`). Settings: [CONFIGURATION.md](../admin/CONFIGURATION.md#retry-settings), `drishti.retry.*` and `drishti.retry.ai.*`.

### Types shipped

| `type` | Backed by | Settings beyond the common ones |
|---|---|---|
| `openai` | Spring AI 1.0.3 (`OpenAiApi`, `OpenAiChatModel`, `OpenAiEmbeddingModel`) over the JDK HTTP client | `base-url`, `completions-path`, `embeddings-path`, `header.*`, `retries`. Works with Azure OpenAI, vLLM, Ollama's OpenAI endpoint, LiteLLM, company gateways. Chat, tool calls, JSON mode, embeddings. |
| `anthropic` | the JDK HTTP client (the former Ask provider, ported) | `base-url`, `messages-path`, `anthropic-version`, `header.*`. Spring AI's Anthropic starter is **not** used: one HTTP stack, one TLS module. |
| `mock` | nothing | section 6 |

Spring AI's own auto-configuration is switched off in `application.yaml` (`spring.ai.model.chat: none`, ...): Drishti builds
the clients from the provider files, and nothing starts without a key. Spring AI 1.0.x is compiled for Java 17, so the
enforcer rule `maxJdkVersion 21` still passes and production runs on JDK 21.

## 3. Add a provider in 30 minutes

1. **Write the class** (10 minutes). Extend `BaseProvider` (it reads the model list from the file) and implement `chatModel`
   (and `embeddingModel` if you can):

   ```java
   public final class AcmeProvider extends BaseProvider {
       AcmeProvider(ProviderConfig c) { super(c, Set.of(Capability.JSON_MODE)); }

       @Override public Optional<LlmModel> chatModel(String name) {
           return find(name, false).map(info -> new LlmModel() {
               public ModelInfo info() { return info; }
               public ChatResponse chat(ChatRequest r) {
                   // call Acme with config.baseUrl(), config.apiKey(), info.id(), maxTokens(r, info, 1024), systemText(r), r.messages()
                   // map failures to LlmException(Kind.FAILED / TIMEOUT, "message without keys or prompts")
                   return new ChatResponse(text, List.of(), info.id(), "stop", new Usage(in, out, info.cost(in, out)));
               }
           });
       }

       public static final class Factory implements ProviderFactory {
           public String type() { return "acme"; }
           public LlmProvider create(ProviderConfig c) { return new AcmeProvider(c); }
       }
   }
   ```
2. **Register it** (1 minute): either list `com.example.AcmeProvider$Factory` in
   `META-INF/services/com.ash.drishti.ai.ProviderFactory` (a jar on the server's class path is enough), or declare the factory as a
   Spring `@Bean`.
3. **Write the file** (2 minutes): `config/ai/acme.yaml` with `type: acme`, `base-url`, `api-key: ${ACME_KEY}` and the models.
   Anything extra you need goes in the file under your own keys; read it with `config.setting("key", default)` (nested maps are
   flat: `a.b.c`, lists `a.0.b`).
4. **Test it** (15 minutes) with the `StubServer` pattern from `drishti-ai/src/test/.../StubServer.java` (a loopback HTTP server, no
   network) and the `mock` provider for everything that sits above you. Then Admin -> AI shows your provider and
   `POST /api/v1/admin/ai/test` sends one real call.

Console: write the same class shape in Python (`BaseProvider` in `core/ai/providers/base.py`), then
`core.ai.providers.register("acme", factory)` or add it to `FACTORIES`.

## 4. Policy

`drishti.ai.policy.*` (Admin does not edit these; they are configuration):

| Setting | Meaning |
|---|---|
| `mask-redacted` (true) | Fields named in `drishti.security.redact` are masked in every prompt: `salary: 90000` becomes `salary: •••` (also `name=value` and `"name":"value"`). This is in addition to the masking a page already applied. |
| `mask-patterns` | Extra regular expressions replaced by `•••` in every prompt (card numbers, ...). |
| `audit` (true) | One access-log row per call, action `ai`: user, feature, `provider/model`, prompt and answer sizes, tokens, outcome, milliseconds. **Never the prompt or the answer.** |
| `per-user-per-minute`, `per-user-per-day` | Call limits (0 = none); `features.<feature>.per-user-per-minute` overrides per feature. `RATE_LIMITED` (HTTP 429, DRS-4017). |
| `cost-per-user-per-day` | Stops further calls once a user's cost (from the models' prices) reaches it. |
| `features.<feature>.roles` | Roles allowed to use a feature; empty or absent = every signed-in user. `POLICY` (HTTP 403, DRS-4018). |

Feature names: `ask`, `admin-test`, and `api:<name>` for callers of `POST /api/v1/ai/chat` (the prefix stops an API caller posing as a
built-in feature). Replace the whole policy by providing your own `LlmPolicy` bean.

## 5. REST

| Call | Who | What |
|---|---|---|
| `GET /api/v1/admin/ai` | admin | on/off, folder, provider types, providers with health, models, file errors, MCP servers. |
| `GET /api/v1/admin/mcp/{server}/tools` | admin | an MCP server's tools ([MCP_CLIENT.md](MCP_CLIENT.md)). |
| `POST /api/v1/admin/ai/test` `{model?, prompt?}` | admin | a real call through the policy; audited (`ai-test`); token scope `ai:admin`. |
| `GET /api/v1/ai/models` | signed in | the models features may name. |
| `POST /api/v1/ai/chat` `{model?, system?, messages? \| prompt?, temperature?, maxTokens?, jsonMode?, timeoutSeconds?, feature?}` | signed in | the feature-facing API (used by the console's `server` provider); token scope `ai:use`. |

Errors: DRS-4015 off (404), DRS-4016 failed (502) or slow (504), DRS-4017 over a limit (429), DRS-4018 policy (403), DRS-4019 unknown
model, provider or server (404). Health in Admin -> AI comes from configuration and from the calls seen: `OFF`, `ERROR` (the file
or its type failed), `UNKNOWN` (no call yet), `UP`, `DOWN` (the last call failed). No probe is sent on its own; **Test** sends one.

## 6. The mock provider

`type: mock` (server and console): deterministic, no network, so features and tests run end to end without a real model. Settings
under `mock:`: `reply` (fixed text; omit to echo `echo: <last user message>`), `latency-ms`, `fail` (every call fails), `fail-every`
(every Nth), `dimensions` (embedding size), and `rules` (the first whose regex is found in the last user message applies:
`reply`, `tool` + `tool-args` for a tool-call script, `error`, `latency-ms`). Usage is characters / 4, priced by the model's
`cost-per-1k-*`, so limits and audit can be exercised. `config/ai/mock.yaml.example` is a complete file.

## 7. Ask on the AI layer

Ask about this page ([CONTEXT_HELP.md](CONTEXT_HELP.md)) builds its prompt as before and calls the gateway with feature `ask`.
Point it at a model with `drishti.explain.ask.model: <provider>/<model>`. The original settings (`endpoint`, `api`, `api-key`) are
**deprecated but unchanged in behaviour**: they become provider `ask` (with the endpoint split into `base-url` and path), work even
when `drishti.ai.enabled` is false, and log a warning at start. A provider file may not be named `ask` while they are set.

## 8. The console

`drishti-console/core/ai/` mirrors the server with `typing.Protocol`s in `protocols.py` (`LlmProvider`, `ChatModel`, `EmbeddingModel`,
`Registry`, `Policy`), httpx (already a dependency) and no vendor SDK:

| Server | Console |
|---|---|
| `openai`, `anthropic`, `mock` | the same three types, same file format and keys (`tls.ca-file`, `tls.cert-file`, `tls.key-file` for TLS) |
| - | **`server`**: delegates to the server's `POST /api/v1/ai/chat` with the signed-in identity; keys, policy, masking, limits and audit stay on the server. **The recommended console provider.** The model `id` is the server's `provider/model`; omit it for the server's default. |

The console's layer follows its folders too, with no restart: a supervisor looks at `ai.dir` and `mcp.dir` every `ai.poll_seconds` (5), reloads
when a file changed, and retries what failed with `retry.*` / `retry.ai.*` / `retry.mcp.*` (the console's equivalents of `drishti.retry.*`; same schedule, same
rules). Admin -> AI shows the *failed - next retry in Ns (attempt k)* line for the console's providers and servers as it does for the server's, and its Retry now
button tries both layers at once.

Settings (`drishti-console/config/application.yaml`): `ai.enabled` (`DRISHTI_AI_ENABLED`, false), `ai.dir` (`DRISHTI_AI_DIR`),
`ai.default_model`, `ai.redact` (the field names to mask), `ai.policy.*` (same meaning as above). Console code calls
`request.app.state.ai.gateway.chat(CallContext(...), ChatRequest.of(...))`; the ready-made route is `POST /api/ai/chat` (JSON only,
signed in, feature `console:<name>`). **Admin -> AI** is a read-only page for both layers: on or off, providers with health and
models, MCP servers and (one server at a time, by link) their tools. The console's Ask stays a thin proxy to the server's Ask.

## 9. Tests

`drishti-ai`: `LlmLayerTest` (mock, files, registry and reload, a stub OpenAI-compatible server incl. headers, cost and error
handling, a stub Anthropic server, policy), `McpLayerTest`. `drishti-server`: `AiApiTest` (REST end to end with the mock),
`AskEndToEndTest` and friends (Ask on the layer, incl. the legacy settings), `AskOffByDefaultTest`. Console:
`tests/test_ai_layer.py`. None touches a network beyond loopback.
