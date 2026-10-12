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
MCP servers (tools a model or a feature can call) have a sibling layer: [MCP_CLIENT.md](MCP_CLIENT.md). Beyond `openai`, `anthropic` and `mock`, sixteen more provider types (Groq, Together, Fireworks, DeepSeek, xAI, OpenRouter, Perplexity, Hugging Face, vLLM, LM Studio, Mistral, any OpenAI-compatible server, Azure OpenAI, Gemini, Cohere, Ollama, AWS Bedrock) come from sajhamcpserver: [section 11](#11-more-providers-the-way-sajhamcpserver-has-them).

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

### Outbound proxy

A site that reaches the model endpoint only through a forward proxy puts a `proxy:` block in the provider file (`url`, `username`, `password` as `${ENV}`, `no-proxy`, `enabled`), or
sets the site default once: `drishti.http.proxy.*` on the server, `DRISHTI_HTTP_PROXY_URL` and friends in the console. `proxy: none` in a file goes direct whatever the site says.
`http://` and `https://` endpoints both go through an `http://` proxy; an https endpoint is tunnelled with `CONNECT`, so `tls.*` (a private CA, a client certificate) still applies to the
endpoint itself. OpenAI-compatible and Anthropic providers use it on the server (the JDK HTTP client) and in the console (`httpx`). A literal `proxy.password` refuses the file without
repeating it; a wrong proxy login is reported as a failure of the call without the password. Everything is in [PROXY.md](../connectors/PROXY.md#9-the-console-ai-and-mcp-clients). Tested in
`AiProxyTest` (Java) and `tests/test_ai_proxy.py` (console) against a fake proxy on loopback. Admin -> AI does not display the proxy yet.

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

### BI uses (stage 2 of Rūpaka)

Three features use the layer through `LLMFactory.model(...)` ([section 10](#10-one-design-in-two-languages-the-sajhamcpserver-abstractions)): **`bi-draft`** (report from words), **`bi-ask`**
(ask the data) and **`bi-explain`** (the narrative of "explain this number", only when `drishti.bi.ai.values` is `aggregates`). Each is a feature name for the policy (section 4), so masks,
per-feature roles and limits, the cost limit and the audit row apply; each runs under the caller's identity and sends the dataset **model** (names, types, descriptions) and the person's words,
never a row. Point them at a model with `drishti.bi.ai.model`. Details, settings, errors (`DRS-4020`: an answer that cannot be used) and tests: [BI_AI.md](../guides/BI_AI.md).

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

`drishti-ai`: `AiProxyTest` (the proxy: credentials from the environment, the site default, `proxy: none`, no-proxy, a literal password refused), `LlmLayerTest` (mock, files, registry and reload, a stub OpenAI-compatible server incl. headers, cost and error
handling, a stub Anthropic server, policy), `McpLayerTest`. `drishti-server`: `AiApiTest` (REST end to end with the mock),
`AskEndToEndTest` and friends (Ask on the layer, incl. the legacy settings), `AskOffByDefaultTest`. Console:
`tests/test_ai_layer.py`. None touches a network beyond loopback. The providers of section 11 have their own tests ([11.10](#1110-tests)).

## 10. One design in two languages: the sajhamcpserver abstractions

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
| `BaseMCPTool` (`core/ai/mcp_tool.py`) | `BaseMcpTool` | the tool base class: `execute`, `get_input_schema`, `get_output_schema` (abstract), `validate_arguments`, `execute_with_tracking`, `get_metrics`, `to_mcp_format`, `load_from_config`, `enable` / `disable` ([MCP_CLIENT.md](MCP_CLIENT.md) section 7) |

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

## 11. More providers, the way sajhamcpserver has them

Sixteen provider types joined `openai`, `anthropic`, `server` and `mock`, mirroring `sajha/ai/llm/providers/*.py` of sajhamcpserver: the type names, the settings (sajha's `snake_case` fields are
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
* **Proxy.** The shared `proxy.*` block (or the site default) applies to every call, an `https://` target tunnelled with CONNECT: [PROXY.md](../connectors/PROXY.md).
* **TLS.** The shared `tls.*` block of the connectors applies to every provider type (section 11.9).
* **Errors** name the cause and never carry the response body: HTTP 401/403 `(authentication failed ...)`, 404 `(not found: check base-url, the path and the model id)`, 429 kind `RATE_LIMITED`, a slow
  endpoint kind `TIMEOUT`, anything else `FAILED`. `retries: n` tries again after a 5xx, a 429 or a connection failure.

### 11.1 OpenAI-compatible presets

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

### 11.2 Azure OpenAI

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

### 11.3 Gemini

```yaml
# config/ai/gemini.yaml
type: gemini
enabled: true
api-key: ${GEMINI_API_KEY}              # GEMINI_API_KEY or GOOGLE_API_KEY is also read when this line is left out
```

`POST {base-url}/v1beta/models/<id>:generateContent` with `x-goog-api-key`; embeddings by `:batchEmbedContents` (`embedding-task-type`, default `RETRIEVAL_DOCUMENT`). `schema-mode: json_schema`
sends tool schemas as `parametersJsonSchema`; `openapi` sends the OpenAPI subset as `parameters`. `api-version` (v1beta), `base-url` (also `GEMINI_BASE_URL`). **Vertex AI (`platform: vertex`) is not built**
and is refused at load.

### 11.4 Mistral

`type: mistral` is the OpenAI-compatible provider with Mistral's declarations (`max_tokens`, key `MISTRAL_API_KEY`, `mistral-embed`). Copy `config/ai/mistral.yaml.example`; the settings are those of 11.1.

### 11.5 Cohere

```yaml
type: cohere
enabled: true
api-key: ${COHERE_API_KEY}              # COHERE_API_KEY or CO_API_KEY is also read when this line is left out
```

`POST /v2/chat` and `/v2/embed` with a bearer key (`chat-path`, `embed-path`, `embedding-input-type` default `search_document`); the model list is `GET /v1/models`. The `document` tool-result format is not built.

### 11.6 Ollama

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

### 11.7 AWS Bedrock

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

### 11.8 Keys, environment variables and what `describe_config()` shows

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

### 11.9 TLS: the same block as the connectors

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

### 11.10 Tests

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
