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

"""The canonical model format: OpenAI Chat Completions and Embeddings, typed.

The same names and fields as ``sajha/ai/llm/canonical.py`` in sajhamcpserver (the extension field ``sajha`` there is ``drishti`` here) and, in camelCase, as the
Java layer (``drishti-ai``, ``com.ash.drishti.ai.llm``). A request stripped of ``drishti`` is a valid OpenAI request and a response stripped of ``drishti`` is a
valid OpenAI response. Provider adapters translate to each vendor at the edge (see docs/admin/AI.md › Model providers, section 10).

    Requests     ChatCompletionRequest, ChatMessage, ContentPart, ImageURL, ToolDefinition, FunctionDefinition, ToolCall, FunctionCall, ResponseFormat,
                 JSONSchemaFormat, StreamOptions, DrishtiRequest
    Responses    ChatCompletion, Choice, CompletionUsage, PromptTokensDetails, CompletionTokensDetails, ResponseDrishti
    Streaming    ChatCompletionChunk, ChunkChoice, ChoiceDelta, DeltaToolCall, DeltaFunction, ChunkAccumulator (chunks -> one ChatCompletion), completion_to_chunks
    Embeddings   EmbeddingsRequest, EmbeddingsResponse, EmbeddingData, EmbeddingsUsage
"""
from __future__ import annotations

import time
import uuid
from typing import Any, Dict, Iterator, List, Literal, Optional, Union

from pydantic import BaseModel, ConfigDict, Field


def new_id(prefix: str = "chatcmpl-") -> str:
    return prefix + uuid.uuid4().hex[:24]


class _Model(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)

    def to_dict(self, **kw) -> Dict[str, Any]:
        return self.model_dump(exclude_none=True, by_alias=True, **kw)


class FunctionCall(_Model):
    name: str = ""
    arguments: str = "{}"                      # a JSON string, as in OpenAI


class ToolCall(_Model):
    id: str
    type: str = "function"
    function: FunctionCall = Field(default_factory=FunctionCall)

    @classmethod
    def of(cls, id: str, name: str, arguments: Any) -> "ToolCall":
        import json
        return cls(id=id, function=FunctionCall(name=name, arguments=arguments if isinstance(arguments, str) else json.dumps(arguments)))


class ImageURL(_Model):
    url: str
    detail: Optional[str] = None


class ContentPart(_Model):
    type: str
    text: Optional[str] = None
    image_url: Optional[ImageURL] = None

    @classmethod
    def of_text(cls, text: str) -> "ContentPart":
        return cls(type="text", text=text)


class ChatMessage(_Model):
    role: str
    content: Union[str, List[ContentPart], None] = None
    name: Optional[str] = None
    tool_calls: Optional[List[ToolCall]] = None
    tool_call_id: Optional[str] = None
    refusal: Optional[str] = None

    @classmethod
    def system(cls, text: str) -> "ChatMessage":
        return cls(role="system", content=text)

    @classmethod
    def developer(cls, text: str) -> "ChatMessage":
        return cls(role="developer", content=text)

    @classmethod
    def user(cls, content: Union[str, List[ContentPart]]) -> "ChatMessage":
        return cls(role="user", content=content)

    @classmethod
    def assistant(cls, text: Optional[str] = None, tool_calls: Optional[List[ToolCall]] = None) -> "ChatMessage":
        return cls(role="assistant", content=text, tool_calls=tool_calls or None)

    @classmethod
    def tool(cls, tool_call_id: str, content: Any) -> "ChatMessage":
        return cls(role="tool", tool_call_id=tool_call_id, content=content if isinstance(content, str) else str(content))

    @property
    def text(self) -> str:
        if self.content is None:
            return ""
        if isinstance(self.content, str):
            return self.content
        return "".join(p.text or "" for p in self.content if p.type == "text")


class FunctionDefinition(_Model):
    name: str
    description: Optional[str] = None
    parameters: Optional[Dict[str, Any]] = None
    strict: Optional[bool] = None


class ToolDefinition(_Model):
    type: str = "function"
    function: Optional[FunctionDefinition] = None

    @classmethod
    def of(cls, name: str, description: str = "", parameters: Optional[Dict[str, Any]] = None, strict: Optional[bool] = None) -> "ToolDefinition":
        return cls(function=FunctionDefinition(name=name, description=description, parameters=parameters, strict=strict))

    @property
    def name(self) -> str:
        return self.function.name if self.function else ""


