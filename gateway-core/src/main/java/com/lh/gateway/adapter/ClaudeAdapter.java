package com.lh.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.lh.gateway.model.LlmRequest;
import com.lh.gateway.model.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Claude 适配器（格式转换）
 */
public class ClaudeAdapter implements LlmAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClaudeAdapter.class);

    private final WebClient webClient;
    private final String apiKey;

    public ClaudeAdapter(WebClient webClient, String apiKey) {
        this.webClient = webClient;
        this.apiKey = apiKey;
    }

    @Override
    public String providerName() {
        return "claude";
    }

    @Override
    public Mono<LlmResponse> call(LlmRequest request) {
        Map<String, Object> claudeRequest = convertToClaudeFormat(request);

        return webClient.post()
                .uri("/v1/messages")
                .headers(this::setClaudeHeaders)
                .bodyValue(claudeRequest)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::convertFromClaudeFormat)
                .doOnSuccess(resp -> log.debug("Claude call succeeded"))
                .doOnError(err -> log.error("Claude call failed: {}", err.getMessage()));
    }

    @Override
    public Mono<String> callStream(LlmRequest request) {
        request.setStream(true);
        Map<String, Object> claudeRequest = convertToClaudeFormat(request);

        return webClient.post()
                .uri("/v1/messages")
                .headers(this::setClaudeHeaders)
                .bodyValue(claudeRequest)
                .retrieve()
                .bodyToFlux(String.class)
                .reduce(String::concat);
    }

    @Override
    public Mono<Boolean> healthCheck() {
        return webClient.get()
                .uri("/v1/models")
                .headers(this::setClaudeHeaders)
                .retrieve()
                .bodyToMono(String.class)
                .hasElement()
                .onErrorReturn(false);
    }

    private void setClaudeHeaders(org.springframework.http.HttpHeaders headers) {
        headers.set("anthropic-version", "2023-06-01");
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("x-api-key", apiKey);
        }
    }

    private Map<String, Object> convertToClaudeFormat(LlmRequest request) {
        StringBuilder systemContent = new StringBuilder();
        var messages = new ArrayList<Map<String, Object>>();

        if (request.getMessages() != null) {
            for (LlmRequest.Message msg : request.getMessages()) {
                if ("system".equals(msg.getRole())) {
                    systemContent.append(msg.getContent()).append("\n");
                } else {
                    messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
                }
            }
        }

        var claudeBody = new LinkedHashMap<String, Object>();
        claudeBody.put("model", request.getModel());
        if (!systemContent.isEmpty()) {
            claudeBody.put("system", systemContent.toString().trim());
        }
        claudeBody.put("messages", messages);
        claudeBody.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : 4096);
        if (request.getTemperature() != null) {
            claudeBody.put("temperature", request.getTemperature());
        }
        if (request.getTools() != null && !request.getTools().isEmpty()
                && !"none".equals(request.getToolChoice())) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (LlmRequest.FunctionTool tool : request.getTools()) {
                if (tool.getFunction() == null || tool.getFunction().getName() == null) {
                    continue;
                }
                Map<String, Object> claudeTool = new LinkedHashMap<>();
                claudeTool.put("name", tool.getFunction().getName());
                if (tool.getFunction().getDescription() != null) {
                    claudeTool.put("description", tool.getFunction().getDescription());
                }
                claudeTool.put("input_schema", tool.getFunction().getParameters() != null
                        ? tool.getFunction().getParameters() : Map.of("type", "object"));
                tools.add(claudeTool);
            }
            if (!tools.isEmpty()) {
                claudeBody.put("tools", tools);
                if ("required".equals(request.getToolChoice())) {
                    claudeBody.put("tool_choice", Map.of("type", "any"));
                } else if ("auto".equals(request.getToolChoice())) {
                    claudeBody.put("tool_choice", Map.of("type", "auto"));
                }
            }
        }
        return claudeBody;
    }

    /** 将 Anthropic Messages 响应完整转换成网关统一的 OpenAI 风格响应。 */
    private LlmResponse convertFromClaudeFormat(JsonNode claudeResponse) {
        LlmResponse response = new LlmResponse();
        response.setId(text(claudeResponse, "id"));
        response.setObject("chat.completion");
        response.setCreated(System.currentTimeMillis() / 1000);
        response.setModel(text(claudeResponse, "model"));

        StringBuilder content = new StringBuilder();
        List<LlmRequest.ToolCall> toolCalls = new ArrayList<>();
        JsonNode blocks = claudeResponse.path("content");
        if (blocks.isArray()) {
            for (JsonNode block : blocks) {
                if ("text".equals(block.path("type").asText()) && block.has("text")) {
                    content.append(block.path("text").asText());
                } else if ("tool_use".equals(block.path("type").asText())) {
                    LlmRequest.FunctionCall function = new LlmRequest.FunctionCall();
                    function.setName(text(block, "name"));
                    function.setArguments(block.path("input").toString());
                    LlmRequest.ToolCall toolCall = new LlmRequest.ToolCall();
                    toolCall.setId(text(block, "id"));
                    toolCall.setType("function");
                    toolCall.setFunction(function);
                    toolCalls.add(toolCall);
                }
            }
        }

        LlmRequest.Message message = new LlmRequest.Message();
        message.setRole("assistant");
        message.setContent(content.toString());
        if (!toolCalls.isEmpty()) {
            message.setToolCalls(toolCalls);
        }
        LlmResponse.Choice choice = new LlmResponse.Choice();
        choice.setIndex(0);
        choice.setMessage(message);
        choice.setFinishReason(mapFinishReason(text(claudeResponse, "stop_reason")));
        response.setChoices(List.of(choice));

        JsonNode usageNode = claudeResponse.path("usage");
        if (usageNode.isObject()) {
            int inputTokens = usageNode.path("input_tokens").asInt(0);
            int outputTokens = usageNode.path("output_tokens").asInt(0);
            LlmResponse.Usage usage = new LlmResponse.Usage();
            usage.setPromptTokens(inputTokens);
            usage.setCompletionTokens(outputTokens);
            usage.setTotalTokens(inputTokens + outputTokens);
            response.setUsage(usage);
        }
        return response;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String mapFinishReason(String claudeReason) {
        if (claudeReason == null) {
            return null;
        }
        return switch (claudeReason) {
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            case "end_turn", "stop_sequence" -> "stop";
            default -> claudeReason;
        };
    }
}
