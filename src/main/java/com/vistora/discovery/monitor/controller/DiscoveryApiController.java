package com.vistora.discovery.monitor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vistora.discovery.monitor.model.DeviceAIDetection;
import com.vistora.discovery.monitor.service.ReportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vistora.discovery.monitor.model.dto.DiscoveryResultDTO;
import java.util.ArrayList;
import java.util.List;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/v1/endpoints")
public class DiscoveryApiController {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryApiController.class);
    private final ReportService reportService;
    private final ObjectMapper objectMapper;

    public DiscoveryApiController(ReportService reportService, ObjectMapper objectMapper) {
        this.reportService = reportService;
        this.objectMapper = objectMapper;
    }

    @PostMapping({"/list", "/index"})
    public List<DiscoveryResultDTO> listAgents() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(1);
        List<DiscoveryResultDTO> unifiedResults = new ArrayList<>();

        // Get all devices with AI detections (one row per device with JSON)
        List<DeviceAIDetection> devices = reportService.getAllDetections();
        log.info("Total devices with AI detections: {}", devices.size());

        for (DeviceAIDetection device : devices) {
            String deviceId = device.getDeviceId();
            String userEmail = device.getUserEmail();
            
            // Parse tool_info JSON
            if (device.getToolInfo() != null && !device.getToolInfo().isEmpty()) {
                try {
                    JsonNode processes = objectMapper.readTree(device.getToolInfo());
                    for (JsonNode process : processes) {
                        String toolCategory = process.has("category") ? process.get("category").asText() : "Unknown";
                        unifiedResults.add(new DiscoveryResultDTO(
                            deviceId,
                            process.get("tool_name").asText(),
                            parseTimestamp(process.get("last_seen")),
                            isActive(parseTimestamp(process.get("last_seen")), threshold),
                            process.get("detected_in").asText(),
                            toolCategory,
                            userEmail
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse tool_info JSON for device {}: {}", deviceId, e.getMessage());
                }
            }

            // Parse browser_info JSON
            if (device.getBrowserInfo() != null && !device.getBrowserInfo().isEmpty()) {
                try {
                    JsonNode extensions = objectMapper.readTree(device.getBrowserInfo());
                    for (JsonNode ext : extensions) {
                        String browserType = ext.has("browser_type") ? ext.get("browser_type").asText() : "Unknown";
                        String extCategory = ext.has("category") ? ext.get("category").asText() : "Browser Extension";
                        unifiedResults.add(new DiscoveryResultDTO(
                            deviceId,
                            ext.get("extension_name").asText(),
                            parseTimestamp(ext.get("last_seen")),
                            isActive(parseTimestamp(ext.get("last_seen")), threshold),
                            browserType + " Extension",
                            extCategory,
                            userEmail
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse browser_info JSON for device {}: {}", deviceId, e.getMessage());
                }
            }
        }

        return unifiedResults;
    }

    private LocalDateTime parseTimestamp(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            return LocalDateTime.parse(node.asText());
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isActive(LocalDateTime lastSeen, LocalDateTime threshold) {
        return lastSeen != null && lastSeen.isAfter(threshold);
    }
}
