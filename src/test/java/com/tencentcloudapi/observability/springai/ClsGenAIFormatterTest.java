package com.tencentcloudapi.observability.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Unit tests for {@link ClsGenAIFormatter}.
 *
 * <p>These assertions pin the exact JSON shape required by the CLS GenAI Trace
 * spec (= OpenTelemetry GenAI SemConv v1.37+). If Spring AI ever renames an
 * accessor or if the formatter diverges from the spec, these tests fail
 * loudly rather than silently writing a wrong format to CLS.
 */
class ClsGenAIFormatterTest {

    @Test
    void formatInputMessages_rendersSystemAndUserInOpenAIChatShape() {
        Prompt prompt = new Prompt(Arrays.asList(
                new SystemMessage("You are a helpful assistant."),
                new UserMessage("What's the weather in Beijing?")
        ));

        String json = ClsGenAIFormatter.formatInputMessages(prompt);

        assertEquals(
                "[{\"role\":\"system\",\"content\":\"You are a helpful assistant.\"},"
              + "{\"role\":\"user\",\"content\":\"What's the weather in Beijing?\"}]",
                json);
    }

    @Test
    void formatInputMessages_escapesControlCharactersInContent() {
        Prompt prompt = new Prompt(new UserMessage("line1\nline2\t\"quoted\""));
        String json = ClsGenAIFormatter.formatInputMessages(prompt);
        assertEquals(
                "[{\"role\":\"user\",\"content\":\"line1\\nline2\\t\\\"quoted\\\"\"}]",
                json);
    }

    @Test
    void formatInputMessages_returnsNullWhenPromptIsEmpty() {
        assertNull(ClsGenAIFormatter.formatInputMessages(null));
    }

    @Test
    void formatOutputMessages_rendersAssistantReplyWithoutToolCalls() {
        ChatResponse response = new ChatResponse(Collections.singletonList(
                new Generation(new AssistantMessage("Sunny, 22°C."))
        ));

        String json = ClsGenAIFormatter.formatOutputMessages(response);

        assertEquals(
                "[{\"role\":\"assistant\",\"content\":\"Sunny, 22°C.\"}]",
                json);
    }

    @Test
    void formatOutputMessages_rendersAssistantReplyWithToolCalls() {
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "call_abc", "function", "get_weather", "{\"city\":\"Beijing\"}");
        AssistantMessage msg = new AssistantMessage(
                null, new LinkedHashMap<>(), Collections.singletonList(tc));

        ChatResponse response = new ChatResponse(Collections.singletonList(
                new Generation(msg)));

        String json = ClsGenAIFormatter.formatOutputMessages(response);

        // content serialises as JSON null (not empty string) when the model
        // only returned tool_calls — matches OpenAI API wire format.
        assertEquals(
                "[{\"role\":\"assistant\",\"content\":null,"
              + "\"tool_calls\":[{\"id\":\"call_abc\",\"type\":\"function\","
              + "\"function\":{\"name\":\"get_weather\","
              + "\"arguments\":\"{\\\"city\\\":\\\"Beijing\\\"}\"}}]}]",
                json);
    }

    @Test
    void formatInputMessages_rendersToolResponseMessage() {
        ToolResponseMessage.ToolResponse resp = new ToolResponseMessage.ToolResponse(
                "call_abc", "get_weather", "{\"temp\":\"22C\"}");
        Prompt prompt = new Prompt(Collections.singletonList(
                new ToolResponseMessage(Collections.singletonList(resp))));

        String json = ClsGenAIFormatter.formatInputMessages(prompt);

        assertEquals(
                "[{\"role\":\"tool\",\"tool_call_id\":\"call_abc\","
              + "\"name\":\"get_weather\","
              + "\"content\":\"{\\\"temp\\\":\\\"22C\\\"}\"}]",
                json);
    }

    @Test
    void formatToolArguments_passesThroughJsonStringVerbatim() {
        String arg = "{\"city\":\"Beijing\",\"unit\":\"C\"}";
        assertEquals(arg, ClsGenAIFormatter.formatToolArguments(arg));
    }

    @Test
    void formatToolArguments_quotesPlainString() {
        assertEquals("\"Beijing\"", ClsGenAIFormatter.formatToolArguments("Beijing"));
    }

    @Test
    void formatToolArguments_serializesMapWithStableJsonShape() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("city", "Beijing");
        args.put("unit", "C");

        String json = ClsGenAIFormatter.formatToolArguments(args);
        assertNotNull(json);
        // LinkedHashMap preserves insertion order → shape is deterministic
        assertEquals("{\"city\":\"Beijing\",\"unit\":\"C\"}", json);
    }

    @Test
    void quote_handlesNullReturningJsonNullLiteral() {
        assertEquals("null", ClsGenAIFormatter.quote(null));
    }

    @Test
    void quote_wrapsEmptyStringWithQuotes() {
        assertEquals("\"\"", ClsGenAIFormatter.quote(""));
    }

    @Test
    void formatOutputMessages_handlesMultipleGenerations() {
        ChatResponse response = new ChatResponse(Arrays.asList(
                new Generation(new AssistantMessage("Option A")),
                new Generation(new AssistantMessage("Option B"))
        ));

        String json = ClsGenAIFormatter.formatOutputMessages(response);

        assertTrue(json.contains("Option A"), "First generation present");
        assertTrue(json.contains("Option B"), "Second generation present");
        assertTrue(json.startsWith("[") && json.endsWith("]"), "Still a JSON array");
    }
}