class JSONSchemaFormat(_Model):
    name: str = "response"
    description: Optional[str] = None
    schema_: Optional[Dict[str, Any]] = Field(default=None, alias="schema")
    strict: Optional[bool] = None


class ResponseFormat(_Model):
    type: str = "text"                         # text | json_object | json_schema
    json_schema: Optional[JSONSchemaFormat] = None


class StreamOptions(_Model):
    include_usage: Optional[bool] = None


class DrishtiRequest(_Model):
    """The extension field of a request: the caller (``context``, a ``core.ai.types.CallContext``: identity and feature, for the policy), a trace id, and for embeddings the purpose."""
    context: Optional[Any] = Field(default=None, exclude=True)
    trace_id: str = ""
    input_purpose: Optional[Literal["query", "document"]] = None


class ChatCompletionRequest(_Model):
    model: str = "default"
    messages: List[ChatMessage] = Field(default_factory=list)
    tools: Optional[List[ToolDefinition]] = None
    tool_choice: Optional[Union[str, Dict[str, Any]]] = None
    parallel_tool_calls: Optional[bool] = None
    response_format: Optional[ResponseFormat] = None
    temperature: Optional[float] = None
    top_p: Optional[float] = None
    max_completion_tokens: Optional[int] = None
    max_tokens: Optional[int] = None
    stop: Optional[Union[str, List[str]]] = None
    seed: Optional[int] = None
    n: Optional[int] = None
    stream: Optional[bool] = None
    stream_options: Optional[StreamOptions] = None
    user: Optional[str] = None
    metadata: Optional[Dict[str, str]] = None
    reasoning_effort: Optional[str] = None
    extra_body: Optional[Dict[str, Any]] = None
    drishti: Optional[DrishtiRequest] = None

    @classmethod
    def coerce(cls, request: Any = None, **fields) -> "ChatCompletionRequest":
        """A request object, a dict, or keyword fields (``messages=[...]``), as one request."""
        if isinstance(request, cls):
            return request.model_copy(update=fields) if fields else request
        data = dict(request or {})
        data.update(fields)
        return cls.model_validate(data)

    @property
    def max_output_tokens(self) -> Optional[int]:
        return self.max_completion_tokens if self.max_completion_tokens is not None else self.max_tokens

    @property
    def wants_tools(self) -> bool:
        return bool(self.tools)

    @property
    def context(self) -> Any:
        return self.drishti.context if self.drishti else None


class PromptTokensDetails(_Model):
    cached_tokens: Optional[int] = None


class CompletionTokensDetails(_Model):
    reasoning_tokens: Optional[int] = None


class CompletionUsage(_Model):
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    prompt_tokens_details: Optional[PromptTokensDetails] = None
    completion_tokens_details: Optional[CompletionTokensDetails] = None

    @classmethod
    def of(cls, prompt: int, completion: int) -> "CompletionUsage":
        return cls(prompt_tokens=prompt, completion_tokens=completion, total_tokens=prompt + completion)


class ResponseDrishti(_Model):
    """The extension field of a response: who answered, what it cost, how long it took."""
    provider: str = ""
    qualified_model: str = ""
    cost_usd: float = 0.0
    cached: bool = False
    latency_ms: int = 0
    trace_id: str = ""


class Choice(_Model):
    index: int = 0
    message: ChatMessage = Field(default_factory=lambda: ChatMessage(role="assistant"))
    finish_reason: Optional[str] = "stop"


class ChatCompletion(_Model):
    id: str = Field(default_factory=new_id)
    object: str = "chat.completion"
    created: int = Field(default_factory=lambda: int(time.time()))
    model: str = ""
    choices: List[Choice] = Field(default_factory=list)
    usage: Optional[CompletionUsage] = None
    system_fingerprint: Optional[str] = None
    service_tier: Optional[str] = None
    drishti: Optional[ResponseDrishti] = None

    @property
    def message(self) -> ChatMessage:
        return self.choices[0].message if self.choices else ChatMessage(role="assistant")

    @property
    def text(self) -> str:
        return self.message.text

    @property
    def tool_calls(self) -> List[ToolCall]:
        return list(self.message.tool_calls or [])

    @property
    def refusal(self) -> Optional[str]:
        return self.message.refusal

    @property
    def finish_reason(self) -> str:
        return (self.choices[0].finish_reason if self.choices else None) or "stop"


