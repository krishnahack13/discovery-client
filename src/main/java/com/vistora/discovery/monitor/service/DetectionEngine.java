package com.vistora.discovery.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vistora.discovery.monitor.config.AIToolCatalogProperties;
import com.vistora.discovery.monitor.model.CatalogConfig;
import com.vistora.discovery.monitor.model.Device;
import com.vistora.discovery.monitor.model.DeviceAIDetection;
import com.vistora.discovery.monitor.model.dto.AgenticTool;
import com.vistora.discovery.monitor.model.dto.DeviceResponse;
import com.vistora.discovery.monitor.model.dto.ShadowAiEvent;
import com.vistora.discovery.monitor.model.dto.UserIdentity;
import com.vistora.discovery.monitor.repository.CatalogConfigRepository;
import com.vistora.discovery.monitor.repository.DeviceAIDetectionRepository;
import com.vistora.discovery.monitor.repository.DeviceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * DetectionEngine - Saves to device_detection table with hostname, os, user_email
 */
@Service
public class DetectionEngine {
    private static final Logger log = LoggerFactory.getLogger(DetectionEngine.class);

    private final CatalogConfigRepository catalogConfigRepository;
    private final DeviceAIDetectionRepository deviceAIDetectionRepository;
    private final DeviceRepository deviceRepository;
    private final AIToolCatalogProperties configProperties;
    private final ObjectMapper objectMapper;

    @Autowired
    public DetectionEngine(CatalogConfigRepository catalogConfigRepository,
            DeviceAIDetectionRepository deviceAIDetectionRepository,
            DeviceRepository deviceRepository,
            AIToolCatalogProperties configProperties,
            ObjectMapper objectMapper) {
        this.catalogConfigRepository = catalogConfigRepository;
        this.deviceAIDetectionRepository = deviceAIDetectionRepository;
        this.deviceRepository = deviceRepository;
        this.configProperties = configProperties;
        this.objectMapper = objectMapper;
    }

    public DetectionReport processResponse(DeviceResponse response) {
        List<CatalogConfig> allConfigs = catalogConfigRepository.findAll();
        List<ProcessInfo> detectedProcesses = new ArrayList<>();
        List<ExtensionInfo> detectedExtensions = new ArrayList<>();

        // Group configs by tool (using catalog_id as proxy for tool)
        java.util.Map<Long, List<CatalogConfig>> configsByTool = allConfigs.stream()
            .collect(java.util.stream.Collectors.groupingBy(CatalogConfig::getCatalogId));

        // Detect AI processes from catalog_config entries
        Set<String> detectedToolKeys = new HashSet<>();
        for (List<CatalogConfig> toolConfigs : configsByTool.values()) {
            DetectionResult result = calculateScoreFromConfigs(response, toolConfigs);
            if (result.score >= 0.5 && !toolConfigs.isEmpty()) {
                String toolName = toolConfigs.get(0).getProcessName() != null ? 
                    toolConfigs.get(0).getProcessName() : "Unknown";
                
                // Deduplicate: normalize name (remove .exe, lowercase) for comparison
                String normalizedKey = toolName.toLowerCase().replace(".exe", "");
                if (detectedToolKeys.contains(normalizedKey)) {
                    continue; // Skip duplicate
                }
                detectedToolKeys.add(normalizedKey);
                
                detectedProcesses.add(new ProcessInfo(
                    toolName,
                    result.detectedIn,
                    determineStandardCategory(null, toolName, result.detectedIn),
                    response.timestamp()
                ));
            }
        }

        // Detect browser extensions from catalog_config entries
        detectedExtensions.addAll(detectBrowserExtensionsFromConfigs(response, allConfigs));

        // Detect IDE extensions (VS Code, Cursor, Windsurf, IntelliJ)
        detectedExtensions.addAll(detectIDEExtensions(response));

        // Build and save to device_detection table
        saveDeviceDetection(response, detectedProcesses, detectedExtensions);

        // Carry through the key telemetry fields into the final report
        List<ShadowAiEvent> shadowAiEvents = response.shadowAiEvents() != null
                ? response.shadowAiEvents() : Collections.emptyList();
        List<AgenticTool> agenticTools = response.agenticTools() != null
                ? response.agenticTools() : Collections.emptyList();
        UserIdentity userIdentity = response.userIdentity();

        return new DetectionReport(
            response.deviceId(),
            detectedProcesses.stream().map(p -> p.toolName + " (" + p.detectedIn + ")").toList(),
            detectedExtensions.stream().map(e -> e.extensionName + " [" + e.browserType + "]").toList(),
            shadowAiEvents,
            agenticTools,
            userIdentity
        );
    }

