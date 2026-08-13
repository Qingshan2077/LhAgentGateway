package com.lh.gateway.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 统一的 LLM 调用响应体
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmResponse {
    private String id;
    private String object;
    private Long created;
    private String model;
    private List<Choice> choices;
    private Usage usage;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getObject() { return object; }
    public void setObject(String object) { this.object = object; }
    public Long getCreated() { return created; }
    public void setCreated(Long created) { this.created = created; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public List<Choice> getChoices() { return choices; }
    public void setChoices(List<Choice> choices) { this.choices = choices; }
    public Usage getUsage() { return usage; }
    public void setUsage(Usage usage) { this.usage = usage; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        private Integer index;
        private LlmRequest.Message message;
        @JsonProperty("finish_reason")
        @JsonAlias("finishReason")
        private String finishReason;

        public Integer getIndex() { return index; }
        public void setIndex(Integer index) { this.index = index; }
        public LlmRequest.Message getMessage() { return message; }
        public void setMessage(LlmRequest.Message message) { this.message = message; }
        public String getFinishReason() { return finishReason; }
        public void setFinishReason(String finishReason) { this.finishReason = finishReason; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        @JsonProperty("prompt_tokens")
        @JsonAlias("promptTokens")
        private Integer promptTokens;
        @JsonProperty("completion_tokens")
        @JsonAlias("completionTokens")
        private Integer completionTokens;
        @JsonProperty("total_tokens")
        @JsonAlias("totalTokens")
        private Integer totalTokens;

        public Integer getPromptTokens() { return promptTokens; }
        public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
        public Integer getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
        public Integer getTotalTokens() { return totalTokens; }
        public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
    }
}
