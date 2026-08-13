package com.lh.gateway.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 统一的 LLM 调用请求体
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmRequest {
    private String model;
    private List<Message> messages;
    private Double temperature;
    @JsonProperty("max_tokens")
    @JsonAlias("maxTokens")
    private Integer maxTokens;
    private Boolean stream;
    private String user;
    private List<FunctionTool> tools;
    @JsonProperty("tool_choice")
    @JsonAlias("toolChoice")
    private Object toolChoice;

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public List<Message> getMessages() { return messages; }
    public void setMessages(List<Message> messages) { this.messages = messages; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public Boolean getStream() { return stream; }
    public void setStream(Boolean stream) { this.stream = stream; }
    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }
    public List<FunctionTool> getTools() { return tools; }
    public void setTools(List<FunctionTool> tools) { this.tools = tools; }
    public Object getToolChoice() { return toolChoice; }
    public void setToolChoice(Object toolChoice) { this.toolChoice = toolChoice; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String role;
        private String content;
        @JsonProperty("tool_calls")
        @JsonAlias("toolCalls")
        private List<ToolCall> toolCalls;

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public List<ToolCall> getToolCalls() { return toolCalls; }
        public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolCall {
        private String id;
        private String type;
        private FunctionCall function;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public FunctionCall getFunction() { return function; }
        public void setFunction(FunctionCall function) { this.function = function; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionCall {
        private String name;
        private String arguments;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getArguments() { return arguments; }
        public void setArguments(String arguments) { this.arguments = arguments; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionTool {
        private String type;
        private Function function;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Function getFunction() { return function; }
        public void setFunction(Function function) { this.function = function; }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Function {
            private String name;
            private String description;
            private Map<String, Object> parameters;

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getDescription() { return description; }
            public void setDescription(String description) { this.description = description; }
            public Map<String, Object> getParameters() { return parameters; }
            public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
        }
    }
}