    private void saveDeviceDetection(DeviceResponse response, List<ProcessInfo> processes, 
                                     List<ExtensionInfo> extensions) {
        try {
            // Get device info from device table
            Optional<Device> deviceOpt = deviceRepository.findById(response.deviceId());
            String hostname = deviceOpt.map(Device::getHostname).orElse("unknown");
            String os = deviceOpt.map(Device::getOs).orElse("unknown");
            String userEmail = deviceOpt.map(Device::getUserEmail).orElse("unknown@local");

            // Build tool_info JSON array
            ArrayNode toolsArray = objectMapper.createArrayNode();
            for (ProcessInfo p : processes) {
                ObjectNode node = toolsArray.addObject();
                node.put("tool_name", p.toolName);
                node.put("detected_in", p.detectedIn);
                node.put("category", p.category);
                node.put("last_seen", p.lastSeen.toString());
            }

            // Build browser_info JSON array
            ArrayNode browsersArray = objectMapper.createArrayNode();
            for (ExtensionInfo e : extensions) {
                ObjectNode node = browsersArray.addObject();
                node.put("browser_type", e.browserType);
                node.put("extension_id", e.extensionId);
                node.put("extension_name", e.extensionName);
                node.put("category", e.category);
                node.put("last_seen", e.lastSeen.toString());
            }

            // Get or create device detection record
            Optional<DeviceAIDetection> existing = deviceAIDetectionRepository.findById(response.deviceId());
            DeviceAIDetection detection;
            if (existing.isPresent()) {
                detection = existing.get();
            } else {
                detection = new DeviceAIDetection(response.deviceId(), hostname, userEmail);
                detection.setOs(os);
            }

            // Update fields
            detection.setHostname(hostname);
            detection.setUserEmail(userEmail);
            detection.setToolInfo(toolsArray.toString());
            detection.setBrowserInfo(browsersArray.toString());

            // Build site_info JSON object
            ObjectNode siteInfoNode = objectMapper.createObjectNode();
            siteInfoNode.set("shadowAiEvents", objectMapper.valueToTree(
                response.shadowAiEvents() != null ? response.shadowAiEvents() : Collections.emptyList()));
            siteInfoNode.set("agenticTools", objectMapper.valueToTree(
                response.agenticTools() != null ? response.agenticTools() : Collections.emptyList()));
            siteInfoNode.set("userIdentity", objectMapper.valueToTree(response.userIdentity()));
            
            String status = response.agentStatus() != null ? response.agentStatus() : "UNKNOWN";
            siteInfoNode.put("agentStatus", status);
            if ("MANUALLY_DISABLED".equals(status) && response.timestamp() != null) {
                siteInfoNode.put("disabledAt", response.timestamp().toString());
            }

            detection.setSiteInfo(siteInfoNode.toString());

            deviceAIDetectionRepository.save(detection);
            log.info("Saved device_detection for {}: {} tools, {} extensions", 
                response.deviceId(), processes.size(), extensions.size());

        } catch (Exception e) {
            log.error("Failed to save device detection for {}: {}", response.deviceId(), e.getMessage());
        }
    }

    public record DetectionReport(
            String deviceId,
            List<String> activeTools,
            List<String> activeExtensions,
            List<ShadowAiEvent> shadowAiEvents,
            List<AgenticTool> agenticTools,
            UserIdentity userIdentity
    ) {}
    private record ProcessInfo(String toolName, String detectedIn, String category, LocalDateTime lastSeen) {}
    private record ExtensionInfo(String extensionId, String extensionName, String browserType, String category, LocalDateTime lastSeen) {}

    private static class DetectionResult {
        double score = 0;
        String detectedIn = "";
    }

