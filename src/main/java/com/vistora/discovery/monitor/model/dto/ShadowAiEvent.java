package com.vistora.discovery.monitor.model.dto;

public record ShadowAiEvent(
        String method,
        String url,
        String prompt,
        String model,
        long timestamp,
        int promptTokenEstimate,
        String userId,
        String deviceId,
        String browser,
        int sensitivityScore,
        String actionType,
        String fileName,
        Long fileSize,
        String fileType,
        Integer browserPid,
        String destinationIp
) {}
