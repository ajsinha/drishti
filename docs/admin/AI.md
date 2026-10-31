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
# AI: model providers, MCP servers, AI management and the quant service

Everything about AI in Drishti, in one place: the **model providers** (section 1: one interface, one file per provider, seventeen
vendor types beside `openai`, `anthropic`, `server` and `mock`), the **MCP client** (section 2: other servers' tools, resources and
prompts behind Drishti's policy), the design of **AI management** (section 3: one console for every AI setting, use and review), and
the design of **server-side pricing**, the quant service (section 4), with the console's lean `QuantEngine` slot that is built.

| Part | Status |
|---|---|
| 1. Model providers | built; **off by default** (`drishti.ai.enabled=false`) |
| 2. The MCP client | built; **off by default** (`drishti.mcp.enabled=false`) |
| 3. AI management | design, not built (parked with the AI wave; the plan files AI and GenUI under 2.12.0) |
| 4. The quant service | proposed, not built, except the console's `QuantEngine` slot (section 4, its section 16) |

Each part keeps its own section numbers (part 1's "section 4" is *4. Policy* inside part 1). The pages that use the layer are
[*Ask about this page*](../guides/USER_GUIDE.md#about-this-page) and BI's AI hooks ([BI_GUIDE.md](../guides/BI_GUIDE.md#29-ai-in-bi-report-from-words-ask-the-data-explain-this-number));
the settings are in [CONFIGURATION.md](CONFIGURATION.md).

## Contents

1. [Model providers](#1-model-providers)
    - [1. The pieces (server, module `drishti-ai`)](#1-the-pieces-server-module-drishti-ai)
    - [2. Provider files](#2-provider-files)
    - [3. Add a provider in 30 minutes](#3-add-a-provider-in-30-minutes)
    - [4. Policy](#4-policy)
    - [5. REST](#5-rest)
    - [6. The mock provider](#6-the-mock-provider)
    - [7. Ask on the AI layer](#7-ask-on-the-ai-layer)
    - [8. The console](#8-the-console)
    - [9. Tests](#9-tests)
    - [10. One design in two languages: the sajhamcpserver abstractions](#10-one-design-in-two-languages-the-sajhamcpserver-abstractions)
    - [11. More providers, the way sajhamcpserver has them](#11-more-providers-the-way-sajhamcpserver-has-them)
2. [The MCP client: other servers' tools, behind Drishti's policy](#2-the-mcp-client-other-servers-tools-behind-drishtis-policy)
    - [1. Interfaces](#1-interfaces)
    - [2. Server files](#2-server-files)
    - [3. Policy (`drishti.mcp.*`)](#3-policy-drishtimcp)
    - [4. The mock server](#4-the-mock-server)
    - [5. Connecting to SAJHA (the user's MCP server)](#5-connecting-to-sajha-the-users-mcp-server)
    - [6. Tests](#6-tests)
    - [7. The tool base class](#7-the-tool-base-class)
3. [AI management (design, not built)](#3-ai-management-design-not-built)
    - [1. Where we are](#1-where-we-are)
    - [2. Principles](#2-principles)
    - [3. Admin → AI, rebuilt (administrators; `ai:admin`)](#3-admin--ai-rebuilt-administrators-aiadmin)
    - [4. Review → AI drafts (authors and approvers)](#4-review--ai-drafts-authors-and-approvers)
    - [5. For everyone: My AI (new, in the user menu)](#5-for-everyone-my-ai-new-in-the-user-menu)
    - [6. APIs (new; `ai:admin` unless noted)](#6-apis-new-aiadmin-unless-noted)
    - [7. Build plan (2.9.0, console lane with its server endpoints)](#7-build-plan-290-console-lane-with-its-server-endpoints)
    - [8. Tests](#8-tests)
4. [Server-side pricing: the quant service (proposed)](#4-server-side-pricing-the-quant-service-proposed)
    - [1. What it is for](#1-what-it-is-for)
    - [2. The shape](#2-the-shape)
    - [3. Why a separate service](#3-why-a-separate-service)
    - [4. What the Drishti server does](#4-what-the-drishti-server-does)
    - [5. What the quant service does](#5-what-the-quant-service-does)
    - [6. Packs declare how products are priced](#6-packs-declare-how-products-are-priced)
    - [7. Market data for a business date](#7-market-data-for-a-business-date)
    - [8. How it reaches the screen](#8-how-it-reaches-the-screen)
    - [9. The API](#9-the-api)
    - [10. Security](#10-security)
    - [11. Caching and performance](#11-caching-and-performance)
    - [12. Failure and operations](#12-failure-and-operations)
    - [13. Testing](#13-testing)
    - [14. Phases](#14-phases)
    - [15. Risks and open questions](#15-risks-and-open-questions)
    - [16. The console's QuantEngine slot (built, lean)](#16-the-consoles-quantengine-slot-built-lean)
    - [17. Reconciled with the 2.8.0 compute workers](#17-reconciled-with-the-280-compute-workers)

## 1. Model providers

Drishti talks to language models through its **own** interfaces. Which vendor, library or endpoint answers is a file in
`config/ai/`, not a decision in a feature. Spring AI (`spring-ai-starter-model-openai`) is an implementation detail behind the
`openai` provider type; no feature sees it. The layer is **off by default** (`drishti.ai.enabled=false`): while off, no
provider file is read and no model is called. The same shape exists in the console (`drishti-console/core/ai/`, section 8).
MCP servers (tools a model or a feature can call) have a sibling layer: [section 2](#2-the-mcp-client-other-servers-tools-behind-drishtis-policy). Beyond `openai`, `anthropic` and `mock`, seventeen more provider types (Groq, Together, Fireworks, DeepSeek, xAI, OpenRouter, Perplexity, Hugging Face, vLLM, LM Studio, Mistral, any OpenAI-compatible server, Azure OpenAI, Gemini, Cohere, Ollama, AWS Bedrock) come from sajhamcpserver: [section 11](#11-more-providers-the-way-sajhamcpserver-has-them).

### 1. The pieces (server, module `drishti-ai`)

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

### 2. Provider files

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
proxy:                               # optional outbound HTTP proxy (see "Outbound proxy" below); `proxy: none` goes direct
  url: http://proxy.corp.example:3128
  username: svc-drishti
  password: ${PROXY_PASSWORD}   # ${ENV} only; a literal is refused and the file does not load
  no-proxy: [localhost, .corp.example]
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

#### Outbound proxy

A site that reaches the model endpoint only through a forward proxy puts a `proxy:` block in the provider file (`url`, `username`, `password` as `${ENV}`, `no-proxy`, `enabled`), or
sets the site default once: `drishti.http.proxy.*` on the server, `DRISHTI_HTTP_PROXY_URL` and friends in the console. `proxy: none` in a file goes direct whatever the site says.
`http://` and `https://` endpoints both go through an `http://` proxy; an https endpoint is tunnelled with `CONNECT`, so `tls.*` (a private CA, a client certificate) still applies to the
endpoint itself. OpenAI-compatible and Anthropic providers use it on the server (the JDK HTTP client) and in the console (`httpx`). A literal `proxy.password` refuses the file without
repeating it; a wrong proxy login is reported as a failure of the call without the password. Everything is in [TLS.md (proxy)](../connectors/TLS.md#proxy-9-the-console-ai-and-mcp-clients). Tested in
`AiProxyTest` (Java) and `tests/test_ai_proxy.py` (console) against a fake proxy on loopback. Admin -> AI does not display the proxy yet.

#### A provider that cannot be set up is retried

A file that is well formed can still give a provider that cannot be built (its type is not installed, a setting the type needs is
wrong, an `${ENV}` key is not set). It is listed in Admin -> AI as **ERROR** and **tried again by itself**: after `drishti.retry.ai.initial`
(5 s), then twice as long each time, never longer than `drishti.retry.ai.max` (5 min), with jitter. The row says
*failed - next retry in 20 s (attempt 3)* and the last error; an error that retrying cannot fix (a missing key, an unknown type, three
refusals in a row) is retried only every `max`. A provider that was built is not tested until a call is made: if a call fails it
shows **DOWN** until the next call goes through (every call is attempted afresh; there is no circuit breaker that holds calls back).
A file that cannot be read (bad YAML, a literal key) is read again at every look at the folder (`drishti.ai.poll-ms`), so it clears the moment it is fixed.
**Retry now** (Admin -> AI, `POST /api/v1/admin/retry/ai`, admin, audited as `ai-retry-now`; an API token needs the `packs:admin` scope) builds
every failing provider at once. Failures are logged and audited on attempts 1, 2, 4, 8, ... (`ai-retry-failed`) and a recovery always
(`ai-recovered`). Settings: [CONFIGURATION.md](CONFIGURATION.md#retry-settings), `drishti.retry.*` and `drishti.retry.ai.*`.

#### Types shipped

| `type` | Backed by | Settings beyond the common ones |
|---|---|---|
| `openai` | Spring AI 1.0.3 (`OpenAiApi`, `OpenAiChatModel`, `OpenAiEmbeddingModel`) over the JDK HTTP client | `base-url`, `completions-path`, `embeddings-path`, `header.*`, `retries`. Works with Azure OpenAI, vLLM, Ollama's OpenAI endpoint, LiteLLM, company gateways. Chat, tool calls, JSON mode, embeddings. |
| `anthropic` | the JDK HTTP client (the former Ask provider, ported) | `base-url`, `messages-path`, `anthropic-version`, `header.*`. Spring AI's Anthropic starter is **not** used: one HTTP stack, one TLS module. |
| `mock` | nothing | section 6 |

Spring AI's own auto-configuration is switched off in `application.yaml` (`spring.ai.model.chat: none`, ...): Drishti builds
the clients from the provider files, and nothing starts without a key. Spring AI 1.0.x is compiled for Java 17, so the
enforcer rule `maxJdkVersion 21` still passes and production runs on JDK 21.

### 3. Add a provider in 30 minutes

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
3. **Write the file** (2 minutes): `config/ai/<name>.yaml` (here `acme.yaml`; the shipped providers are `config/ai/*.yaml.example`) with `type: acme`, `base-url`, `api-key: ${ACME_KEY}` and the models.
   Anything extra you need goes in the file under your own keys; read it with `config.setting("key", default)` (nested maps are
   flat: `a.b.c`, lists `a.0.b`).
4. **Test it** (15 minutes) with the `StubServer` pattern from `drishti-ai/src/test/.../StubServer.java` (a loopback HTTP server, no
   network) and the `mock` provider for everything that sits above you. Then Admin -> AI shows your provider and
   `POST /api/v1/admin/ai/test` sends one real call.

Console: write the same class shape in Python (`BaseProvider` in `core/ai/providers/base.py`), then
`core.ai.providers.register("acme", factory)` or add it to `FACTORIES`.

### 4. Policy

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

### 5. REST

| Call | Who | What |
|---|---|---|
| `GET /api/v1/admin/ai` | admin | on/off, folder, provider types, providers with health, models, file errors, MCP servers. |
| `GET /api/v1/admin/mcp/{server}/tools` | admin | an MCP server's tools ([section 2](#2-the-mcp-client-other-servers-tools-behind-drishtis-policy)). |
| `POST /api/v1/admin/ai/test` `{model?, prompt?}` | admin | a real call through the policy; audited (`ai-test`); token scope `ai:admin`. |
| `GET /api/v1/ai/models` | signed in | the models features may name. |
| `POST /api/v1/ai/chat` `{model?, system?, messages? \| prompt?, temperature?, maxTokens?, jsonMode?, timeoutSeconds?, feature?}` | signed in | the feature-facing API (used by the console's `server` provider); token scope `ai:use`. |

Errors: DRS-4015 off (404), DRS-4016 failed (502) or slow (504), DRS-4017 over a limit (429), DRS-4018 policy (403), DRS-4019 unknown
model, provider or server (404). Health in Admin -> AI comes from configuration and from the calls seen: `OFF`, `ERROR` (the file
or its type failed), `UNKNOWN` (no call yet), `UP`, `DOWN` (the last call failed). No probe is sent on its own; **Test** sends one.

### 6. The mock provider

`type: mock` (server and console): deterministic, no network, so features and tests run end to end without a real model. Settings
under `mock:`: `reply` (fixed text; omit to echo `echo: <last user message>`), `latency-ms`, `fail` (every call fails), `fail-every`
(every Nth), `dimensions` (embedding size), and `rules` (the first whose regex is found in the last user message applies:
`reply`, `tool` + `tool-args` for a tool-call script, `error`, `latency-ms`). Usage is characters / 4, priced by the model's
`cost-per-1k-*`, so limits and audit can be exercised. `config/ai/mock.yaml.example` is a complete file.

### 7. Ask on the AI layer

Ask about this page ([CONTEXT_HELP.md](../architecture/CONTEXT_HELP.md)) builds its prompt as before and calls the gateway with feature `ask`.
Point it at a model with `drishti.explain.ask.model: <provider>/<model>`. The original settings (`endpoint`, `api`, `api-key`) are
**deprecated but unchanged in behaviour**: they become provider `ask` (with the endpoint split into `base-url` and path), work even
when `drishti.ai.enabled` is false, and log a warning at start. A provider file may not be named `ask` while they are set.

#### BI uses (stage 2 of Rūpaka)

Seven features use the layer through `LLMFactory.model(...)` ([section 10](#10-one-design-in-two-languages-the-sajhamcpserver-abstractions)): **`bi-draft`** (report from words), **`bi-ask`**
(ask the data), **`bi-explain`** (the narrative of "explain this number", only when `drishti.bi.ai.values` is `aggregates`), **`bi-suggest`** (the designer copilot's visuals, wells and filters),
**`bi-explain-checks`** (the Check panel's problems in plain words), **`bi-helper`** (the dataset helper's relationships, measures and column descriptions) and **`subscription-narrative`** (the written
summary at the top of a subscription's email, made **as the recipient** from their own unmasked figures, only in `aggregates` mode). Each is a feature name for the policy (section 4), so masks,
per-feature roles and limits, the cost limit and the audit row apply; each runs under the caller's identity and sends the dataset **model** (names, types, descriptions) and the person's words,
never a row. Point them at a model with `drishti.bi.ai.model`. Details, settings, errors (`DRS-4020`: an answer that cannot be used) and tests: [BI_GUIDE.md section 29](../guides/BI_GUIDE.md#29-ai-in-bi-report-from-words-ask-the-data-explain-this-number).

### 8. The console

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
models, MCP servers and (one server at a time, by link) their tools. Each console provider has a **Test connection** button: it calls the provider's `probe()` (one cheap live call such as a model list) and `live_models()` once, on request, and shows the answer inline (`POST /admin/ai/api/test/<name>`: administrators only, a JSON body, at most 15 seconds, one test per provider every two seconds, the key never in the answer). `health()` still never calls the network. Every test is an `ai-audit` line in the console's log (who, provider, outcome, milliseconds). The console's Ask stays a thin proxy to the server's Ask.

### 9. Tests

`drishti-ai`: `AiProxyTest` (the proxy: credentials from the environment, the site default, `proxy: none`, no-proxy, a literal password refused), `LlmLayerTest` (mock, files, registry and reload, a stub OpenAI-compatible server incl. headers, cost and error
handling, a stub Anthropic server, policy), `McpLayerTest`. `drishti-server`: `AiApiTest` (REST end to end with the mock),
`AskEndToEndTest` and friends (Ask on the layer, incl. the legacy settings), `AskOffByDefaultTest`. Console:
`tests/test_ai_layer.py`. None touches a network beyond loopback. The providers of section 11 have their own tests ([11.10](#1110-tests)).

### 10. One design in two languages: the sajhamcpserver abstractions

The AI layer exposes the **same abstractions as the user's other project, sajhamcpserver** (`sajha/ai/llm/`), in Python (console, `core/ai/llm/`) and Java (`drishti-ai`, package
`com.ash.drishti.ai.llm`), so that code written against one reads like code written against the other. sajhamcpserver is the model; nothing is imported from it. Two things differ on purpose: the
extension field of a request and a response is **`drishti`** (sajhamcpserver's is `sajha`), and the Java names are the Python names in camelCase.

**Layers.** Under the abstractions the *wire* providers are unchanged (`openai` through Spring AI, `anthropic`, `mock`, and in the console `server`): they speak the small request and response types of the
earlier layers (`ChatRequest`, `ChatResponse`) and carry the HTTP, TLS and proxy code. `ProviderBase` implements `LLMProvider` over a wire provider and translates in one place
(`Convert` in Java, `to_wire` / `from_wire` in `core/ai/llm/provider.py`). The provider file format (`config/ai/<name>.yaml`) is **unchanged**; `from_settings` takes the same block as a map.
The policy (masks, audit, limits, feature roles: section 4) is applied by `GovernedModel`, which `LLMFactory.model()` hands out; so both the old `LlmGateway.chat` and the new `LLMModel`
calls pass the same policy and count in the same health.

| Python (`core.ai.llm`) | Java (`com.ash.drishti.ai.llm`) | Role |
|---|---|---|
| `LLMModel` | `LLMModel` | one model: `info`, `chat_completions_create` / `chatCompletionsCreate`, `achat_completions_create` / `achatCompletionsCreate`, `chat_completions_stream` / `chatCompletionsStream`, `achat_completions_stream` / `achatCompletionsStream`, `embeddings_create` / `embeddingsCreate`, `aembeddings_create` / `aembeddingsCreate` |
| `LLMProvider` | `LLMProvider` | `from_settings`, `active`, `models`, `model(name, kind)`, `chat_model(model_id, **options)`, `embedding_model`, `health`, `describe_config`, `close` (camelCase in Java; `from_settings` is `LLMProviderFactory.fromSettings`, found by type in `LLMProviders.fromSettings`, because a Java interface cannot require a static) |
| `ChatModel`, `EmbeddingModel` | `ChatModel`, `EmbeddingModel` | base classes: implement `_create` / `_acreate` (Java `create`) or `_embed` / `_aembed` (Java `embed`); streaming is emulated from one completion |
| `ProviderBase` | `ProviderBase` | `LLMProvider` over a wire provider |
| `ChatCompletionRequest`, `ChatCompletion`, `ChatCompletionChunk` | same | OpenAI Chat Completions, typed (`coerce` builds a request from keyword fields; Java: `ChatCompletionRequest.coerce(map)`) |
| `EmbeddingsRequest`, `EmbeddingsResponse`, `EmbeddingData`, `EmbeddingsUsage` | same | OpenAI Embeddings |
| `ChatMessage`, `ToolCall`, `FunctionCall`, `ToolDefinition`, `FunctionDefinition`, `ResponseFormat`, `CompletionUsage`, `Choice`, `ChoiceDelta`, `ChunkChoice` | nested in `Canonical` | the parts |
| `DrishtiRequest` (`context`, `trace_id`, `input_purpose`), `ResponseDrishti` (`provider`, `qualified_model`, `cost_usd`, `cached`, `latency_ms`, `trace_id`) | `DrishtiRequest`, `ResponseDrishti` | the extension fields; `context` is the caller (`CallContext`) the policy decides on |
| `ModelInfo` (`id`, `provider`, `kind`, `capabilities`, `display_name`, `source`, `qualified_id`), `ModelCapabilities`, `HealthStatus` | same | catalogue entry; capability costs are per million tokens |
| `register_provider`, `registered_providers`, `provider_class`, `load_class` | `LLMProviders.register`, `.registered`, `.fromSettings` | the provider-type registry (the shipped types are registered at start) |
| `LLMFactory.model(name, *, kind, context)` -> `GovernedModel`, `.provider(name)`, `.provider_names()`, `.provider_types()` and the six passthrough calls | `LLMFactory.model(name, kind, context)`, `.provider`, `.providerNames`, `.providerTypes` and the passthroughs | the one door application code uses; policy applied |
| `LLMError` and `RateLimited`, `ProviderUnavailable`, `AuthenticationFailed`, `ContextTooLong`, `ContentFiltered`, `UnsupportedFeature`, `ModelFailed`, `InvalidRequest`, `PolicyDenied`, `BudgetExceeded`, `NoModelAvailable`, `ConfigurationError` | `LlmException` with `Kind` | each Python class has a `kind` equal to the Java `Kind`: `FAILED`, `UNSUPPORTED`, `NOT_FOUND`, `POLICY`, `RATE_LIMITED`, `TIMEOUT`, `CONFIG` |
| `BaseMCPTool` (`core/ai/mcp_tool.py`) | `BaseMcpTool` | the tool base class: `execute`, `get_input_schema`, `get_output_schema` (abstract), `validate_arguments`, `execute_with_tracking`, `get_metrics`, `to_mcp_format`, `load_from_config`, `enable` / `disable` ([section 2](#2-the-mcp-client-other-servers-tools-behind-drishtis-policy) section 7) |

```python
factory = request.app.state.ai.llm                                   # an LLMFactory over the console's gateway and policy
model = factory.model("default", context=CallContext(user, roles, "my-feature", ident))
completion = await model.achat_completions_create(messages=[{"role": "user", "content": "hello"}])
completion.text, completion.usage.total_tokens, completion.drishti.cost_usd
```

```java
LLMModel model = factory.model("default", "chat", new CallContext(user, roles, "my-feature"));
ChatCompletion c = model.chatCompletionsCreate(ChatCompletionRequest.of("default", "be brief", "hello"));
c.text(); c.usage().totalTokens(); c.drishti().costUsd();
```

**What differs from sajhamcpserver, honestly.** Streaming is **emulated** (a completion cut into role, text and finish chunks; the signatures are final, the wire providers do not stream yet); the
canonical request fields beyond those in `ChatCompletionRequest` (images, logprobs, ...) are not carried to the wire providers; Python's `**options` of `chat_model` and Java's options map are accepted
and ignored; the fallback, cache, budget and breaker layers of sajhamcpserver's `Governor` are not built (Drishti's policy is section 4). The registry, factory and settings are the same shape; the layered
environment overrides (`SAJHA_AI_*`) are Drishti's own `DRISHTI_AI_*` and the provider files.

**Tests.** One shared scenario file, `drishti-ai/src/test/resources/contract/llm_contract.json` (catalogue, chat, tool call, a scripted failure, a stream, embeddings, refusals), is run against the mock
by `LLMContractTest` (Java) and `tests/test_llm_contract.py` (Python); `BaseMcpToolTest` and the last test of the Python file cover the tool base.

### 11. More providers, the way sajhamcpserver has them

Seventeen provider types joined `openai`, `anthropic`, `server` and `mock`, mirroring `sajha/ai/llm/providers/*.py` of sajhamcpserver: the type names, the settings (sajha's `snake_case` fields are
`kebab-case` keys of the provider file), the model catalogue and the presets. They sit on the same abstractions (section 10), in **both** languages, behind the same policy (masks, audit,
limits, roles, labels-only: section 4). **Every one is off until you copy its example from `config/ai/<type>.yaml.example` to `config/ai/<name>.yaml`, set `enabled: true`** and
`drishti.ai.enabled=true`. A provider file is the same in the server and the console.

| `type:` | Vendor and API | Server (Java, `com.ash.drishti.ai`) | Console (Python, `core/ai/providers`) |
|---|---|---|---|
| `groq`, `together`, `fireworks`, `deepseek`, `xai`, `openrouter`, `perplexity`, `huggingface`, `vllm`, `lmstudio`, `mistral`, `openai_compatible` | OpenAI Chat Completions | `OpenAiCompatProvider` | `openai_compat.py` |
| `azure_openai` | Azure OpenAI: api key, bearer or Microsoft Entra ID | `AzureOpenAiProvider`, `EntraTokenSource` | `azure.py`, `cloud_auth.py` |
| `gemini` | Google Gemini API (AI Studio) | `GeminiProvider` | `gemini.py` |
| `cohere` | Cohere v2 | `CohereProvider` | `cohere.py` |
| `ollama` | a local Ollama server, no key | `OllamaProvider` | `ollama.py` |
| `bedrock` | Amazon Bedrock: Converse, InvokeModel, ListFoundationModels | `BedrockProvider` (AWS SDK v2) | `bedrock.py` (boto3) |

**What every one of them does.**

* **Chat** with text turns, a system text, tools (OpenAI-style `tool_calls` mapped to and from the vendor's shape), JSON mode, temperature and the max-token limit; **embeddings** where the API has them
  (OpenAI-compatible, Azure, Gemini, Cohere, Ollama, Bedrock); the cost of a call from the catalogue prices.
* **Streaming** is the layer's emulation (section 10): the signature is final, the wire call is one request.
* **Model listing.** `models()` is the catalogue (`ai/llm_catalog.json`, mirrored from sajha's `catalog.py`, prices per million tokens) plus the file's `models:`; a file model with the same name wins, and
  `catalogue: false` keeps only the file's. `live_models()` (Python) / `liveModels()` (Java) asks the endpoint what it serves now and returns the ones not already listed, with source `live`: `GET /models`
  for the keyless OpenAI-compatible servers (`vllm`, `lmstudio`, `openai_compatible`; `live-models: true` turns it on for others), `/api/tags` for Ollama, `/v1beta/models` for Gemini, `/v1/models` for Cohere,
  `ListFoundationModels` for Bedrock. A vendor's new model id is a line in `models:`, never a code change. A logical name is the id with characters outside `A-Za-z0-9._-` replaced by `_`
  (`openai/gpt-oss-20b` is `openai_gpt-oss-20b`); `groq/llama-3.3-70b-versatile` addresses a model as `<provider>/<logical name>`.
* **Health.** `health()` never calls the network: it is *down* for `enabled: false` and for a missing key, credential or region (with the variable to set), else *ok*; once a certificate given by `tls.*` is
  less than 30 days from expiry the text carries the sentence of [TLS.md](../connectors/TLS.md#9-certificate-expiry). `probe()` (Python also `aprobe()`) makes one cheap live call (the model list; Ollama
  `/api/tags`) and answers the same shape; Admin shows both.
* **`describe_config()`** shows the file with every secret as `********` (the key, `header.*` credentials, `proxy.password`, `aws-*`, `client-secret`, `tls.*` passwords); the file's other settings are shown as written.
* **Keys only as `${ENV}`.** `api-key`, `aws-access-key-id`, `aws-secret-access-key`, `aws-session-token`, `client-secret` and the `tls.*` passwords accept an `${ENV_NAME}` placeholder or nothing; a literal is
  refused at load, naming the setting. When `api-key` is left out, a preset reads the vendor's own variable (the table below).
* **Proxy.** The shared `proxy.*` block (or the site default) applies to every call, an `https://` target tunnelled with CONNECT: [TLS.md (proxy)](../connectors/TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers).
* **TLS.** The shared `tls.*` block of the connectors applies to every provider type (section 11.9).
* **Errors** name the cause and never carry the response body: HTTP 401/403 `(authentication failed ...)`, 404 `(not found: check base-url, the path and the model id)`, 429 kind `RATE_LIMITED`, a slow
  endpoint kind `TIMEOUT`, anything else `FAILED`. `retries: n` tries again after a 5xx, a 429 or a connection failure.

#### 11.1 OpenAI-compatible presets

A preset is a base URL, a key variable and a catalogue; the code is the same. `openai_compatible` has none of them: give `base-url`.

| `type:` | Default `base-url` | Key read from (when `api-key` is left out) | Needs a key | Embeddings |
|---|---|---|---|---|
| `groq` | `https://api.groq.com/openai/v1` | `GROQ_API_KEY` | yes | no |
| `together` | `https://api.together.xyz/v1` | `TOGETHER_API_KEY` | yes | yes |
| `fireworks` | `https://api.fireworks.ai/inference/v1` | `FIREWORKS_API_KEY` | yes | yes |
| `deepseek` | `https://api.deepseek.com/v1` | `DEEPSEEK_API_KEY` | yes | no |
| `xai` | `https://api.x.ai/v1` | `XAI_API_KEY` | yes | no |
| `openrouter` | `https://openrouter.ai/api/v1` | `OPENROUTER_API_KEY` | yes | no |
| `perplexity` | `https://api.perplexity.ai` | `PERPLEXITY_API_KEY`, `PPLX_API_KEY` | yes | no |
| `huggingface` | `https://router.huggingface.co/v1` | `HF_TOKEN`, `HUGGING_FACE_HUB_TOKEN` | yes | no |
| `vllm` | `http://localhost:8000/v1` | `VLLM_API_KEY` | no | yes |
| `lmstudio` | `http://localhost:1234/v1` | `LMSTUDIO_API_KEY` | no | yes |
| `mistral` | `https://api.mistral.ai/v1` | `MISTRAL_API_KEY` | yes | yes (`mistral-embed`) |
| `openai_compatible` | none (required) | `api-key` only | no | yes |

```yaml
# config/ai/fast.yaml
type: groq
enabled: true
api-key: ${GROQ_API_KEY}
timeout: 60s
default-model: llama-3.3-70b-versatile
models:                                  # optional: add to or override the catalogue
  - {name: gpt-oss, id: openai/gpt-oss-20b, capabilities: [chat, tool-calls, json-mode], cost-per-1k-input: 0.000075, cost-per-1k-output: 0.0003}
```

Settings: `base-url`, `completions-path` / `chat-path` (`/chat/completions`), `embeddings-path` (`/embeddings`), `max-tokens-param` (`max_tokens`; `max_completion_tokens` for a server that wants it; Mistral
declares `max_tokens`), `live-models`, `models-path` (`/models`), `header.<Name>` (extra headers; a credential as `${ENV}`), `retries`, `proxy.*`, `tls.*`. A request goes to
`POST {base-url}/chat/completions` with `Authorization: Bearer <key>`:

```json
{"model": "llama-3.3-70b-versatile", "messages": [{"role": "user", "content": "ping"}], "max_tokens": 1024, "stream": false}
```

Built: chat, tools, JSON object mode, embeddings, model list. Not built (the layer's request type does not carry them yet): `tool_choice` and the vendor spellings of it (`tool-choice-required`),
`seed`, `parallel_tool_calls`, `reasoning_effort`, `strict` JSON schemas, images.

#### 11.2 Azure OpenAI

```yaml
# config/ai/azure.yaml
type: azure_openai
enabled: true
base-url: https://my-resource.openai.azure.com        # also AZURE_OPENAI_ENDPOINT
api-style: v1                                          # v1: /openai/v1/chat/completions, the deployment as "model"
auth: api_key                                          # api_key: the api-key header | bearer | entra
api-key: ${AZURE_OPENAI_API_KEY}
deployments:                                           # model id: deployment name; each is also a model of this provider
  gpt-4o: my-gpt4o-deployment
  text-embedding-3-small: my-embedding-deployment
```

`api-style: deployments` addresses `/openai/deployments/<deployment>/chat/completions?api-version=<api-version>` (`api-version` defaults to 2024-10-21). The limit is sent as
`max_completion_tokens`. **Microsoft Entra ID** (`auth: entra`): `tenant-id`, `client-id`, `client-secret: ${AZURE_CLIENT_SECRET}` (the client-credentials grant) or `federated-token-file` (workload identity, the
grant with a client assertion); `entra-mode: auto | client_secret | workload_identity`, `entra-scope` (`https://cognitiveservices.azure.com/.default`), `entra-authority`
(`https://login.microsoftonline.com`). The token is cached until a minute before it expires and is fetched through the provider's own proxy and TLS settings. **Built in both languages without an SDK; managed identity
(the instance metadata endpoint) is not built**, and a file that asks for it (`entra` with no secret and no federated file) is *down* with that sentence. Azure has no model-list call every key may make: the models
are the `deployments` (and `models:`).

#### 11.3 Gemini

```yaml
# config/ai/gemini.yaml
type: gemini
enabled: true
api-key: ${GEMINI_API_KEY}              # GEMINI_API_KEY or GOOGLE_API_KEY is also read when this line is left out
```

`POST {base-url}/v1beta/models/<id>:generateContent` with `x-goog-api-key`; embeddings by `:batchEmbedContents` (`embedding-task-type`, default `RETRIEVAL_DOCUMENT`). `schema-mode: json_schema`
sends tool schemas as `parametersJsonSchema`; `openapi` sends the OpenAPI subset as `parameters`. `api-version` (v1beta), `base-url` (also `GEMINI_BASE_URL`). **Vertex AI (`platform: vertex`) is not built**
and is refused at load.

#### 11.4 Mistral

`type: mistral` is the OpenAI-compatible provider with Mistral's declarations (`max_tokens`, key `MISTRAL_API_KEY`, `mistral-embed`). Copy `config/ai/mistral.yaml.example`; the settings are those of 11.1.

#### 11.5 Cohere

```yaml
type: cohere
enabled: true
api-key: ${COHERE_API_KEY}              # COHERE_API_KEY or CO_API_KEY is also read when this line is left out
```

`POST /v2/chat` and `/v2/embed` with a bearer key (`chat-path`, `embed-path`, `embedding-input-type` default `search_document`); the model list is `GET /v1/models`. The `document` tool-result format is not built.

#### 11.6 Ollama

```yaml
type: ollama
enabled: true
base-url: http://localhost:11434         # also OLLAMA_HOST; a bare host:port gets http://
timeout: 300s                            # a model that is not loaded yet is slow to answer
options: {top_k: 40}
```

No key. `POST /api/chat` (`stream: false`; tool-call ids are made up as `call_<n>`), `/api/embed`; `options.<name>` (any runtime option), `num-ctx`, `keep-alive`, `think`. An optional `api-key` is sent as a bearer
token, for a proxy in front of Ollama. `live_models()` lists what is pulled (`/api/tags`); `probe()` says `http://host:11434: 2 model(s) pulled`, or that it is not answering, without raising. `/api/show` capability
detection is not built: declare `capabilities` in `models:`.

#### 11.7 AWS Bedrock

Built in **both** languages on the AWS SDKs, so the whole AWS credential chain works: environment keys, a shared-credentials `profile`, SSO, container and instance roles, web identity (IRSA). Java uses the
SDK v2 modules `bedrockruntime` and `bedrock` (with the JDK's HTTP clients only, as the S3 connector does: no Netty); Python uses `boto3` (in `requirements.txt`).

```yaml
# config/ai/bedrock.yaml
type: bedrock
enabled: true
region: us-east-1                        # also AWS_REGION / AWS_DEFAULT_REGION
# profile: my-profile                    # else the default chain
# aws-access-key-id: ${AWS_ACCESS_KEY_ID}
# aws-secret-access-key: ${AWS_SECRET_ACCESS_KEY}
# aws-session-token: ${AWS_SESSION_TOKEN}
default-model: anthropic.claude-sonnet-5-5
```

Chat is the **Converse** API (`POST /model/<id>/converse`, system, messages, `inferenceConfig`, `toolConfig`); embeddings are **InvokeModel** (`/model/<id>/invoke`: Titan `inputText`, one call per text; Cohere Embed
`texts`); the live list is `ListFoundationModels`. Settings: `region`, `profile`, the three `aws-*` keys as `${ENV}`, `base-url` (an endpoint override: a VPC endpoint, or a fake in a test), `control-url` (the
same for the model list), `embedding-input-type`, `timeout`, `retries` (SDK standard retries), `proxy.*`, `tls.*`. Every request is signed with SigV4 by the SDK (the tests check the `Authorization` header's
`AWS4-HMAC-SHA256 Credential=<key>/<date>/<region>/bedrock/aws4_request`, the date and the session token). Not built: guardrails, `additional-model-request-fields`, the `document` and image content blocks,
and `tls.verify-hostname: false` (both AWS clients always check the name; the file is refused with that reason).

#### 11.8 Keys, environment variables and what `describe_config()` shows

```json
{
  "name": "fast",
  "type": "groq",
  "enabled": true,
  "baseUrl": "https://api.groq.com/openai/v1",
  "apiKey": "********",
  "timeoutSeconds": 60.0,
  "defaultModel": "llama-3.3-70b-versatile",
  "models": ["llama-3.3-70b-versatile", "llama-3.1-8b-instant", "..."],
  "settings": {"catalogue": "true", "header.Authorization": "********", "header.X-Team": "red",
               "proxy.password": "********", "proxy.url": "http://proxy.corp:3128", "proxy.username": "svc"},
  "tls": []
}
```

With the key variable unset, `health()` says `down` and what to set (captured): `{'status': 'down', 'detail': 'no api key: set GROQ_API_KEY (or api-key: ${ENV_NAME})'}`.

#### 11.9 TLS: the same block as the connectors

Every provider type (and the console's MCP client) reads the connectors' `tls:` block ([TLS.md](../connectors/TLS.md) is the reference; in a provider file `tls:` is flattened to `tls.*` as everywhere):
trust (`ca-file` as a PEM file or the PEM text, `truststore`, `trust-jvm-default`), a client identity for mutual TLS (`cert-file` + `key-file` + `key-password`, or `keystore`), `protocols`, `cipher-suites`,
`verify-hostname` (on by default), `insecure-trust-all` (refused unless `DRISHTI_ALLOW_INSECURE_TLS=true`). Passwords are `${ENV}` placeholders or `...-password-file`, never literals. A `tls.*` key against an
`http://` address is an error at load, never a silent downgrade. Through an HTTP proxy the same settings hold (CONNECT tunnel, TLS end to end). The expiry of every certificate given is read at start and shown in
`health()` and `describe_config()["tls"]` (Admin → AI) as the connectors show it.

```yaml
type: vllm
base-url: https://llm.bank.example:8443/v1
tls:
  ca-file: /etc/drishti/tls/bank-ca.pem
  cert-file: /etc/drishti/tls/drishti.pem        # mutual TLS
  key-file: /etc/drishti/tls/drishti.key
  key-password-file: /run/secrets/drishti-key-password
```

Differences by language and type, honestly: the **server** reads the full set (PEM, PKCS12, JKS, all keys). The **console** reads PEM and **PKCS12** (a JKS store is refused with the `keytool -importkeystore` command that
converts it), `protocols` as `TLSv1.2` / `TLSv1.3`, `cipher-suites` in OpenSSL names, and `trust-jvm-default` means the system's authorities. **Bedrock** takes the CA, the truststore and the client identity (both
SDKs accept a trust and key manager or a bundle) but not `verify-hostname: false` or `protocols`. Hostname verification uses the address you connect to, so a private-CA certificate needs it as a SAN.

#### 11.10 Tests

No test touches a network beyond loopback.

* Per provider, in both languages, against a fake server that speaks that API (`drishti-ai/src/test/java/com/ash/drishti/ai/FakeLlmServer.java`, `drishti-console/tests/llm_fakes.py`; the same rules: the last user text is
  echoed, `weather` asks for the tool, `boom` is 500, `limit` is 429, `slow` sleeps): the request (path, auth header, body fields), a reply with usage and cost, a tool call, embeddings, the model list, 401, 429,
  500 (the body never appears in the message), a timeout, retries, health, masking, the proxy, Azure's three auth modes and both address styles, Bedrock's SigV4 header and session token. `HttpProvidersTest` and
  `test_llm_providers.py`.
* The **shared contract** `llm_contract_http.json` (catalogue, chat, tool call, system turn, 500, 429, stream, embeddings, refusals) runs against an OpenAI-compatible preset, Ollama, Gemini, Cohere and Bedrock in
  `HttpProvidersContractTest` and `test_llm_providers.py`; the older `llm_contract.json` still runs against the mock.
* **TLS** with a test CA (`TestPki` in Java, `cryptography` in Python): the private CA is trusted only when configured, mutual TLS is refused without the client certificate and accepted with it (PEM, encrypted
  key, PKCS12, JKS), the expiry note, the plain-address error, a CONNECT proxy tunnel, every provider type and Bedrock: `ProviderTlsTest`, `test_ai_tls.py`.
* The catalogue file is the same in `drishti-ai/src/main/resources/ai/` and `drishti-console/core/ai/providers/`; both languages' tests fail if the copies differ.

## 2. The MCP client: other servers' tools, behind Drishti's policy

Drishti can act as a client of **MCP (Model Context Protocol)** servers: list their tools, resources and prompts, call a tool, read a
resource. Like the LLM layer ([section 1](#1-model-providers)) it is interface-driven, configured by one file per server,
**off by default** (`drishti.mcp.enabled=false`), and exists on both layers: the server (`drishti-ai`, package `com.ash.drishti.ai`)
and the console (`drishti-console/core/ai/mcp.py`).

### 1. Interfaces

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

### 2. Server files

One YAML per server in `drishti.mcp.dir` (`DRISHTI_MCP_DIR`, default `./config/mcp`), reloaded live:

```yaml
transport: streamable-http
url: https://tools.example/mcp
timeout: 30s
headers:
  Authorization: Bearer ${TOOLS_TOKEN}     # ${ENV} placeholders only; a credential header without one is refused
allowed-tools: [calc_percentage_change]    # empty = all the server lists; others can be neither listed nor called
tls: {ca-file: /etc/drishti/ca.pem}        # the shared tls.* keys
proxy: {url: http://proxy.corp.example:3128, username: svc-drishti, password: ${PROXY_PASSWORD}}   # optional; `proxy: none` goes direct
```

#### Outbound proxy

A Streamable HTTP server is reached through the file's `proxy:` block, the site default (`drishti.http.proxy.*`; in the console the `DRISHTI_HTTP_PROXY_*` environment
variables) or directly, exactly as an AI provider and a connector are: `http://` proxy, https servers tunnelled with `CONNECT` and keeping `tls.*`, the password an `${ENV}` placeholder
(a literal refuses the file), a wrong login reported without the password. The initialize handshake, `tools/list`, calls and the closing DELETE all use it. Reference:
[TLS.md (proxy)](../connectors/TLS.md#proxy-9-the-console-ai-and-mcp-clients). The `mock` transport has no network and ignores it.

A server is **connected on first use**. Two things can go wrong, and both are retried by themselves (`drishti.retry.mcp.*`, defaults: 5 s,
doubling, up to 5 min, jitter; the console has `retry.mcp.*`):

- the file is fine but the server cannot be set up (its transport is not installed, a header's `${ENV}` is not set): Admin -> AI lists it **ERROR**
  with *failed - next retry in Ns (attempt k)* and builds it again on the schedule;
- the connection was lost (the last call failed, so the server shows **DOWN**): it is listed the same way, and when its retry is due the
  client is closed and connected afresh (a new handshake). When that works the server is **UP** again and the line disappears; open
  tool lists simply work on the next call. Calls made while it is down are still attempted (they are not held back).

**Retry now** (Admin -> AI, `POST /api/v1/admin/retry/mcp`, admin, audited as `mcp-retry-now`) reconnects at once. Failures are logged and
audited on attempts 1, 2, 4, 8, ... (`mcp-retry-failed`) and recoveries always (`mcp-recovered`). The console's MCP client does the same with `retry.mcp.*`.

### 3. Policy (`drishti.mcp.*`)

| Setting | Meaning |
|---|---|
| `server-roles.[server]` | Roles allowed to use a server (absent = every signed-in user). |
| `tool-roles.[server/tool]` | Roles allowed to see and call one tool. |
| `server-features.[server]` | Features allowed to use a server. |
| `audit` (true) | Access-log row per call, action `mcp`: user, feature, `server/tool`, sizes, outcome, ms. Never arguments or results. |
| masking | Arguments named in `drishti.security.redact` are replaced by `•••`; string arguments and every result pass through the same masker (`drishti.ai.policy.mask-patterns`). |
| timeouts | the file's `timeout`. |

REST: `GET /api/v1/admin/mcp/{server}/tools` (admin), `POST /api/v1/ai/mcp/{server}/tools/{tool}` `{arguments, feature}` (signed in; token
scope `ai:use`). Errors as the LLM layer (DRS-4015..4014). Admin -> AI lists the servers and, by link, their tools.

### 4. The mock server

`transport: mock`: scripted tools (`mock.tools.N.{name, description, reply, error}`; `${arg}` in `reply` is replaced), resources
(`uri`, `text`) and prompts, no network. `config/mcp/mock.yaml.example`.

### 5. Connecting to SAJHA (the user's MCP server)

SAJHA (`sajhamcpserver`) serves one `/mcp` endpoint (Streamable HTTP, also SSE, WebSocket and stdio) for both spec eras, chosen per
request; Drishti speaks the session-based 2025-11-25 one. Default `http://localhost:3002/mcp`. Anonymous callers see **no tools**
by default, so Drishti needs a credential: an `X-API-Key` (Admin -> API keys in SAJHA) or `Authorization: Bearer <JWT>`. Copy
`config/mcp/sajha.yaml.example` to `sajha.yaml` in the same folder, export `SAJHA_API_KEY`, set `drishti.mcp.enabled=true`.

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
copy), seed SAJHA's own config/apikeys.json from its config/apikeys.json.example (files of the SAJHA project, not of this repository), then
`SERVER_HOST=127.0.0.1 SERVER_PORT=18991 python run_sajha_web.py`, run the test above, and stop the process by its PID.

### 6. Tests

`AiProxyTest` and `tests/test_ai_proxy.py` (the MCP client through a fake proxy, plain and `CONNECT` to a TLS server with its own CA), `McpLayerTest` (mock under policy: roles, masking, `allowed-tools`, credential headers; the Streamable HTTP client against a loopback
stub: handshake, session id, JSON and event-stream answers, DELETE on close), `AiApiTest` (REST), `tests/test_ai_layer.py` (console).

### 7. The tool base class

A tool (something an MCP server serves, or a locally defined one) is written against `BaseMCPTool` (Python, `core/ai/mcp_tool.py`) or `BaseMcpTool` (Java, `com.ash.drishti.ai.llm`), whose abstract methods and signatures
are those of sajhamcpserver's `sajha/tools/base_mcp_tool.py`: `execute(arguments)`, `get_input_schema()` and `get_output_schema()` (Java: `execute`, `getInputSchema`, `getOutputSchema`). A tool is configured by a map
(`name`, `description`, `version`, `enabled`), or `load_from_config(path)`; `validate_arguments` checks the required and (with `additionalProperties: false`) the known names; `execute_with_tracking` validates, runs
and counts (`get_metrics`: calls, errors, last call, last error); `to_mcp_format()` is the `tools/list` entry. The client side (calling a remote server's tools, section 1) is unchanged. The mapping table of the whole
layer is in [section 1](#1-model-providers), its section 10.

## 3. AI management (design, not built)

Status: **design, not built.** Not in a release yet: planned for 2.9.0 until 2026-10-25, then parked with the AI wave (2.9.0 to 2.9.5 shipped without it; the plan files AI and GenUI under 2.12.0; see
[BI_DESIGN.md §12.2](../architecture/BI_DESIGN.md#122-the-three-waves)). Asked by the product owner on 2026-10-18: "for AI in general we
will need an elaborate UI to manage". Names marked **(new)** do not exist today.

### 1. Where we are

The AI layer is complete underneath ([section 1](#1-model-providers), [section 2](#2-the-mcp-client-other-servers-tools-behind-drishtis-policy)): providers
and models from `config/ai/*.yaml` (21 types: OpenAI, Anthropic, Azure OpenAI, Gemini, Mistral, Cohere, Bedrock, Ollama,
the OpenAI-compatible presets and the mock), a policy (`drishti.ai.policy.*`: masks, limits, cost per user and day, roles
per feature, audit), MCP servers and their tools, and features that use them (`ask`, the seven BI features, the API).
But managing it is files and environment variables: **Admin → AI** is a read-only page (on or off, providers with
health, MCP servers, a *Test connection* button), and the policy is configuration only ("Admin does not edit these").
Wave I (2.9.0) adds more that needs managing: AI grounding and golden questions, AI-drafted measures, reports and Transforms
under review, the Sajha MCP connector, and GenUI ([CLIENT_RENDERER.md](../architecture/CLIENT_RENDERER.md#21-genui-screens-composed-for-an-ai-agent-design-not-built)).

### 2. Principles

- **Everything in the UI, files stay the truth.** Each change made in the console is written to an overlay file under
  `<data dir>/ai/` (new; as `packs/added.yaml` is for packs), audited, and read without a restart. Shipped files and
  environment variables stay valid; the page shows where each value comes from (file, overlay, default) and a value from
  the environment cannot be overridden in the UI.
- **No secret is typed into the console.** A key is named by its environment variable (`${OPENAI_API_KEY}`) or a secret
  reference; the page shows whether it is set and works, never its value.
- **See what is sent.** Every place that changes what reaches a model offers a preview of the masked prompt for a chosen
  role.
- **Least surprise.** Policy changes that widen access (a feature opened to a new role, a limit raised, values mode moved
  from names to aggregates) can require a second administrator's approval (`drishti.ai.manage.four-eyes`, new, default on
  in production profiles).

### 3. Admin → AI, rebuilt (administrators; `ai:admin`)

| Tab | What it shows | What it changes |
|---|---|---|
| **Overview** | on or off; calls a minute and errors (an activity chart like Admin → Health's), cost today against budgets, providers up or down, the next retries, open reviews | the master switch (`drishti.ai.enabled`, when not set by the environment) |
| **Providers and models** | each provider: type, base URL, proxy, TLS, health, latency, the key's state (set, working, last used); its models with context size, prices, capabilities (chat, tools, JSON, embeddings) | add a provider from a form generated from its type's settings (the same `SettingSpec` style as Admin → Connectors), edit, disable, **Test connection**; set prices; a fallback chain per model (try B when A is down) |
| **Features** | every feature (`ask`, `bi-draft`, `bi-ask`, `bi-explain`, `bi-suggest`, `bi-explain-checks`, `bi-helper`, `subscription-narrative`, GenUI's `genui`, `api:*`) with its model, roles, limits, values mode, calls and cost | the model per feature, roles, per-minute and per-day limits, the cost cap, the values mode (`names` or `aggregates`), on or off per feature |
| **Data protection** | the masked fields (`drishti.security.redact` plus `ai.redact`), the extra patterns, a **masked-prompt preview** for any page or dataset and role | extra mask patterns; test a pattern against sample text; which features may send aggregates |
| **MCP servers and tools** | servers (stdio and HTTP), health, their tools with descriptions and schemas, calls a minute | add, edit, test a server; allow or deny each tool, per role; the Sajha MCP connector's settings |
| **Agents and GenUI** | the agents and MCP clients that call Drishti's own tools (`compose_ui` and the BI tools), what they did | which agents may call which tools; GenUI's policy is edited on Admin → GenUI and linked from here |
| **Usage and cost** | calls, tokens and cost by user, feature, model and day; the top users; trends; refusals by code (`DRS-4017`, `DRS-4018`) | budgets per user, role or feature, and alerts when a budget nears its end (to the Alerts mailbox) |
| **Quality** | golden questions per provider and model: pass rate, answers that changed since the last run, the measures each answer used | add or edit a golden question (question, expected measures or answer), run them on demand or on a schedule, compare two models side by side |
| **Audit** | one row per call (user, feature, model, sizes, tokens, cost, outcome, time), searchable and exportable; never the prompt or the answer by default | an opt-in to keep masked prompts and answers for N days (`drishti.ai.audit.keep-text`, new, planned, off), with its own retention and a banner on every AI surface while it is on |
| **Playground** | a chat through the policy **as a chosen role**: the masked prompt actually sent, the model's answer, tokens, cost, latency | nothing kept (audited as `ai-test`); used to try a model or a prompt before a feature uses it |

### 4. Review → AI drafts (authors and approvers)

One queue (new) for everything AI writes that becomes part of Drishti: measures from words, reports from intent,
AI-drafted Transforms, GenUI promotions, help text. Each draft shows the request, the model, the masked prompt (when kept),
the draft as YAML and as a preview, a diff against what it replaces, its checks (validator, tests, golden questions); the
actions are edit, approve (four eyes as for Sutras), reject with a reason, and *ask again* with a note. Approved drafts go
through the existing Build workbench propose → approve flow; nothing an AI writes is published without it.

### 5. For everyone: My AI (new, in the user menu)

What the person may use (features, models, limits left today), their recent AI calls (feature, time, outcome; their own
prompts only if text is kept), and their drafts waiting for review.

### 6. APIs (new; `ai:admin` unless noted)

`GET/PUT /api/v1/admin/ai/providers[/{name}]`, `POST /api/v1/admin/ai/providers/{name}/test`,
`GET/PUT /api/v1/admin/ai/features[/{feature}]`, `GET/PUT /api/v1/admin/ai/protection`, `POST /api/v1/admin/ai/preview`
(masked prompt for a role), `GET/PUT /api/v1/admin/mcp/servers[/{name}]`, `PUT /api/v1/admin/mcp/servers/{name}/tools`,
`GET /api/v1/admin/ai/usage`, `GET/PUT /api/v1/admin/ai/budgets`, `GET/POST /api/v1/admin/ai/golden[/{id}/run]`,
`GET /api/v1/admin/ai/audit`, `POST /api/v1/admin/ai/playground`; `GET /api/v1/ai/drafts` and
`POST /api/v1/ai/drafts/{id}/approve|reject|retry` (authors, approvers); `GET /api/v1/me/ai` (signed in). Every write is
audited, versioned in the overlay and refused when the value comes from the environment.

### 7. Build plan (2.9.0, console lane with its server endpoints)

| Step | Scope | Accepted when |
|---|---|---|
| M1 | Overlay store under `<data dir>/ai/`, reload without restart, source of every value, four-eyes for widening changes | a provider added in the UI answers a Test within 5 s with no restart; a widening change waits for a second administrator |
| M2 | Overview, Providers and models (forms from settings, fallback chains), Features | an administrator adds a provider, points `bi-ask` at it, limits it to one role, all without editing a file |
| M3 | Data protection with the masked-prompt preview; MCP servers and tools with per-role tool rules | the preview for a role without `raw` shows every redacted field masked; a denied tool is refused (`DRS-4018`) |
| M4 | Usage and cost, budgets and alerts; Audit (opt-in text retention) | a budget at 90 % sends an alert; the audit exports a day's calls as CSV |
| M5 | Quality: golden questions per model, scheduled runs, side-by-side | a model change that breaks a golden question is shown before it is made the default |
| M6 | Playground; Review → AI drafts; My AI | an AI-drafted measure is reviewed, edited and approved in the queue and appears in the model |
| M7 | Docs and tutorial (UI, CLI, YAML), browser tests for every tab | a new administrator sets up AI from the tutorial alone |

### 8. Tests

Browser tests per tab (Chromium, Firefox, WebKit, in the style of the console's `*_browser.py`), all against the mock
provider (`type: mock`) so no network is used; server tests for the overlay (source precedence, environment wins,
audit rows, four-eyes), the preview's masking against the policy's own masking (one implementation, one answer), and the
budget and alert path.

## 4. Server-side pricing: the quant service (proposed)

**Status: proposed; not in a planned release, a later candidate in [IMPLEMENTATION_PLAN.md › Roadmap](../architecture/IMPLEMENTATION_PLAN.md#roadmap); the decision is
[ADR-019](../architecture/adr/019-pricing-is-a-quantlib-service-behind-the-server.md).** Nothing here is built yet (checked again against 2.9.5, 2026-10-28: there is no `drishti-quant-gateway` module, no `drishti.quant.*` server setting and no `quant` power; `drishti.quant` below is the small browser library of Calc).

**2.8.0 update (2026-10-24): read [PYTHON_COMPUTE.md](../architecture/PYTHON_COMPUTE.md) first.** The product owner decided that computations,
pricing included, run in **compute workers supervised by the Drishti server** (out-of-process Python workers with QLRisk as the
default pricer, and an isolated Java worker for QuantLib's own Java binding), used by **reactive connectors**
([DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#4-reactive-connectors)); the first user is the VaR engine ([VAR_GUIDE.md Part III](../guides/VAR_GUIDE.md#24-where-the-engine-lives)). That replaces the
*deployment shape* proposed below (a separate `drishti-quant` HTTP service); the rest of this part still stands and is reused:
pricing stays outside the server's JVM, the `quant` power, the packs' pricer mappings (section 6), market data assembled by the server
for a business date (section 7), caching by trade version and date (section 11). The QuantLib-SWIG Java option rejected in section 3
*inside the server* is now accepted *in an isolated Java worker*. See [section 17](#17-reconciled-with-the-280-compute-workers).

**Relation to Rūpaka (BI).** This is the server-side pricing service for the terminal and Calc. [RUPAKA.md](../architecture/RUPAKA.md) section 9.2 plans a different, console-side `QuantEngine` for the analyst workbench of BI phase 9, with QLRisk as its first provider, and keeps it a design until QLRisk's API is declared stable. If both are built they should share the pricer mappings of section 6 rather than keep two. Neither is in the BI pages of 1.20 ([BI_GUIDE.md](../guides/BI_GUIDE.md) section 11 lists what BI does not have yet).

Drishti shows trades, market data and risk, and Calc ([USER_GUIDE.md](../guides/USER_GUIDE.md#calc-python-on-any-view)) lets a user compute
on them in the browser with numpy, pandas and the small `drishti.quant` library. What Calc cannot do well is
*proper* pricing: building curves from instruments, pricing swaptions or callable bonds, calibrating models. That
needs QuantLib, and QuantLib belongs on a server, not in a browser. This document describes how a pricing service
works with Drishti, how it reaches the screen, and how it would be built.

### 1. What it is for

| Question | Today | With the service |
|---|---|---|
| What is this swap worth on the curve of that day? | the booked MTM from the source system | an independent model NPV next to the booked MTM, and the difference |
| What are its Greeks by tenor? | the booked DV01, sometimes by tenor | key-rate DV01, vega, gamma, computed consistently |
| What if rates rise 25 bp and vols rise 2 points? | a first-order estimate in Calc (DV01 × shift) | a full reprice under the scenario |
| What is the implied vol of this option, the par rate of this swap? | approximations in `drishti.quant` | QuantLib's calibrated answer |
| What is a book worth under a stress? | not available | an overnight or on-demand batch through the same service |

It is a **valuation and analysis** service for the people using Drishti, not a replacement for the bank's pricing
or risk systems of record: its numbers sit beside the booked ones, clearly labelled as Drishti's model values.

### 2. The shape

```
Browser (Calc, a Valuation panel, the what-if drawer)
   │  the user's session: same permissions, same redaction
   ▼
Console ──▶ Drishti server  /api/v1/quant/...
               │  checks the `quant` power and that the user may open the entity
               │  resolves market data for the business date from its own sources
               │  caps the size and time of a request, records it in the access log
               │  caches results by (trade version, market date, model, scenario)
               ▼
          drishti-quant (internal only, never reached by browsers)
               Python, QuantLib, a small HTTP API; stateless; one or many replicas
               maps the trade document to a QuantLib instrument through the pack's pricer mapping,
               builds the curves and surfaces it was given, prices, returns NPV, Greeks, cashflows
```

Every request a user makes goes through the Drishti server. The service never fetches data itself and never sees a
user's credentials; it is given a trade, market data and a model, and it returns numbers.

### 3. Why a separate service

| Option | For | Against | Verdict |
|---|---|---|---|
| **A separate Python service with QuantLib-Python** | QuantLib publishes ready-made wheels for Linux, macOS and Windows (QuantLib 1.43 today); a crash in native code cannot take the Drishti server down; it scales on its own; the same interface can front ORE or a bank's pricers later | one more process to run | **chosen** ([ADR-019](../architecture/adr/019-pricing-is-a-quantlib-service-behind-the-server.md)) |
| QuantLib's Java (SWIG) bindings inside the server | one process | native libraries to build for every platform, and a crash or a long calibration inside the server everyone uses | rejected |
| QuantLib compiled to WebAssembly, in Calc | no server | no build exists; tens of MB more to download; a laptop's CPU for calibrations; rebuilt for every Pyodide version | rejected ([USER_GUIDE.md](../guides/USER_GUIDE.md#calc-python-on-any-view)) |
| ORE (Open Source Risk Engine) as the service | portfolio analytics (XVA, simulation) | larger to adopt, XML-configured | later, behind the same API |

### 4. What the Drishti server does

A new module, `drishti-quant-gateway`, in the server:

- **The endpoint** `/api/v1/quant/...` ([section 9](#9-the-api)).
- **Permission:** a new role power, `quant` (like `calc` and `layout`), granted by default to trader, risk, author
  and admin roles, not to viewers; and, as for every read, the user must be allowed to open the entity. Redaction
  applies: a field the role sees masked is not sent to the service, and a price that would need it is refused with
  the reason.
- **The trade:** read through the source router as for a view, so the same document the screen shows is priced.
- **Market data** for the business date ([section 7](#7-market-data-for-a-business-date)).
- **The pack's pricer mapping** for the product ([section 6](#6-packs-declare-how-products-are-priced)).
- **Limits:** at most `drishti.quant.max-concurrent` requests in flight (8; planned), each with a timeout
  (`drishti.quant.timeout`, planned, 10 s; 120 s for batches), a cap on scenario and grid sizes, and a per-user rate.
- **Caching** ([section 11](#11-caching-and-performance)).
- **Health:** the service shows in Admin → Health like a connector (`UP`, `DOWN: …`), with its version and
  QuantLib's.
- **Audit:** each pricing is recorded in the access log (who, what entity, which date, which scenario).

### 5. What the quant service does

A small Python application (`services/drishti-quant/`), built and shipped as its own image (`drishti-quant`):

- **HTTP API** (FastAPI): `POST /price`, `/scenario`, `/curve`, `/implied`, `/health`. Requests carry the trade
  document, the market data and the pricer spec; responses carry numbers and the model used. No state between
  requests, so replicas can be added freely.
- **Adapters**, one per product family, turning a trade document (through the pack's mapping) into a QuantLib
  instrument and engine: vanilla interest-rate swaps (fixed/float, OIS), FX forwards and FX options
  (Garman–Kohlhagen), fixed-rate bonds, equity options (Black–Scholes, and American by finite differences), then
  swaptions (Black, Bachelier), caps and floors, CDS (ISDA standard model).
- **Market builders:** discount and forward curves from the points Drishti sends (or bootstrapped from instruments
  when the pack provides quotes), vol surfaces and smiles, fixings for floating legs.
- **Results:** NPV, the booked comparison is left to Drishti; Greeks (DV01, key-rate DV01 by bucket, delta, gamma,
  vega, theta); projected cashflows; the model, engine and market date used, so every number explains itself.
- **Only the service talks QuantLib.** If the bank already has a pricing library or service, an adapter in this
  service can call it instead, and Drishti does not change.

### 6. Packs declare how products are priced

A pack says, per product, which adapter prices it and where its inputs come from in the trade and the market, in
`pack.yaml` (validated at load; mistakes are problem codes like any pack error):

```yaml
quant:
  enabled: true
  pricers:
    IRS_FIXFLOAT:
      adapter: vanilla-swap
      notional: notional
      legs: legs                          # each leg: payReceive, fixedRate or index, spread, frequency, dayCount
      discount: { curve: "OIS-${currency}" }       # an ir-curve entity id, from the trade's currency
      forward:  { curve: "${legs[1].index}" }      # the floating leg's index curve
    FX_OPTION:
      adapter: fx-option
      pair: currencyPair
      strike: strike
      expiry: expiryDate
      vol: { surface: "FXV-${currencyPair}" }
      domestic: { curve: "OIS-${quoteCurrency}" }
      foreign:  { curve: "OIS-${baseCurrency}" }
  scenarios:                                 # named shifts offered in the what-if drawer
    rates-up-25:   { curves: { "*": { parallel: 25bp } } }
    steepener:     { curves: { "*": { twist: { pivot: 5Y, short: -10bp, long: 15bp } } } }
    vols-up-2:     { surfaces: { "*": { parallel: 0.02 } } }
```

Adding a product is configuration plus, if no adapter fits, a small adapter in the service. No server code changes.

### 7. Market data for a business date

The server assembles market data from its own sources, as of the date the user is looking at, using the same
routing, history and redaction as views: the curves, surfaces and fixings the pricer spec names are read like any
entity (`ir-curve`, `fx-vol-surface`, `rate-fixing` …), for the business date, and sent with the trade. A price for
2026-09-29 therefore uses 2026-09-29's market, the same market the screens show for that date, and the result records
which entities and dates it used (provenance, as on every view). If an input is missing for the date, the request is
refused with the name of the missing entity rather than priced on another day's data.

### 8. How it reaches the screen

| Where | What the user does | What they see |
|---|---|---|
| **Calc** | `await drishti.pricer.price_async("trade", "MX-20000001")`, `scenario_async(…, shift={"USD-OIS": +25})`, `implied_vol_async(…)`, `curve_async(…)` | DataFrames and charts of NPV, Greeks, cashflows; numpy and pandas stay in the browser for analysis |
| **A Valuation panel** | opens a trade; a Sutra binds a panel to the pricer (a `quant:` source on a `kv` or `table` panel) | model NPV beside booked MTM, the difference, Greeks by tenor, model and market date; computed when the view opens, then cached |
| **The what-if drawer** | a key the Sutra assigns (for example F6) on a trade or netting set | sliders and named scenarios from the pack (rates +25 bp, steepener, vols +2); the entity repriced live, shown as a before/after waterfall |
| **Overnight batch** (later) | an operator schedules a book's repricing | results loaded into the lake as a dated kind (`trade-valuation`), so they are searchable, pivotable and comparable over days like anything else |

### 9. The API

Server (what the console and Calc call; JSON):

| Method and path | Body / parameters | Returns |
|---|---|---|
| `POST /api/v1/quant/price/{kind}/{id}` | `asOf`, optional `model`, `measures` (`npv`, `greeks`, `cashflows`) | NPV, Greeks, cashflows, model, market inputs used |
| `POST /api/v1/quant/scenario/{kind}/{id}` | `asOf`, a named scenario or explicit shifts | base and shocked values, and the difference |
| `POST /api/v1/quant/implied/{kind}/{id}` | `asOf`, target price | implied vol or spread |
| `POST /api/v1/quant/curve/{kind}/{id}` | `asOf`, interpolation | zero, discount and forward curves on a grid |
| `GET /api/v1/quant/status` | — | enabled, service health, versions, the user's `quant` power |

Service (internal; the server is its only client): the same operations, with the trade, market data and pricer spec
in the body instead of entity ids.

### 10. Security

- **Only the server reaches the service**: it listens on the internal network, and every request carries a token the
  server signs with `drishti.quant.secret` (planned); anything else is refused.
- **The service holds no credentials and reads no data**: it is given exactly the inputs to price.
- **Permissions are Drishti's**: the `quant` power, the right to open the entity, and redaction, all checked in the
  server before anything is sent.
- **Bounded work**: request and grid sizes, time and concurrency are capped in the server; the service also enforces
  its own timeouts, so a runaway calibration ends rather than holding a worker.

### 11. Caching and performance

- Results are cached in the server by (entity, document generation, business date, model, measures, scenario): a
  trade reopened costs nothing; a new version of the trade or a new market day reprices.
- Typical costs (to be measured in phase 1): a vanilla swap with curves given, tens of milliseconds; with key-rate
  DV01 by bump and reprice, a few hundred; a swaption with a calibrated model, up to a second.
- Batches run on the service's replicas in parallel, bounded so they never starve interactive requests (a separate
  queue and a share of workers).

### 12. Failure and operations

- **Deployment**: one more container in `deploy/docker/compose.yaml` (`drishti-quant`), and the planned `drishti.quant.url`,
  `drishti.quant.secret`, `drishti.quant.enabled` in the server (planned settings). Without it, Calc's pricer calls and Valuation panels
  say "pricing is not configured" and everything else works as today.
- **Health**: Admin → Health shows the service; when it is down, Valuation panels show the reason in place (not an
  error page) and Calc raises a clear error.
- **Scaling**: add replicas behind the internal load balancer; the service is stateless.
- **Versions**: the service reports its QuantLib version with every result, so a number can always be reproduced.

### 13. Testing

- **The service**: each adapter against QuantLib's published examples and textbook values (the same discipline as
  `drishti.quant`'s tests), and against the sample trades with known inputs.
- **The server**: the gateway's permission, redaction, market-data assembly, caching and limits, with a fake service.
- **End to end**: a scratch server, console and service on the banking samples; a Valuation panel and Calc calls in a
  real browser; the what-if drawer's numbers against the service's.

### 14. Phases

| Phase | Delivers | Done when |
|---|---|---|
| **1. The service** | `drishti-quant` with adapters for vanilla swaps, FX forwards and options, fixed-rate bonds and equity options; curves from the points given; its image | adapters match reference values; the image runs on its own |
| **2. The gateway** | the server endpoints, the `quant` power, market-data assembly for a business date, pack `quant:` mappings, caching, limits, Health, audit | a banking trade prices through the server with the date's market, refused cleanly when a role or an input is missing |
| **3. In the UX** | `drishti.pricer` in Calc; a Valuation panel on banking trades through their Sutras; the pack's mappings for the banking products | a trader opens a swap and sees model NPV beside booked MTM, and reprices it from Calc |
| **4. What-if** | the drawer with named scenarios and sliders, before/after waterfall | shifts reprice live on a trade and a netting set |
| **5. More products and batches** | swaptions, caps and floors, CDS; overnight repricing of a book into the lake | a book's valuations are searchable and comparable over days |

Each phase is tested, documented (this part becomes a guide, with a deep `docs/guides/PRICING.md` for users, planned),
and released on its own.

### 15. Risks and open questions

| Risk or question | Mitigation or decision needed |
|---|---|
| Model values differ from booked values and confuse users | always shown side by side, labelled as Drishti's model, with the model and market used |
| The sample market data is synthetic and not always consistent with booked trades | adapters are tested on reference cases; sample-data differences are expected and explained in the panel |
| Market inputs missing for a date | refused with the missing entity named; never silently priced on another day |
| Exotic products | out of scope until asked for; the adapter interface lets a bank plug in its own pricer |
| Load from many users repricing at once | caching, the concurrency cap and separate batch capacity |
| Which products first | to confirm with the desks: the proposal is phase 1's list |
| Batch results in the lake | to decide in phase 5: a new kind per day, written by the service's batch through the existing loaders |
| Licensing | QuantLib is BSD-licensed, ORE uses a modified BSD licence; both suit a proprietary product, with notices in `THIRD-PARTY-NOTICES.md` |

### 16. The console's QuantEngine slot (built, lean)

This section is what is **built** of RUPAKA 9.2's `QuantEngine` for Analyse, and it is deliberately small: `price` only (no sensitivities, scenarios, VaR or XVA yet), no pack adapters, no Arrow in or out, a stand-in in place of QLRisk. A Python cell in [Analyse](../guides/USER_GUIDE.md#analyse-slice-sql-and-python) can price things with `quant.price(instrument, **parameters)`. The console does not contain a pricing library of its own: it has a **slot**, the
`QuantEngine` interface, and a small **stand-in** that fills it so notebooks, demos and tests run anywhere. A real library plugs into the same slot.

#### 16.1 The interface

`drishti-console/core/quant/engine.py`:

```python
class QuantEngine(ABC):
    name = "engine"
    def instruments(self) -> dict[str, list[str]]: ...      # {"npv": ["rate", "cashflows"]}
    def price(self, instrument: str, **params) -> float: ... # raises QuantError for a call it cannot make
    def describe(self) -> dict: ...                          # {"engine", "instruments"}
```

An engine is a **pure function of its inputs**: no network, no files, no state. It is imported by the Python worker *before* the sandbox closes and runs inside it, so it cannot reach out either.
A `QuantError` reaches the cell as `ValueError: quant: <message>`.

#### 16.2 The stand-in

`core/quant/standin.py` (`math` only), engine name `stand-in`. Rates, yields and volatilities are decimals (`0.05`), times are in years.

| Instrument | Parameters | Returns |
|---|---|---|
| `black_scholes` | `spot`, `strike`, `rate`, `vol`, `years`, `kind` (`call` or `put`, default `call`), `div` (default `0`) | European option price |
| `bond` | `coupon`, `yield_`, `years`, `face` (default `100`), `freq` (default `2`) | price of a fixed-coupon bond; a bond whose coupon equals its yield prices at `face` |
| `npv` | `rate`, `cashflows` (a list; the first at time 0) | net present value |

```python
quant.price("black_scholes", spot=100, strike=100, rate=0.05, vol=0.2, years=1)        # 10.4506
quant.price("bond", coupon=0.05, yield_=0.05, years=10)                                  # 100.0
quant.price("npv", rate=0.1, cashflows=[-100, 110])                                      # 0.0
quant.instruments()                                                                      # what the engine prices
```

It is for teaching and tests, **not a risk system**: no curves, calendars, day counts, Greeks or calibration. Its tests (`test_the_quant_stand_in_prices`) check the Black-Scholes values,
put-call parity, a par bond and an NPV of zero.

#### 16.3 Setting

`bi.explore.quant_engine` (console `application.yaml`), default `stand-in`. A name no installed engine answers to is `ValueError: no quant engine named ...` in the cell, and the Analyse page
shows the engine in use.

#### 16.4 How QLRisk plugs in (design only)

QLRisk (the user's quant library, `~/PycharmProjects/qlrisk`) is **under construction; the console neither imports nor modifies it**, and nothing here is wired to it. The slot is
shaped so that, when its API is stable, the work is small and local:

1. Write an adapter, `core/quant/qlrisk.py`, a `QuantEngine` subclass whose `instruments()` lists what QLRisk prices and whose `price()` translates keyword parameters into QLRisk calls
   and returns a float (raising `QuantError` for a bad call). The adapter is the only file that imports QLRisk.
2. Register it in `core/quant/__init__.py: get_engine` under the name `qlrisk`.
3. Make QLRisk importable by the worker: the worker's import guard allows only the packages a cell lists (on `bi.python.packages`), but the engine is loaded by the worker *before* its sandbox,
   from the console's own environment, so QLRisk must be installed there (and its data files readable at load time: the sandbox forbids reads after it closes).
4. Set `bi.explore.quant_engine: qlrisk`.

Open questions for that day: whether QLRisk wants curves and market data passed in (a cell would load them from the extract and pass them as parameters) or fetches them itself (it
cannot, in the sandbox: no network), whether it is fork- and memory-friendly under the worker's address-space limit, and how results with structure (a Greeks table) come back (the stand-in returns
one float; `price` could return a dict, and the cell's `result` already draws tables and charts). The design for a separate quant *service* the console calls over the network is
[RUPAKA.md](../architecture/RUPAKA.md) section 9.2 and sections 2 to 5 above; this slot is the in-process stand-in for it.

### 17. Reconciled with the 2.8.0 compute workers

Decided 2026-10-24 (product owner); the design is [PYTHON_COMPUTE.md](../architecture/PYTHON_COMPUTE.md).

| This document proposed | 2.8.0 decides |
|---|---|
| a separate Python service, `drishti-quant`, reached over HTTP (sections 2, 5, 12) | **compute workers** started and supervised by the server: a pool of out-of-process Python workers, data passed as Arrow, from a locked, administrator-approved environment ([PYTHON_COMPUTE.md sections 4 to 7](../architecture/PYTHON_COMPUTE.md#4-requires-what-a-connector-needs-to-run)); a remote pool on other machines remains a later option and would look much like the service |
| QuantLib-Python adapters written in the service (section 5) | a **pricer SPI** with **QLRisk as the default provider** (design only until its API is declared stable) and a stand-in for tests ([PYTHON_COMPUTE.md section 9](../architecture/PYTHON_COMPUTE.md#9-pricing-one-spi-two-engines)) |
| QuantLib's Java (SWIG) binding rejected inside the server (section 3) | **accepted in an isolated Java worker**: built per OS (Linux first), packaged in a versioned jar, extracted under the data folder, never loaded in the server; its tests skip on Windows; a parity test against QLRisk |
| `/api/v1/quant/...` endpoints (section 9) | pricing reaches screens through **reactive connectors** and their kinds (a what-if is a computed kind, [VAR_GUIDE.md section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes-what-if-planned-intraday-updates-as-built-in-294)); dedicated endpoints for single-trade pricing may still be added on the same workers |
| packs' `quant: pricers:` mappings (section 6) | **kept**, shared by every pricer provider |
| market data for a business date assembled by the server (section 7) | **kept** |
| the `quant` power, limits, caching, audit (sections 4, 10, 11) | **kept**, joined by the `compute` power and the compute audit ([DATA_ARCHITECTURE.md › Reactive connectors, section 15](../architecture/DATA_ARCHITECTURE.md#15-observability-and-audit)) |

The console's `QuantEngine` slot (section 16) is unchanged: it serves Analyse cells; when QLRisk is stable its adapter and the compute
pricer SPI share the packs' pricer mappings.
