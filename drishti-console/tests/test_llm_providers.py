# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

"""The LLM providers added from sajhamcpserver (OpenAI-compatible presets, Azure OpenAI, Gemini, Mistral, Cohere, Ollama, Bedrock) against fake servers on loopback.

Per provider: the request (path, authentication header, body fields), a reply (text, tool call, usage, cost), an error (401, 429, 500: the message names the cause and
never carries the body), a timeout, the model list, health, describe_config with every secret masked, embeddings where the API has them. The shared contract scenarios of
``llm_contract_http.json`` run against the OpenAI-compatible preset and Ollama (and Gemini, Cohere, Bedrock). No real network."""
import asyncio
import json
from pathlib import Path

import pytest

from core.ai.llm import ChatCompletionRequest, ChatMessage, EmbeddingsRequest, LLMError, ProviderBase
from core.ai.llm.registry import registered_providers
from core.ai.providers import catalog
from llm_fakes import KEY, FakeServer

CONTRACT = json.loads((Path(__file__).resolve().parents[2] / "drishti-ai/src/test/resources/contract/llm_contract_http.json").read_text(encoding="utf-8"))
ENV = {"THE_KEY": KEY, "AKID": "AKIDEXAMPLE", "ASECRET": "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "AWS_TOKEN": "tok-session"}
PRESETS = ["groq", "together", "fireworks", "deepseek", "xai", "openrouter", "perplexity", "huggingface", "vllm", "lmstudio", "mistral", "openai_compatible"]


def make(typ, url, name="p", **extra):
    cfg = {"type": typ, "base-url": url, "timeout": "5s", **extra}
    if typ not in ("ollama", "vllm", "lmstudio", "openai_compatible", "bedrock"):
        cfg.setdefault("api-key", "${THE_KEY}")
    if typ in ("openai_compatible", "vllm", "lmstudio"):
        cfg.setdefault("api-key", "${THE_KEY}")
    return ProviderBase.from_settings(name, cfg, env=ENV.get)


def ask(provider, text, model="", **fields):
    return provider.chat_model(model).chat_completions_create(messages=[{"role": "user", "content": text}], **fields)


@pytest.fixture()
def fake():
    made = []

    def f(api, **kw):
        s = FakeServer(api, **kw)
        made.append(s)
        return s
    yield f
    for s in made:
        s.close()


# ---- the registry, the catalogue, the presets ------------------------------------------------------------------------------------------------------------

def test_every_type_is_registered_and_the_catalogue_gives_each_preset_models_a_base_url_and_a_key_variable():
    want = set(PRESETS) | {"azure_openai", "gemini", "cohere", "ollama", "bedrock"}
    assert want <= set(registered_providers())
    for t in want - {"openai_compatible"}:
        p = ProviderBase.from_settings("x", {"type": t, **({"base-url": "https://x.openai.azure.com"} if t == "azure_openai" else {}),
                                             **({"deployments": {"gpt": "dep"}} if t == "azure_openai" else {})})
        pre = catalog.preset(t)
        assert pre and (p.describe_config()["baseUrl"] or t in ("azure_openai", "bedrock")), t
        if t not in ("vllm", "lmstudio", "azure_openai"):
            assert p.models(), t
        assert p.active is True


def test_the_default_base_urls_are_the_vendors_and_keys_come_from_the_vendors_variable():
    urls = {"groq": "https://api.groq.com/openai/v1", "together": "https://api.together.xyz/v1", "fireworks": "https://api.fireworks.ai/inference/v1",
            "deepseek": "https://api.deepseek.com/v1", "xai": "https://api.x.ai/v1", "openrouter": "https://openrouter.ai/api/v1",
            "perplexity": "https://api.perplexity.ai", "huggingface": "https://router.huggingface.co/v1", "vllm": "http://localhost:8000/v1",
            "lmstudio": "http://localhost:1234/v1", "mistral": "https://api.mistral.ai/v1", "gemini": "https://generativelanguage.googleapis.com",
            "cohere": "https://api.cohere.com", "ollama": "http://localhost:11434"}
    for t, u in urls.items():
        assert ProviderBase.from_settings("x", {"type": t}).describe_config()["baseUrl"] == u
    p = ProviderBase.from_settings("g", {"type": "groq"}, env={"GROQ_API_KEY": "from-env"}.get)
    assert p.describe_config()["apiKey"] == "********" and p.health().ok
    assert ProviderBase.from_settings("g", {"type": "groq"}, env={}.get).health().ok is False                   # a keyed preset with no key is down, said plainly
    assert "GROQ_API_KEY" in ProviderBase.from_settings("g", {"type": "groq"}, env={}.get).health().detail


def test_a_file_model_overrides_the_catalogue_and_catalogue_false_keeps_only_the_files():
    p = ProviderBase.from_settings("g", {"type": "groq", "models": [{"name": "llama-3.3-70b-versatile", "id": "custom", "cost-per-1k-input": 1}]})
    m = {x.id: x for x in p.models()}
    assert "llama-3.3-70b-versatile" in m and len(p.models()) == 4
    assert ProviderBase.from_settings("g", {"type": "groq", "catalogue": False, "models": [{"name": "only"}]}).models()[0].id == "only"
    assert p.chat_model("").model_id == "llama-3.3-70b-versatile"                                                  # the catalogue default


def test_literal_keys_are_refused_for_every_credential_setting():
    for k in ("api-key",):
        with pytest.raises(LLMError):
            ProviderBase.from_settings("x", {"type": "groq", k: "gsk_literal"})
    with pytest.raises(LLMError):
        ProviderBase.from_settings("x", {"type": "bedrock", "aws-secret-access-key": "literal"})
    with pytest.raises(LLMError):
        ProviderBase.from_settings("x", {"type": "azure_openai", "base-url": "https://a", "client-secret": "literal"})


# ---- OpenAI-compatible presets ---------------------------------------------------------------------------------------------------------------------------

@pytest.mark.parametrize("typ", PRESETS)
def test_openai_compatible_presets_send_the_chat_completions_request_with_a_bearer_key(fake, typ):
    s = fake("openai")
    p = make(typ, s.url, models=[{"name": "m", "id": "the-model", "cost-per-1k-input": 1.0, "cost-per-1k-output": 2.0}], **{"default-model": "m"})
    c = ask(p, "ping", system="")
    method, path, hdr, body = s.seen[0]
    j = json.loads(body)
    assert method == "POST" and path == "/chat/completions" and hdr["Authorization"] == "Bearer " + KEY
    assert j["model"] == "the-model" and j["messages"][-1] == {"role": "user", "content": "ping"} and j["max_tokens"] == 1024 and j["stream"] is False
    assert c.text == "echo: ping" and c.usage.total_tokens == 8 and c.drishti.cost_usd == pytest.approx(0.011) and c.drishti.provider == "p"


def test_a_tool_call_comes_back_and_the_tool_schema_goes_out(fake):
    s = fake("openai")
    p = make("groq", s.url, models=[{"name": "m"}])
    req = ChatCompletionRequest(model="m", messages=[ChatMessage.user("weather in Oslo")],
                                tools=[{"type": "function", "function": {"name": "get_weather", "description": "d", "parameters": {"type": "object"}}}])
    c = p.chat_model("m").chat_completions_create(req)
    assert [(t.function.name, t.function.arguments) for t in c.tool_calls] == [("get_weather", "{\"city\":\"Oslo\"}")] and c.finish_reason == "tool_calls"
    assert json.loads(s.seen[0][3])["tools"][0]["function"]["name"] == "get_weather"


def test_mistral_and_a_max_tokens_param_setting_choose_the_name_of_the_limit(fake):
    s = fake("openai")
    ask(make("openai_compatible", s.url, models=[{"name": "m"}], **{"max-tokens-param": "max_completion_tokens"}), "x")
    assert "max_completion_tokens" in json.loads(s.seen[0][3]) and "max_tokens" not in json.loads(s.seen[0][3])
    ask(make("mistral", s.url, models=[{"name": "m"}]), "x")
    assert json.loads(s.seen[1][3])["max_tokens"] == 1024


def test_the_openai_compatible_embeddings_model_and_the_live_model_list(fake):
    s = fake("openai")
    p = make("vllm", s.url, models=[{"name": "e", "kind": "embedding", "id": "bge"}])
    r = p.embedding_model("e").embeddings_create(EmbeddingsRequest(model="e", input=["a", "b"]))
    assert len(r.data) == 2 and r.vectors[0] == [0.1, 0.2, 0.3, 0.4] and s.seen[0][1] == "/embeddings"
    live = p.live_models()                                                       # keyless local servers list what they serve
    assert [m.id for m in live] == ["served-model", "served-embed"] and live[1].kind == "embedding" and live[0].source == "live"
    assert p.probe().ok
    assert make("groq", s.url, models=[{"name": "m"}]).live_models() == []      # a hosted preset does not call the list unless told to


def test_errors_say_the_cause_and_never_carry_the_body(fake):
    s = fake("openai")
    p = make("together", s.url, models=[{"name": "m"}])
    with pytest.raises(LLMError) as e:
        ask(p, "boom")
    assert e.value.kind == "FAILED" and "HTTP 500" in str(e.value) and "secret" not in str(e.value)
    with pytest.raises(LLMError) as e:
        ask(p, "limit")
    assert e.value.kind == "RATE_LIMITED" and "HTTP 429" in str(e.value)
    bad = ProviderBase.from_settings("p", {"type": "together", "base-url": s.url, "api-key": "${OTHER}", "models": [{"name": "m"}]}, env={"OTHER": "wrong"}.get)
    with pytest.raises(LLMError) as e:
        ask(bad, "x")
    assert "HTTP 401" in str(e.value) and "authentication failed" in str(e.value) and "wrong" not in str(e.value)


def test_a_slow_server_is_a_timeout(fake):
    s = fake("openai")
    p = make("xai", s.url, models=[{"name": "m"}], timeout="300ms")
    with pytest.raises(LLMError) as e:
        ask(p, "slow")
    assert e.value.kind == "TIMEOUT"


def test_retries_try_again_on_a_server_error(fake):
    s = fake("openai")
    with pytest.raises(LLMError):
        ask(make("groq", s.url, models=[{"name": "m"}], retries=2), "boom")
    assert len(s.seen) == 3


def test_describe_config_masks_every_secret_and_keeps_the_rest(fake):
    p = ProviderBase.from_settings("g", {"type": "groq", "api-key": "${THE_KEY}", "header": {"Authorization": "${THE_KEY}", "X-Team": "red"}, "max-tokens-param": "max_tokens",
                                         "proxy": {"url": "http://proxy:3128", "username": "u", "password": "${THE_KEY}"}}, env=ENV.get)
    d = p.describe_config()
    text = json.dumps(d)
    assert KEY not in text and d["apiKey"] == "********"
    assert d["settings"]["header.Authorization"] == "********" and d["settings"]["header.X-Team"] == "red" and d["settings"]["max-tokens-param"] == "max_tokens"
    assert d["settings"]["proxy.password"] == "********" and d["settings"]["proxy.url"] == "http://proxy:3128"


def test_the_proxy_settings_are_honoured(fake):
    s = fake("openai")
    p = make("groq", "http://upstream.invalid", models=[{"name": "m"}], proxy={"url": s.url})                    # the fake is the proxy: it answers any path
    assert ask(p, "ping").text == "echo: ping"
    assert s.seen[0][1].startswith("http://upstream.invalid") and s.seen[0][2]["Host"].startswith("upstream.invalid")


# ---- Azure OpenAI ----------------------------------------------------------------------------------------------------------------------------------------

def test_azure_v1_style_sends_the_api_key_header_and_the_deployment_as_the_model(fake):
    s = fake("azure")
    p = make("azure_openai", s.url, deployments={"gpt-4o": "my-dep"})
    assert [m.id for m in p.models()] == ["gpt-4o"]
    c = ask(p, "ping")
    _, path, hdr, body = s.seen[0]
    assert path == "/openai/v1/chat/completions" and hdr["api-key"] == KEY and "Authorization" not in hdr
    assert json.loads(body)["model"] == "my-dep" and "max_completion_tokens" in json.loads(body) and c.text == "echo: ping"


def test_azure_deployments_style_puts_the_deployment_and_api_version_in_the_url(fake):
    s = fake("azure")
    p = make("azure_openai", s.url, **{"api-style": "deployments", "api-version": "2024-10-21", "deployments": {"gpt-4o": "my-dep", "text-embedding-3": "emb"}})
    ask(p, "ping", model="gpt-4o")
    assert s.seen[0][1] == "/openai/deployments/my-dep/chat/completions?api-version=2024-10-21"
    p.embedding_model("text-embedding-3").embeddings_create(EmbeddingsRequest(model="text-embedding-3", input=["a"]))
    assert s.seen[1][1] == "/openai/deployments/emb/embeddings?api-version=2024-10-21"


def test_azure_entra_gets_a_token_from_the_authority_caches_it_and_sends_it_as_bearer(fake):
    s = fake("azure-entra")
    p = make("azure_openai", s.url, auth="entra", **{"api-key": None, "tenant-id": "tnt", "client-id": "cid", "client-secret": "${ASECRET}", "entra-authority": s.url,
                                                      "deployments": {"gpt-4o": "d"}})
    assert p.health().ok and "client_secret" in p.health().detail
    ask(p, "one")
    ask(p, "two")
    tokens = [x for x in s.seen if x[1].endswith("/oauth2/v2.0/token")]
    assert len(tokens) == 1 and tokens[0][1] == "/tnt/oauth2/v2.0/token"                                       # cached for the second call
    form = tokens[0][3]
    assert "grant_type=client_credentials" in form and "client_id=cid" in form and "scope=https%3A%2F%2Fcognitiveservices.azure.com%2F.default" in form
    assert "client_secret=" in form and ENV["ASECRET"].split("/")[0] in form
    chat = [x for x in s.seen if x[1].endswith("/chat/completions")]
    assert all(x[2]["Authorization"] == "Bearer entra-token-1" for x in chat) and all("api-key" not in x[2] for x in chat)


def test_azure_entra_workload_identity_sends_the_federated_assertion_and_managed_identity_is_not_built(fake, tmp_path):
    s = fake("azure-entra")
    f = tmp_path / "fed.jwt"
    f.write_text("jwt-from-file\n")
    p = make("azure_openai", s.url, auth="entra", **{"api-key": None, "tenant-id": "t", "client-id": "c", "federated-token-file": str(f), "entra-authority": s.url,
                                                      "deployments": {"g": "d"}})
    assert "workload_identity" in p.health().detail
    ask(p, "x")
    form = s.seen[0][3]
    assert "client_assertion=jwt-from-file" in form and "client_assertion_type=urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer" in form
    mi = make("azure_openai", s.url, auth="entra", **{"api-key": None, "tenant-id": "t", "client-id": "c", "deployments": {"g": "d"}})
    assert not mi.health().ok and "managed identity is not built" in mi.health().detail


def test_azure_without_a_key_is_down_and_without_a_resource_is_refused():
    p = ProviderBase.from_settings("a", {"type": "azure_openai", "base-url": "https://r.openai.azure.com"}, env={}.get)
    assert not p.health().ok and "AZURE_OPENAI_API_KEY" in p.health().detail
    with pytest.raises(LLMError):
        ProviderBase.from_settings("a", {"type": "azure_openai"}, env={}.get)


# ---- Gemini ----------------------------------------------------------------------------------------------------------------------------------------------

def test_gemini_generate_content_request_and_reply(fake):
    s = fake("gemini")
    p = make("gemini", s.url, models=[{"name": "m", "id": "gemini-2.5-flash", "cost-per-1k-input": 0.5}])
    c = p.chat_model("m").chat_completions_create(messages=[{"role": "system", "content": "be brief"}, {"role": "user", "content": "ping"}], temperature=0.2, max_tokens=50)
    _, path, hdr, body = s.seen[0]
    j = json.loads(body)
    assert path == "/v1beta/models/gemini-2.5-flash:generateContent" and hdr["x-goog-api-key"] == KEY
    assert j["systemInstruction"] == {"parts": [{"text": "be brief"}]} and j["contents"] == [{"role": "user", "parts": [{"text": "ping"}]}]
    assert j["generationConfig"]["temperature"] == 0.2 and j["generationConfig"]["maxOutputTokens"] == 50
    assert c.text == "echo: ping" and c.usage.total_tokens == 8 and c.finish_reason == "stop"


def test_gemini_tools_json_mode_embeddings_and_the_model_list(fake):
    s = fake("gemini")
    p = make("gemini", s.url, models=[{"name": "m", "id": "g"}, {"name": "e", "id": "gemini-embedding-2", "kind": "embedding"}])
    req = ChatCompletionRequest(model="m", messages=[ChatMessage.user("weather in Oslo")], response_format={"type": "json_object"},
                                tools=[{"type": "function", "function": {"name": "get_weather", "parameters": {"type": "object", "additionalProperties": False}}}])
    c = p.chat_model("m").chat_completions_create(req)
    j = json.loads(s.seen[0][3])
    assert c.tool_calls[0].function.name == "get_weather" and c.finish_reason == "tool_calls"
    assert j["generationConfig"]["responseMimeType"] == "application/json" and "parametersJsonSchema" in j["tools"][0]["functionDeclarations"][0]
    r = p.embedding_model("e").embeddings_create(EmbeddingsRequest(model="e", input=["a", "b"]))
    assert s.seen[1][1] == "/v1beta/models/gemini-embedding-2:batchEmbedContents" and json.loads(s.seen[1][3])["requests"][0]["taskType"] == "RETRIEVAL_DOCUMENT"
    assert len(r.data) == 2
    assert [m.id for m in p.live_models()] == ["served-model", "served-embed"] and p.probe().ok
    o = make("gemini", s.url, models=[{"name": "m", "id": "g"}], **{"schema-mode": "openapi"})
    o.chat_model("m").chat_completions_create(req)
    d = json.loads(s.seen[-1][3])["tools"][0]["functionDeclarations"][0]
    assert d["parameters"] == {"type": "object"} and "parametersJsonSchema" not in d


def test_gemini_errors_timeout_and_vertex_is_not_built(fake):
    s = fake("gemini")
    p = make("gemini", s.url, models=[{"name": "m"}], timeout="300ms")
    for text, kind in (("boom", "FAILED"), ("limit", "RATE_LIMITED"), ("slow", "TIMEOUT")):
        with pytest.raises(LLMError) as e:
            ask(p, text)
        assert e.value.kind == kind
    with pytest.raises(LLMError) as e:
        make("gemini", s.url, platform="vertex")
    assert "not built" in str(e.value)


# ---- Cohere ----------------------------------------------------------------------------------------------------------------------------------------------

def test_cohere_v2_chat_embed_and_models(fake):
    s = fake("cohere")
    p = make("cohere", s.url, models=[{"name": "m", "id": "command-a-03-2025"}, {"name": "e", "id": "embed-v4.0", "kind": "embedding"}])
    c = ask(p, "ping", model="m")
    _, path, hdr, body = s.seen[0]
    assert path == "/v2/chat" and hdr["Authorization"] == "Bearer " + KEY and json.loads(body)["model"] == "command-a-03-2025" and c.text == "echo: ping"
    t = p.chat_model("m").chat_completions_create(ChatCompletionRequest(model="m", messages=[ChatMessage.user("weather")],
                                                                        tools=[{"type": "function", "function": {"name": "get_weather", "parameters": {"type": "object"}}}]))
    assert t.tool_calls[0].function.name == "get_weather" and t.finish_reason == "tool_calls"
    r = p.embedding_model("e").embeddings_create(EmbeddingsRequest(model="e", input=["a"]))
    assert s.seen[2][1] == "/v2/embed" and json.loads(s.seen[2][3])["input_type"] == "search_document" and len(r.data) == 1
    assert [m.id for m in p.live_models()] == ["served-model", "served-embed"]
    for text, kind in (("boom", "FAILED"), ("limit", "RATE_LIMITED")):
        with pytest.raises(LLMError) as e:
            ask(p, text, model="m")
        assert e.value.kind == kind


def test_cohere_timeout(fake):
    s = fake("cohere")
    with pytest.raises(LLMError) as e:
        ask(make("cohere", s.url, models=[{"name": "m"}], timeout="300ms"), "slow")
    assert e.value.kind == "TIMEOUT"


# ---- Ollama ----------------------------------------------------------------------------------------------------------------------------------------------

def test_ollama_needs_no_key_and_speaks_api_chat(fake):
    s = fake("ollama")
    p = make("ollama", s.url, models=[{"name": "m", "id": "llama3.2"}], **{"num-ctx": 4096, "keep-alive": "5m", "options": {"top_k": 20}})
    c = ask(p, "ping", temperature=0.3)
    _, path, hdr, body = s.seen[0]
    j = json.loads(body)
    assert path == "/api/chat" and "Authorization" not in hdr and j["stream"] is False and j["model"] == "llama3.2"
    assert j["options"] == {"top_k": 20, "num_predict": 1024, "temperature": 0.3, "num_ctx": 4096} and j["keep_alive"] == "5m"
    assert c.text == "echo: ping" and c.usage.total_tokens == 8
    t = ask(p, "weather")
    assert t.tool_calls[0].function.name == "get_weather" and json.loads(t.tool_calls[0].function.arguments) == {"city": "Oslo"}


def test_ollama_embeddings_the_pulled_models_and_health_probe(fake):
    s = fake("ollama")
    p = make("ollama", s.url, models=[{"name": "e", "id": "nomic-embed-text", "kind": "embedding"}, {"name": "m", "id": "llama3.2"}])
    r = p.embedding_model("e").embeddings_create(EmbeddingsRequest(model="e", input=["a", "b"]))
    assert s.seen[0][1] == "/api/embed" and len(r.data) == 2
    assert [m.id for m in p.live_models()] == []                      # both pulled models are already in the catalogue of the file
    p2 = make("ollama", s.url, catalogue=False, models=[{"name": "x", "id": "other"}])
    assert [m.id for m in p2.live_models()] == ["llama3.2", "nomic-embed-text"]
    assert p.health().ok and p.probe().ok and "2 model(s) pulled" in p.probe().detail


def test_ollama_down_is_reported_not_raised_and_errors_are_mapped(fake):
    s = fake("ollama")
    url = s.url
    p = make("ollama", url, models=[{"name": "m"}], timeout="300ms")
    for text, kind in (("boom", "FAILED"), ("limit", "RATE_LIMITED"), ("slow", "TIMEOUT")):
        with pytest.raises(LLMError) as e:
            ask(p, text)
        assert e.value.kind == kind
    s.close()
    d = make("ollama", url, models=[{"name": "m"}], timeout="300ms").probe()
    assert not d.ok and "not answering" in d.detail
    s.__init__("ollama")                                               # keep the fixture's close() valid


def test_a_bare_ollama_host_gets_http_and_the_port():
    assert ProviderBase.from_settings("o", {"type": "ollama", "base-url": "gpu-box"}).describe_config()["baseUrl"] == "gpu-box"
    from core.ai.providers.ollama import normalize_base
    assert normalize_base("gpu-box") == "http://gpu-box:11434" and normalize_base(":8080") == "http://localhost:8080" and normalize_base("http://h:1/") == "http://h:1"


# ---- Bedrock ---------------------------------------------------------------------------------------------------------------------------------------------

def _checker(region="eu-west-1"):
    """What a fake Bedrock can check of a boto3 request without the secret: the SigV4 scheme, the key id, the date, the service and the region of the credential scope."""
    def check(method, url, body, hdr):
        h = {k.lower(): v for k, v in hdr.items()}
        a = h.get("authorization", "")
        return (a.startswith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/") and f"/{region}/bedrock/aws4_request" in a and "SignedHeaders=" in a and "Signature=" in a
                and "x-amz-date" in h)
    return check


@pytest.fixture(autouse=True)
def _no_ambient_aws(monkeypatch, tmp_path):
    for k in ("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_PROFILE", "AWS_REGION", "AWS_DEFAULT_REGION"):
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setenv("AWS_EC2_METADATA_DISABLED", "true")
    monkeypatch.setenv("AWS_CONFIG_FILE", str(tmp_path / "none-config"))
    monkeypatch.setenv("AWS_SHARED_CREDENTIALS_FILE", str(tmp_path / "none-credentials"))


def test_bedrock_converse_is_signed_with_sigv4_and_carries_the_session_token(fake):
    s = fake("bedrock", sigv4_check=_checker())
    p = make("bedrock", s.url, models=[{"name": "n", "id": "us.amazon.nova-pro-v1:0", "cost-per-1k-input": 1.0}], region="eu-west-1",
             **{"aws-access-key-id": "${AKID}", "aws-secret-access-key": "${ASECRET}", "aws-session-token": "${AWS_TOKEN}"})
    c = p.chat_model("n").chat_completions_create(messages=[{"role": "system", "content": "be brief"}, {"role": "user", "content": "ping"}], max_tokens=77)
    _, path, hdr, body = s.seen[0]
    j = json.loads(body)
    assert path == "/model/us.amazon.nova-pro-v1%3A0/converse" and {k.lower(): v for k, v in hdr.items()}["x-amz-security-token"] == "tok-session" and hdr["Authorization"].startswith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/")
    assert j["system"] == [{"text": "be brief"}] and j["messages"] == [{"role": "user", "content": [{"text": "ping"}]}] and j["inferenceConfig"]["maxTokens"] == 77
    assert c.text == "echo: ping" and c.usage.total_tokens == 8 and c.drishti.cost_usd == pytest.approx(0.005)


def test_bedrock_tools_embeddings_errors_and_the_default_credential_chain_is_not_built(fake):
    s = fake("bedrock", sigv4_check=_checker())
    keys = {"aws-access-key-id": "${AKID}", "aws-secret-access-key": "${ASECRET}"}
    p = make("bedrock", s.url, models=[{"name": "n", "id": "anthropic.claude-sonnet-5-5"}, {"name": "t", "id": "amazon.titan-embed-text-v2:0", "kind": "embedding"},
                                      {"name": "c", "id": "cohere.embed-english-v3", "kind": "embedding"}], region="eu-west-1", **keys)
    t = p.chat_model("n").chat_completions_create(ChatCompletionRequest(model="n", messages=[ChatMessage.user("weather")],
                                                                        tools=[{"type": "function", "function": {"name": "get_weather", "parameters": {"type": "object"}}}]))
    assert t.tool_calls[0].function.name == "get_weather" and json.loads(s.seen[0][3])["toolConfig"]["tools"][0]["toolSpec"]["inputSchema"] == {"json": {"type": "object"}}
    assert len(p.embedding_model("t").embeddings_create(EmbeddingsRequest(model="t", input=["a", "b"])).data) == 2
    assert json.loads(s.seen[1][3]) == {"inputText": "a"} and s.seen[1][1] == "/model/amazon.titan-embed-text-v2%3A0/invoke"          # Titan: one call per text
    assert len(p.embedding_model("c").embeddings_create(EmbeddingsRequest(model="c", input=["a", "b"])).data) == 2
    for text, kind in (("boom", "FAILED"), ("limit", "RATE_LIMITED")):
        with pytest.raises(LLMError) as e:
            ask(p, text, model="n")
        assert e.value.kind == kind
    wrong = make("bedrock", s.url, models=[{"name": "n"}], region="us-east-1", **keys)                                                  # signed for another region
    with pytest.raises(LLMError) as e:
        ask(wrong, "x")
    assert "HTTP 403" in str(e.value)
    nokeys = ProviderBase.from_settings("b", {"type": "bedrock"}, env={}.get)
    assert not nokeys.health().ok and "no AWS credentials" in nokeys.health().detail
    with pytest.raises(LLMError) as e:
        ask(ProviderBase.from_settings("b", {"type": "bedrock", "base-url": s.url, "models": [{"name": "n"}]}, env={}.get), "x")
    assert e.value.kind == "CONFIG"


def test_bedrock_timeout_and_the_aws_credential_chain_is_used_when_the_file_names_no_keys(fake, monkeypatch):
    s = fake("bedrock")
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "AKIDEXAMPLE")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "s")
    env = {"AWS_REGION": "ap-south-1"}
    p = ProviderBase.from_settings("b", {"type": "bedrock", "base-url": s.url, "timeout": "300ms", "models": [{"name": "n"}]}, env=env.get)
    assert p.health().ok and "ap-south-1" in p.health().detail
    with pytest.raises(LLMError) as e:
        ask(p, "slow")
    assert e.value.kind == "TIMEOUT"
    d = p.describe_config()
    assert d["settings"]["region"] == "ap-south-1"


