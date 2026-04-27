package com.vistora.discovery.dto;

import java.util.Map;

public class AgentPlatformUpdateRequest {
    private String agentId;
    private Map<String, Object> data;   // Key-value overrides

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}