    private DetectionResult calculateScoreFromConfigs(DeviceResponse response, List<CatalogConfig> configs) {
        DetectionResult result = new DetectionResult();
        
        // Collect all signatures from configs
        List<String> processNames = configs.stream()
            .map(CatalogConfig::getProcessName)
            .filter(p -> p != null && !p.isEmpty())
            .toList();
        List<String> extensionIds = configs.stream()
            .map(CatalogConfig::getExtensionId)
            .filter(e -> e != null && !e.isEmpty())
            .toList();
        List<Integer> ports = configs.stream()
            .map(CatalogConfig::getPort)
            .filter(p -> p != null)
            .toList();
        List<String> configPaths = configs.stream()
            .map(CatalogConfig::getConfigPath)
            .filter(p -> p != null && !p.isEmpty())
            .toList();

        // 1. Process Check
        boolean hasActiveProcess = false;
        if (!processNames.isEmpty() && response.runningProcesses() != null) {
            for (var proc : response.runningProcesses()) {
                if (proc.name() == null) continue;
                String procName = proc.name().toLowerCase();
                for (String sig : processNames) {
                    if (procName.contains(sig.toLowerCase())) {
                        result.score += 1.0;
                        result.detectedIn = "Process List";
                        hasActiveProcess = true;
                        break;
                    }
                }
                if (hasActiveProcess) break;
            }
        }

        // 2. VS Code Extension Check
        boolean hasActiveVSCodeExt = false;
        if (!extensionIds.isEmpty() && response.vscodeExtensions() != null) {
            for (var ext : response.vscodeExtensions()) {
                if (ext.id() == null) continue;
                for (String sig : extensionIds) {
                    if (ext.id().equalsIgnoreCase(sig) || ext.id().toLowerCase().contains(sig.toLowerCase())) {
                        result.score += 1.0;
                        result.detectedIn = "VS Code Extension";
                        hasActiveVSCodeExt = true;
                        break;
                    }
                }
                if (hasActiveVSCodeExt) break;
            }
        }

        // 3. Network Port Check
        boolean hasActivePort = false;
        if (!ports.isEmpty() && response.openPorts() != null) {
            hasActivePort = response.openPorts().stream()
                    .anyMatch(port -> ports.contains(port));
            if (hasActivePort) {
                result.score += 0.5;
                if (result.detectedIn.isEmpty()) {
                    result.detectedIn = "Network Port Scan";
                }
            }
        }

        // 4. Config Path Check
        boolean hasActiveConfigFile = false;
        if (!configPaths.isEmpty() && response.aiConfigDirectories() != null) {
            for (var config : response.aiConfigDirectories()) {
                boolean matchesSignature = configPaths.stream()
                        .anyMatch(sig -> config.path().toLowerCase().contains(sig.toLowerCase()));
                
                if (matchesSignature && config.lastModified() != null) {
                    long minutesSinceModified = java.time.Duration.between(config.lastModified(), java.time.LocalDateTime.now()).toMinutes();
                    if (minutesSinceModified <= 60) {
                        hasActiveConfigFile = true;
                        result.score += 0.5;
                        if (result.detectedIn.isEmpty()) {
                            result.detectedIn = "Recent Configuration Activity";
                        }
                        break;
                    }
                }
            }
        }

        // Cleanup
        if (!hasActiveProcess && !hasActiveVSCodeExt && !hasActivePort && !hasActiveConfigFile && result.score < 0.5) {
            result.score = 0.0;
        }

        result.score = Math.min(result.score, 1.0);
        return result;
    }

    private String determineStandardCategory(String catalogCategory, String toolName, String detectedIn) {
        // Priority 1: Use catalog category if it matches our standard patterns
        if (catalogCategory != null && !catalogCategory.trim().isEmpty()) {
            String lowerCat = catalogCategory.toLowerCase();
            if (lowerCat.contains("ide")) return "IDE Tool";
            if (lowerCat.contains("extension")) return "Browser Extension";
            if (lowerCat.contains("browser")) return "Browser ✅";
            if (lowerCat.contains("cli") || lowerCat.contains("dev")) return "Developer & CLI";
        }

        // Priority 2: Heuristic detection based on Name or Source
        String lowerName = toolName != null ? toolName.toLowerCase() : "";
        String lowerDetectedIn = detectedIn != null ? detectedIn.toLowerCase() : "";

        // Specifically catch Browser processes
        if (lowerName.contains("chrome") || lowerName.contains("edge") || lowerName.contains("chromium")) {
            return "Browser ";
        }

        // Catch IDE Plugins
        if (lowerDetectedIn.contains("vs code") || lowerDetectedIn.contains("intellij") || lowerDetectedIn.contains("ide")) {
            return "IDE Tool";
        }

        // Catch Browser Extensions
        if (lowerDetectedIn.contains("extension")) {
            return "Browser Extension";
        }

        // Catch Backend/CLI/General Tools
        if (lowerDetectedIn.contains("process list") || lowerDetectedIn.contains("cli") || lowerName.contains("cli")) {
            return "Developer & CLI";
        }

        // Fallback: If catalog had something, use it, otherwise use a safe default
        return (catalogCategory != null && !catalogCategory.isEmpty()) ? catalogCategory : "Developer & CLI";
    }

