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

"""The shared LLM contract against the mock provider, through the canonical abstractions (``LLMProvider``, ``LLMModel``). The Java layer runs the same file
(``drishti-ai/src/test/resources/contract/llm_contract.json``) in ``LLMContractTest``; if the two layers drift one of the tests fails. Also ``BaseMCPTool``."""
import asyncio
import json
from pathlib import Path

import pytest

from core.ai.gateway import LlmGateway
from core.ai.llm import (ChatCompletionRequest, ChatMessage, EmbeddingsRequest, GovernedModel, LLMError, LLMFactory, LLMModel, LLMProvider, ProviderBase,
                         UnsupportedFeature, registered_providers)
from core.ai.mcp_tool import BaseMCPTool, ToolArgumentError
from core.ai.policy import DefaultPolicy, PolicySettings, PromptMasker
from core.ai.providers import FACTORIES
from core.ai.registry import LlmRegistry
from core.ai import config as cfgmod
from core.ai.types import CallContext

CONTRACT = json.loads((Path(__file__).resolve().parents[2] / "drishti-ai/src/test/resources/contract/llm_contract.json").read_text(encoding="utf-8"))
P = CONTRACT["provider"]


@pytest.fixture()
def provider() -> LLMProvider:
    return ProviderBase.from_settings(P["name"], {**P["config"], "type": P["type"]})


def msgs(rows):
    return [ChatMessage(role=r, content=c) for r, c in rows]


def test_the_catalogue_lists_the_models_with_kind_and_qualified_id(provider):
    assert isinstance(provider, LLMProvider) and provider.name == "contract" and provider.active
    got = [(m.id, m.kind, m.qualified_id) for m in provider.models()]
    assert got == [(w["id"], w["kind"], w["qualified_id"]) for w in CONTRACT["catalogue"]]
    assert provider.health().ok
    d = provider.describe_config()
    assert d["type"] == "mock" and d["apiKey"] == ""


def test_chat_scenarios_give_the_same_answers_as_in_java(provider):
    model = provider.chat_model("")
    assert isinstance(model, LLMModel) and model.info().id == "chat"
    for s in CONTRACT["chat"]:
        want = s["expect"]
        req = ChatCompletionRequest(model="chat", messages=msgs(s["messages"]))
        if "error" in want:
            with pytest.raises(LLMError) as e:
                model.chat_completions_create(req)
            assert e.value.kind == want["error"], s["name"]
            continue
        c = model.chat_completions_create(req)
        assert c.finish_reason == want["finish_reason"], s["name"]
        if "text" in want:
            assert c.text == want["text"], s["name"]
        if "tool" in want:
            assert [(t.function.name, t.function.arguments) for t in c.tool_calls] == [(want["tool"], want["tool_arguments"])]
        assert c.usage.total_tokens > 0
        assert c.drishti.qualified_model == "contract/chat"


def test_the_async_twin_and_keyword_fields_agree(provider):
    model = provider.model("chat", "chat")
    c = asyncio.run(model.achat_completions_create(messages=[{"role": "user", "content": "ping"}]))
    assert c.text == "echo: ping"
    assert model.chat_completions_create(messages=[ChatMessage.user("ping")]).text == "echo: ping"


def test_a_stream_is_the_completion_in_chunks(provider):
    s = CONTRACT["stream"]
    chunks = list(provider.model("chat", "chat").chat_completions_stream(ChatCompletionRequest(messages=msgs(s["messages"]))))
    assert len(chunks) == s["chunks"]
    assert "".join(c.delta for c in chunks) == s["joined"]
    assert chunks[-1].choices[0].finish_reason == s["last_finish_reason"]

    async def collect():
        return [c async for c in provider.model("chat", "chat").achat_completions_stream(ChatCompletionRequest(messages=msgs(s["messages"])))]
    assert "".join(c.delta for c in asyncio.run(collect())) == s["joined"]


def test_embeddings_give_one_vector_per_input_of_the_configured_size(provider):
    e = CONTRACT["embeddings"]
    r = provider.embedding_model("vec").embeddings_create(EmbeddingsRequest(model="vec", input=e["input"]))
    assert len(r.data) == e["count"] and len(r.vectors[0]) == e["dimensions"]
    assert provider.model("vec", "").info().kind == "embedding"


def test_refusals_carry_the_same_kinds_as_in_java(provider):
    for s in CONTRACT["refusals"]:
        with pytest.raises(LLMError) as e:
            m = provider.model(s["model"], "")
            if s["call"] == "chat":
                m.chat_completions_create(ChatCompletionRequest(messages=[ChatMessage.user("x")]))
            else:
                m.embeddings_create(EmbeddingsRequest(input=["x"]))
        assert e.value.kind == s["error"], s["name"]
    assert issubclass(UnsupportedFeature, LLMError)


def test_the_governed_factory_applies_the_policy_to_canonical_calls():
    seen = []

    class Spy(DefaultPolicy):
        def before(self, ctx, model, request):
            seen.append((ctx.feature, request.messages[0].content))
            return super().before(ctx, model, request)

    cfg = cfgmod.provider_from_dict({**P["config"], "type": P["type"]}, P["name"], {}.get)
    reg = LlmRegistry(FACTORIES, lambda: ([cfg], {}), None, "contract/chat")
    reg.reload()
    factory = LLMFactory(LlmGateway(reg, Spy(PolicySettings(), PromptMasker(["salary"]))))
    model = factory.model("contract/chat", context=CallContext("ann", frozenset(), "unit"))
    assert isinstance(model, GovernedModel)
    c = model.chat_completions_create(messages=[{"role": "user", "content": "salary: 90000 ping"}])
    assert seen == [("unit", "salary: 90000 ping")]
    assert c.text == "echo: salary: ••• ping"                      # the policy masked the prompt before the model saw it
    assert factory.provider_names() == ["contract"]
    assert {"mock", "openai", "anthropic", "server"} <= set(LLMFactory.provider_types()) and "mock" in registered_providers()
    assert factory.provider("contract").chat_model().info().id == "chat"


class Echo(BaseMCPTool):
    def execute(self, arguments):
        return {"echo": arguments["text"]}

    def get_input_schema(self):
        return {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"], "additionalProperties": False}

    def get_output_schema(self):
        return {"type": "object"}


def test_a_tool_validates_runs_tracks_and_serves_itself_in_mcp_format():
    t = Echo({"name": "echo", "description": "Echoes", "version": "2.1"})
    assert t.execute_with_tracking({"text": "hi"}) == {"echo": "hi"}
    with pytest.raises(ToolArgumentError):
        t.execute_with_tracking({})
    with pytest.raises(ToolArgumentError):
        t.execute_with_tracking({"text": "x", "other": 1})
    assert t.get_metrics()["calls"] == 3 and t.get_metrics()["errors"] == 2
    assert t.to_mcp_format()["name"] == "echo" and t.version == "2.1" and t.enabled
    t.disable()
    assert not t.enabled
