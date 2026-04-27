package com.vistora.discovery.monitor.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vistora.discovery.monitor.model.dto.BrowserExtension;
import com.vistora.discovery.monitor.model.dto.ProcessInfo;
import com.vistora.discovery.monitor.model.dto.ShadowAiEvent;
import com.vistora.discovery.monitor.model.dto.VsCodeExtension;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class PrivacyFilter {

    public static class PrivacyConfig {
        public List<String> ignore_processes = Collections.emptyList();
        public List<String> ignore_domains = Collections.emptyList();
        public List<String> ignore_extensions = Collections.emptyList();
    }

    private PrivacyConfig config;

    public PrivacyFilter() {
        this.config = loadConfig();
    }

    private PrivacyConfig loadConfig() {
        try {
            Path configPath = Paths.get(System.getProperty("user.dir"), "agent-privacy.json");
            File file = configPath.toFile();
            if (file.exists() && file.isFile()) {
                System.out.println("[PrivacyFilter] Found agent-privacy.json. Applying opt-out rules.");
                return new ObjectMapper().readValue(file, PrivacyConfig.class);
            }
        } catch (Exception e) {
            System.err.println("[PrivacyFilter] Error reading agent-privacy.json: " + e.getMessage());
        }
        return new PrivacyConfig(); // Default empty config
    }

    public List<ProcessInfo> filterProcesses(List<ProcessInfo> processes) {
        if (config.ignore_processes.isEmpty()) return processes;
        
        return processes.stream()
                .filter(p -> {
                    String name = p.name() != null ? p.name().toLowerCase() : "";
                    return config.ignore_processes.stream().noneMatch(ignored -> name.contains(ignored.toLowerCase()));
                })
                .collect(Collectors.toList());
    }

    public List<ShadowAiEvent> filterShadowAi(List<ShadowAiEvent> events) {
        if (config.ignore_domains.isEmpty()) return events;

        return events.stream()
                .filter(e -> {
                    String url = e.url() != null ? e.url().toLowerCase() : "";
                    return config.ignore_domains.stream().noneMatch(ignored -> url.contains(ignored.toLowerCase()));
                })
                .collect(Collectors.toList());
    }

    public List<BrowserExtension> filterBrowserExtensions(List<BrowserExtension> extensions) {
        if (config.ignore_extensions.isEmpty()) return extensions;

        return extensions.stream()
                .filter(e -> {
                    String name = e.name() != null ? e.name().toLowerCase() : "";
                    String id = e.id() != null ? e.id().toLowerCase() : "";
                    return config.ignore_extensions.stream().noneMatch(ignored -> 
                        name.contains(ignored.toLowerCase()) || id.contains(ignored.toLowerCase())
                    );
                })
                .collect(Collectors.toList());
    }

    public List<VsCodeExtension> filterVsCodeExtensions(List<VsCodeExtension> extensions) {
        if (config.ignore_extensions.isEmpty()) return extensions;

        return extensions.stream()
                .filter(e -> {
                    String id = e.id() != null ? e.id().toLowerCase() : "";
                    return config.ignore_extensions.stream().noneMatch(ignored -> id.contains(ignored.toLowerCase()));
                })
                .collect(Collectors.toList());
    }
}