class DeltaFunction(_Model):
    name: Optional[str] = None
    arguments: Optional[str] = None


class DeltaToolCall(_Model):
    index: int = 0
    id: Optional[str] = None
    type: Optional[str] = None
    function: Optional[DeltaFunction] = None


class ChoiceDelta(_Model):
    role: Optional[str] = None
    content: Optional[str] = None
    refusal: Optional[str] = None
    tool_calls: Optional[List[DeltaToolCall]] = None


class ChunkChoice(_Model):
    index: int = 0
    delta: ChoiceDelta = Field(default_factory=ChoiceDelta)
    finish_reason: Optional[str] = None


class ChatCompletionChunk(_Model):
    id: str = ""
    object: str = "chat.completion.chunk"
    created: int = 0
    model: str = ""
    choices: List[ChunkChoice] = Field(default_factory=list)
    usage: Optional[CompletionUsage] = None
    drishti: Optional[ResponseDrishti] = None

    @property
    def delta(self) -> str:
        return (self.choices[0].delta.content or "") if self.choices else ""


class ChunkAccumulator:
    """Chunks to one ChatCompletion."""

    def __init__(self):
        self._id, self._model, self._text, self._finish, self._usage, self._drishti = "", "", [], None, None, None
        self._calls: Dict[int, ToolCall] = {}

    def add(self, chunk: ChatCompletionChunk) -> None:
        self._id, self._model = chunk.id or self._id, chunk.model or self._model
        self._usage, self._drishti = chunk.usage or self._usage, chunk.drishti or self._drishti
        for ch in chunk.choices:
            if ch.delta.content:
                self._text.append(ch.delta.content)
            for d in ch.delta.tool_calls or []:
                c = self._calls.setdefault(d.index, ToolCall(id=d.id or "", function=FunctionCall(name="", arguments="")))
                c.id = d.id or c.id
                if d.function:
                    c.function.name += d.function.name or ""
                    c.function.arguments += d.function.arguments or ""
            self._finish = ch.finish_reason or self._finish

    def result(self) -> ChatCompletion:
        calls = [self._calls[i] for i in sorted(self._calls)]
        msg = ChatMessage.assistant("".join(self._text), calls or None)
        return ChatCompletion(id=self._id or new_id(), model=self._model, choices=[Choice(message=msg, finish_reason=self._finish or "stop")],
                              usage=self._usage, drishti=self._drishti)


def completion_to_chunks(c: ChatCompletion) -> Iterator[ChatCompletionChunk]:
    """A completion as the chunks a stream would carry: the role, the text, the finish (with the usage). Streaming is emulated this way unless a provider streams natively."""
    base = dict(id=c.id, created=c.created, model=c.model)
    yield ChatCompletionChunk(**base, choices=[ChunkChoice(delta=ChoiceDelta(role="assistant", content=""))])
    yield ChatCompletionChunk(**base, choices=[ChunkChoice(delta=ChoiceDelta(content=c.text))])
    yield ChatCompletionChunk(**base, choices=[ChunkChoice(finish_reason=c.finish_reason)], usage=c.usage, drishti=c.drishti)


class EmbeddingsRequest(_Model):
    model: str = "embedding"
    input: Union[str, List[str]] = Field(default_factory=list)
    encoding_format: Optional[str] = None
    dimensions: Optional[int] = None
    user: Optional[str] = None
    drishti: Optional[DrishtiRequest] = None

    @classmethod
    def coerce(cls, request: Any = None, **fields) -> "EmbeddingsRequest":
        if isinstance(request, cls):
            return request.model_copy(update=fields) if fields else request
        data = dict(request or {})
        data.update(fields)
        return cls.model_validate(data)

    @property
    def texts(self) -> List[str]:
        return [self.input] if isinstance(self.input, str) else list(self.input)


class EmbeddingData(_Model):
    object: str = "embedding"
    embedding: List[float]
    index: int = 0


class EmbeddingsUsage(_Model):
    prompt_tokens: int = 0
    total_tokens: int = 0


class EmbeddingsResponse(_Model):
    object: str = "list"
    data: List[EmbeddingData] = Field(default_factory=list)
    model: str = ""
    usage: EmbeddingsUsage = Field(default_factory=EmbeddingsUsage)
    drishti: Optional[ResponseDrishti] = None

    @property
    def vectors(self) -> List[List[float]]:
        return [d.embedding for d in self.data]
