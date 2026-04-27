package com.vistora.discovery.monitor.model.dto;

import java.time.LocalDateTime;

/**
 * Unified DTO to represent any detected AI tool, whether found via process, port, or browser extension.
 */
public record DiscoveryResultDTO(
    String deviceId,
    String toolName,
    LocalDateTime lastSeen,
    boolean active,
    String detectedIn,
    String category,
    String userEmail
) {}
