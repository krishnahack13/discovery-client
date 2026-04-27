package com.vistora.discovery.monitor.model.dto;

public record AgenticTool(
        String name,
        String usageLevel,
        int totalConversations,
        String lastActive
) {
}
