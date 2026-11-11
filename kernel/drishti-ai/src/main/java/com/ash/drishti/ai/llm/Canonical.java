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
package com.ash.drishti.ai.llm;

import com.ash.drishti.ai.CallContext;
import java.util.List;
import java.util.Map;

/**
 * The canonical model format: OpenAI Chat Completions and Embeddings, typed. Same names and (camelCase) fields as {@code core/ai/llm/canonical.py} in the console, which
 * mirrors sajhamcpserver's {@code sajha/ai/llm/canonical.py}; the extension field {@code sajha} there is {@code drishti} here. A request stripped of {@code drishti} is a
 * valid OpenAI request. Provider adapters translate to each vendor at the edge. The larger types have their own files; the small ones are nested here.
 */
public final class Canonical {

    private Canonical() {}

    public record FunctionCall(String name, String arguments) {
        public FunctionCall {
            name = name == null ? "" : name;
            arguments = arguments == null ? "{}" : arguments;                     // a JSON string, as in OpenAI
        }
    }

    public record ToolCall(String id, String type, FunctionCall function) {
        public ToolCall {
            type = type == null ? "function" : type;
        }

        public static ToolCall of(String id, String name, String arguments) {
            return new ToolCall(id, "function", new FunctionCall(name, arguments));
        }
    }

    public record ChatMessage(String role, String content, String name, List<ToolCall> toolCalls, String toolCallId, String refusal) {
        public ChatMessage {
            toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
        }

        public static ChatMessage system(String text) {
            return new ChatMessage("system", text, null, null, null, null);
        }

        public static ChatMessage developer(String text) {
            return new ChatMessage("developer", text, null, null, null, null);
        }

        public static ChatMessage user(String text) {
            return new ChatMessage("user", text, null, null, null, null);
        }

        public static ChatMessage assistant(String text, List<ToolCall> toolCalls) {
            return new ChatMessage("assistant", text, null, toolCalls, null, null);
        }

        public static ChatMessage tool(String toolCallId, String content) {
            return new ChatMessage("tool", content, null, null, toolCallId, null);
        }

        /** The message's text, "" when it has none. */
        public String text() {
            return content == null ? "" : content;
        }
    }

    public record FunctionDefinition(String name, String description, String parameters, Boolean strict) {}

    /** {@code parameters} is a JSON Schema as JSON text. */
    public record ToolDefinition(String type, FunctionDefinition function) {
        public ToolDefinition {
            type = type == null ? "function" : type;
        }

        public static ToolDefinition of(String name, String description, String parameters) {
            return new ToolDefinition("function", new FunctionDefinition(name, description, parameters, null));
        }

        public String name() {
            return function == null ? "" : function.name();
        }
    }

    /** {@code type}: text, json_object or json_schema. */
    public record ResponseFormat(String type, String jsonSchema) {
        public ResponseFormat {
            type = type == null ? "text" : type;
        }
    }

    /** What the extension field of a request carries: the caller (identity and feature, for the policy), a trace id, and for embeddings the purpose. */
    public record DrishtiRequest(CallContext context, String traceId, String inputPurpose) {}

    public record CompletionUsage(int promptTokens, int completionTokens, int totalTokens) {
        public static CompletionUsage of(int prompt, int completion) {
            return new CompletionUsage(prompt, completion, prompt + completion);
        }

        public CompletionUsage plus(CompletionUsage o) {
            return of(promptTokens + o.promptTokens, completionTokens + o.completionTokens);
        }
    }

    /** The extension field of a response: who answered, what it cost, how long it took. */
    public record ResponseDrishti(String provider, String qualifiedModel, double costUsd, boolean cached, long latencyMs, String traceId) {}

    public record Choice(int index, ChatMessage message, String finishReason) {}

    public record DeltaFunction(String name, String arguments) {}

    public record DeltaToolCall(int index, String id, String type, DeltaFunction function) {}

    public record ChoiceDelta(String role, String content, String refusal, List<DeltaToolCall> toolCalls) {}

    public record ChunkChoice(int index, ChoiceDelta delta, String finishReason) {}

    public record EmbeddingData(String object, List<Double> embedding, int index) {
        public EmbeddingData {
            object = object == null ? "embedding" : object;
        }

        public float[] vector() {
            float[] v = new float[embedding.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = embedding.get(i).floatValue();
            }
            return v;
        }
    }

    public record EmbeddingsUsage(int promptTokens, int totalTokens) {}

    /** Free-form options a provider may take that have no standard field. */
    public static Map<String, Object> noExtra() {
        return Map.of();
    }
}