# ---- the shared contract, run against the HTTP providers ------------------------------------------------------------------------------------------------------

def contract_cases():
    return [pytest.param(c, id=c["label"]) for c in CONTRACT["providers"]]


@pytest.mark.parametrize("case", contract_cases())
def test_the_shared_contract_against_each_http_provider(fake, case):
    s = fake(case["fake"], sigv4_check=_checker("us-east-1") if case["type"] == "bedrock" else None)
    cfg = {**case["config"], "type": case["type"], "base-url": s.url, "timeout": "5s"}
    p = ProviderBase.from_settings(CONTRACT["name"], cfg, env=ENV.get)
    assert [(m.id, m.kind, m.qualified_id) for m in p.models()] == [(w["id"], w["kind"], CONTRACT["name"] + "/" + w["id"]) for w in CONTRACT["catalogue"]]
    model = p.chat_model("")
    assert model.info().id == CONTRACT["catalogue"][0]["id"]
    for sc in CONTRACT["chat"]:
        want = sc["expect"]
        req = ChatCompletionRequest(model=model.model_id, messages=[ChatMessage(role=r, content=c) for r, c in sc["messages"]],
                                    tools=[{"type": "function", "function": {"name": "get_weather", "parameters": {"type": "object"}}}] if "tool" in want else None)
        if "error" in want:
            with pytest.raises(LLMError) as e:
                model.chat_completions_create(req)
            assert e.value.kind == want["error"], sc["name"]
            continue
        c = model.chat_completions_create(req)
        assert c.finish_reason == want["finish_reason"], sc["name"]
        if "text" in want:
            assert c.text == want["text"], sc["name"]
        if "tool" in want:
            assert [(t.function.name, t.function.arguments) for t in c.tool_calls] == [(want["tool"], want["tool_arguments"])]
        assert c.usage.total_tokens > 0 and c.drishti.qualified_model == f"{CONTRACT['name']}/{model.model_id}"
    stream = CONTRACT["stream"]
    chunks = list(model.chat_completions_stream(ChatCompletionRequest(model=model.model_id, messages=[ChatMessage(role=r, content=c) for r, c in stream["messages"]])))
    assert "".join(c.delta for c in chunks) == stream["joined"] and chunks[-1].choices[0].finish_reason == stream["last_finish_reason"]
    assert asyncio.run(model.achat_completions_create(messages=[ChatMessage.user("ping")])).text == "echo: ping"
    e = CONTRACT["embeddings"]
    r = p.embedding_model("").embeddings_create(EmbeddingsRequest(model="", input=e["input"]))
    assert len(r.data) == e["count"] and len(r.vectors[0]) == e["dimensions"]
    for ref in CONTRACT["refusals"]:
        with pytest.raises(LLMError) as err:
            m = p.model(ref["model"], "")
            if ref["call"] == "chat":
                m.chat_completions_create(ChatCompletionRequest(messages=[ChatMessage.user("x")]))
            else:
                m.embeddings_create(EmbeddingsRequest(input=["x"]))
        assert err.value.kind == ref["error"], ref["name"]


def test_the_two_catalogue_copies_are_identical():
    root = Path(__file__).resolve().parents[2]
    a = (root / "drishti-ai/src/main/resources/ai/llm_catalog.json").read_text(encoding="utf-8")
    b = (root / "drishti-console/core/ai/providers/llm_catalog.json").read_text(encoding="utf-8")
    assert a == b


def test_bedrock_reads_a_shared_credentials_profile_and_lists_foundation_models(fake, tmp_path, monkeypatch):
    s = fake("bedrock", sigv4_check=_checker("us-west-2"))
    creds = tmp_path / "creds"
    creds.write_text("[lab]\naws_access_key_id = AKIDEXAMPLE\naws_secret_access_key = abc\n")
    monkeypatch.setenv("AWS_SHARED_CREDENTIALS_FILE", str(creds))
    p = ProviderBase.from_settings("b", {"type": "bedrock", "base-url": s.url, "control-url": s.url, "profile": "lab", "region": "us-west-2", "catalogue": False, "models": [{"name": "n"}]}, env={}.get)
    assert p.health().ok
    live = p.live_models()
    assert {m.id for m in live} >= {"amazon.titan-embed-text-v2:0"} and s.seen[0][1] == "/foundation-models" and p.probe().ok
