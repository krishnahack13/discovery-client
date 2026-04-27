package com.vistora.discovery.monitor.model.dto;

import java.time.LocalDateTime;
import java.util.List;

public record DeviceResponse(
        String deviceId,
        String hostname,
        String os,
        String osVersion,
        String agentVersion,
        LocalDateTime timestamp,
        List<ProcessInfo> runningProcesses,
        List<InstalledApp> installedApplications,
        List<VsCodeExtension> vscodeExtensions,
        List<BrowserExtension> browserExtensions,
        List<ConfigFileInfo> aiConfigDirectories,
        List<Integer> openPorts,
        List<String> environmentVariables,
        List<ShadowAiEvent> shadowAiEvents,
        List<AgenticTool> agenticTools,
        UserIdentity userIdentity,
        String agentStatus) {
}