    private List<ExtensionInfo> detectBrowserExtensionsFromConfigs(DeviceResponse response, List<CatalogConfig> configs) {
        List<ExtensionInfo> detected = new ArrayList<>();
        if (response.browserExtensions() == null) {
            System.out.println("[DEBUG] No browser extensions in response");
            return detected;
        }

        System.out.println("[DEBUG] Received " + response.browserExtensions().size() + " browser extensions");

        // Collect all extension IDs from configs
        List<String> extensionIds = configs.stream()
            .map(CatalogConfig::getExtensionId)
            .filter(e -> e != null && !e.isEmpty())
            .toList();

        System.out.println("[DEBUG] Catalog has " + extensionIds.size() + " extension IDs: " + extensionIds);
        System.out.println("[DEBUG] AI keywords: " + configProperties.getAiKeywords());

        for (var ext : response.browserExtensions()) {
            System.out.println("[DEBUG] Checking extension: " + ext.name() + " (ID: " + ext.id() + ", Browser: " + ext.browser() + ", LastActive: " + ext.lastActive() + ")");

            if (ext.lastActive() == null) {
                System.out.println("[DEBUG]   -> Skipped: lastActive is null");
                continue;
            }

            long daysSinceActive = ChronoUnit.DAYS.between(ext.lastActive(), LocalDateTime.now());
            if (daysSinceActive > configProperties.getBrowserExtensionActivityThresholdDays()) {
                System.out.println("[DEBUG]   -> Skipped: inactive for " + daysSinceActive + " days");
                continue;
            }

            boolean isKnownCatalog = extensionIds.stream()
                    .anyMatch(cid -> {
                        if (cid.length() == 32 && cid.matches("^[a-p]{32}$")) {
                            return ext.id().equalsIgnoreCase(cid);
                        }
                        return ext.id().toLowerCase().contains(cid.toLowerCase());
                    });

            String nameLower = ext.name().toLowerCase();
            boolean isAiRelated = configProperties.getAiKeywords().stream()
                    .anyMatch(keyword -> nameLower.contains(keyword.toLowerCase()));

            System.out.println("[DEBUG]   -> isKnownCatalog: " + isKnownCatalog + ", isAiRelated: " + isAiRelated);

            if (isKnownCatalog || isAiRelated) {
                System.out.println("[DEBUG]   -> DETECTED!");
                detected.add(new ExtensionInfo(
                    ext.id(),
                    ext.name(),
                    ext.browser(),
                    "Browser Extension",
                    LocalDateTime.now()
                ));
            }
        }
        System.out.println("[DEBUG] Total browser extensions detected: " + detected.size());
        return detected;
    }

    private List<ExtensionInfo> detectIDEExtensions(DeviceResponse response) {
        List<ExtensionInfo> detected = new ArrayList<>();
        if (response.vscodeExtensions() == null || response.vscodeExtensions().isEmpty()) {
            return detected;
        }

        List<String> aiKeywords = configProperties.getAiKeywords();

        for (var ext : response.vscodeExtensions()) {
            String extName = ext.id() != null ? ext.id().toLowerCase() : "";
            String category = ext.category() != null ? ext.category().toLowerCase() : "";

            // Check if extension name contains any AI keyword
            boolean isAiRelated = aiKeywords.stream()
                    .anyMatch(keyword -> extName.contains(keyword.toLowerCase()));

            // Also check category if it mentions AI/ML
            boolean isAiCategory = category.contains("ai") || category.contains("ml") ||
                                   category.contains("llm") || category.contains("copilot") ||
                                   category.contains("assistant") || category.contains("agent");

            if (isAiRelated || isAiCategory) {
                String displayName = ext.id() != null ? ext.id() : "unknown-extension";
                String ideSource = ext.source() != null ? ext.source() : "Unknown IDE";
                detected.add(new ExtensionInfo(
                    ext.id(),
                    displayName,
                    ideSource,
                    "IDE Tool",
                    LocalDateTime.now()
                ));
            }
        }

        log.info("Detected {} AI IDE extensions from {}", detected.size(), response.deviceId());
        return detected;
    }
}
