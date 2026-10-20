package com.tencentcloudapi.observability.springai;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Converts Spring AI's native request / response objects
 * ({@link Prompt}, {@link ChatResponse}) into the JSON format mandated by the
 * CLS GenAI Trace spec (= OpenTelemetry GenAI Semantic Conventions v1.37+).
 *
 * <p>The spec requires {@code gen_ai.input.messages} and
 * {@code gen_ai.output.messages} to be a JSON array of OpenAI-chat–shaped
 * message objects:
 *
 * <pre>
 *   gen_ai.input.messages:
 *     [{"role":"system","content":"..."},
 *      {"role":"user","content":"..."}]
 *
 *   gen_ai.output.messages:
 *     [{"role":"assistant","content":"...","tool_calls":[
 *          {"id":"call_xxx","type":"function",
 *           "function":{"name":"get_weather","arguments":"{\"city\":\"北京\"}"}}
 *     ]}]
 * </pre>
 *
 * <p>Writing a {@code Prompt.toString()} string here instead would make the
 * CLS console render it as a single opaque text blob rather than a structured
 * message bubble list.
 *
 * <p>Spring AI 1.0 GA public API (verified via {@code javap}, all stable):
 * <ul>
 *     <li>{@code Prompt#getInstructions()} → {@code List<Message>}</li>
 *     <li>{@code ChatResponse#getResults()} → {@code List<Generation>}</li>
 *     <li>{@code Generation#getOutput()} → {@code AssistantMessage}</li>
 *     <li>{@code AbstractMessage#getText()} → content</li>
 *     <li>{@code AbstractMessage#getMessageType()} → role enum</li>
 *     <li>{@code AssistantMessage#getToolCalls()} → tool_calls list</li>
 *     <li>{@code AssistantMessage.ToolCall} is a record exposing
 *         {@code id()}, {@code type()}, {@code name()}, {@code arguments()}</li>
 *     <li>{@code ToolResponseMessage#getResponses()} → list of tool outputs,
 *         each a record with {@code id()}, {@code name()}, {@code responseData()}</li>
 * </ul>
 *
 * <p>Thread-safe (stateless), zero external dependencies — hand-rolled JSON
 * writer to keep the SDK dependency-free. Jackson would be overkill for the
 * handful of shapes we emit here.
 */
final class ClsGenAIFormatter {

    private ClsGenAIFormatter() { /* utility */ }

    // ─────────────────────────────────────────────────────────────────
    //  Public entry points
    // ─────────────────────────────────────────────────────────────────

    /**
     * Format a Spring AI {@link Prompt} as a {@code gen_ai.input.messages} JSON array.
     *
     * @return JSON string, or {@code null} if {@code prompt} is null / empty
     */
    static String formatInputMessages(Prompt prompt) {
        if (prompt == null) return null;
        List<Message> messages = prompt.getInstructions();
        if (messages == null || messages.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(256).append('[');
        boolean first = true;
        for (Message m : messages) {
            if (m == null) continue;
            if (!first) sb.append(',');
            first = false;
            writeMessage(sb, m);
        }
        return sb.append(']').toString();
    }

    /**
     * Format a Spring AI {@link ChatResponse} as a {@code gen_ai.output.messages}
     * JSON array. Each {@link Generation} contributes one assistant message
     * entry (with {@code tool_calls} if the model picked a function to call).
     *
     * @return JSON string, or {@code null} if {@code response} is null / empty
     */
    static String formatOutputMessages(ChatResponse response) {
        if (response == null) return null;
        List<Generation> generations = response.getResults();
        if (generations == null || generations.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(512).append('[');
        boolean first = true;
        for (Generation g : generations) {
            if (g == null) continue;
            AssistantMessage msg = g.getOutput();
            if (msg == null) continue;
            if (!first) sb.append(',');
            first = false;
            writeAssistantMessage(sb, msg);
        }
        return sb.append(']').toString();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Message writers
    // ─────────────────────────────────────────────────────────────────

    private static void writeMessage(StringBuilder sb, Message m) {
        MessageType type = m.getMessageType();
        if (type == MessageType.ASSISTANT && m instanceof AssistantMessage) {
            writeAssistantMessage(sb, (AssistantMessage) m);
        } else if (type == MessageType.TOOL && m instanceof ToolResponseMessage) {
            writeToolResponseMessage(sb, (ToolResponseMessage) m);
        } else {
            // USER / SYSTEM — plain {role, content}
            sb.append("{\"role\":").append(quote(roleOf(type)))
              .append(",\"content\":").append(quote(safeText(m)))
              .append('}');
        }
    }

    private static void writeAssistantMessage(StringBuilder sb, AssistantMessage m) {
        sb.append("{\"role\":\"assistant\",\"content\":");
        String text = m.getText();
        sb.append(text == null || text.isEmpty() ? "null" : quote(text));
        List<AssistantMessage.ToolCall> toolCalls = m.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            sb.append(",\"tool_calls\":[");
            boolean first = true;
            for (AssistantMessage.ToolCall tc : toolCalls) {
                if (!first) sb.append(',');
                first = false;
                writeToolCall(sb, tc);
            }
            sb.append(']');
        }
        sb.append('}');
    }

    private static void writeToolCall(StringBuilder sb, AssistantMessage.ToolCall tc) {
        // ToolCall is a record (no `get` prefix): id(), type(), name(), arguments()
        String id   = tc.id();
        String name = tc.name();
        String args = tc.arguments();  // Already a JSON string from the LLM
        sb.append("{\"id\":").append(quote(nullSafe(id)))
          .append(",\"type\":\"function\"")
          .append(",\"function\":{")
          .append("\"name\":").append(quote(nullSafe(name)))
          .append(",\"arguments\":").append(quote(nullSafe(args)))
          .append("}}");
    }

    /** Spring AI's tool reply (role=tool) carries a list of ToolResponse records.
     *  We expand them to one OpenAI-style message per entry — matches what you'd
     *  send back to the model in a follow-up API call. */
    private static void writeToolResponseMessage(StringBuilder sb, ToolResponseMessage m) {
        List<ToolResponseMessage.ToolResponse> responses = m.getResponses();
        if (responses == null || responses.isEmpty()) {
            // Degenerate case — nothing to emit, write an empty tool message.
            sb.append("{\"role\":\"tool\",\"content\":\"\"}");
            return;
        }
        // One ToolResponseMessage may map to multiple tool-role messages. We
        // emit them joined inside a single object with array-shaped content
        // to preserve 1:1 message slot counts; most SDKs expect this form.
        if (responses.size() == 1) {
            ToolResponseMessage.ToolResponse r = responses.get(0);
            sb.append("{\"role\":\"tool\",\"tool_call_id\":").append(quote(nullSafe(r.id())))
              .append(",\"name\":").append(quote(nullSafe(r.name())))
              .append(",\"content\":").append(quote(nullSafe(r.responseData())))
              .append('}');
            return;
        }
        // Rare: multiple tool responses in one message — serialize as an array
        // of tool entries wrapped in a container object for CLS to render.
        sb.append("{\"role\":\"tool\",\"responses\":[");
        boolean first = true;
        for (ToolResponseMessage.ToolResponse r : responses) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"tool_call_id\":").append(quote(nullSafe(r.id())))
              .append(",\"name\":").append(quote(nullSafe(r.name())))
              .append(",\"content\":").append(quote(nullSafe(r.responseData())))
              .append('}');
        }
        sb.append("]}");
    }

    // ─────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────

    private static String roleOf(MessageType type) {
        if (type == null) return "user";
        switch (type) {
            case SYSTEM:    return "system";
            case ASSISTANT: return "assistant";
            case TOOL:      return "tool";
            case USER:
            default:        return "user";
        }
    }

    private static String safeText(Message m) {
        try {
            String s = m.getText();
            return s == null ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** JSON-encode a string, including surrounding double quotes and all
     *  control-character escapes required by RFC 8259. */
    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    /** Pretty best-effort serializer for an arbitrary tool-arguments object
     *  that may come as a {@code Map}, {@code CharSequence}, or anything else.
     *  Spring AI's {@code ToolCallingObservationContext.getToolCallArguments()}
     *  already returns a JSON string, so this is only used as a fallback for
     *  frameworks or tests that supply a raw object. */
    static String formatToolArguments(Object arguments) {
        if (arguments == null) return null;
        if (arguments instanceof CharSequence) {
            String s = arguments.toString().trim();
            if (s.isEmpty()) return null;
            // Already JSON? Pass through verbatim.
            if ((s.startsWith("{") && s.endsWith("}"))
                    || (s.startsWith("[") && s.endsWith("]"))) {
                return s;
            }
            return quote(s);
        }
        if (arguments instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) arguments;
            StringBuilder sb = new StringBuilder(64).append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(String.valueOf(e.getKey()))).append(':');
                Object v = e.getValue();
                if (v == null) sb.append("null");
                else if (v instanceof Number || v instanceof Boolean) sb.append(v);
                else if (v instanceof Map) sb.append(formatToolArguments(v));
                else sb.append(quote(String.valueOf(v)));
            }
            return sb.append('}').toString();
        }
        return quote(String.valueOf(arguments));
    }
}
