package com.vistora.discovery.monitor.bootstrap;

import com.vistora.discovery.monitor.config.AIToolCatalogProperties;
import com.vistora.discovery.monitor.model.CatalogConfig;
import com.vistora.discovery.monitor.repository.CatalogConfigRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DataInitializer implements CommandLineRunner {

        private final CatalogConfigRepository catalogConfigRepository;
        private final AIToolCatalogProperties configProperties;
        private Long nextId = 1L;

        public DataInitializer(CatalogConfigRepository catalogConfigRepository, AIToolCatalogProperties configProperties) {
                this.catalogConfigRepository = catalogConfigRepository;
                this.configProperties = configProperties;
        }

        @Override
        public void run(String... args) {
                System.out.println("Running DataInitializer - populating catalog_config table...");

                // Get max existing ID to avoid conflicts
                List<CatalogConfig> existing = catalogConfigRepository.findAll();
                if (!existing.isEmpty()) {
                        nextId = existing.stream()
                                        .mapToLong(CatalogConfig::getCatalogId)
                                        .max()
                                        .orElse(0L) + 1;
                }

                // Build set of existing entries to avoid duplicates (without needing DELETE permission)
                java.util.Set<String> existingSignatures = new java.util.HashSet<>();
                for (CatalogConfig cc : existing) {
                        String signature = cc.getToolIdentifier() + ":" + cc.getProcessName() + ":" + cc.getExtensionId() + ":" + cc.getPort() + ":" + cc.getConfigPath();
                        existingSignatures.add(signature);
                }

                for (AIToolCatalogProperties.AIToolConfig configTool : configProperties.getInitialCatalog()) {
                        String toolIdentifier = configTool.getToolName().toLowerCase().replaceAll("\\s+", "_");

                        // Add process names as individual entries (skip if exists)
                        if (configTool.getProcessNames() != null) {
                                for (String processName : configTool.getProcessNames()) {
                                        String signature = toolIdentifier + ":" + processName + ":null:null:null";
                                        if (!existingSignatures.contains(signature)) {
                                                CatalogConfig cc = new CatalogConfig();
                                                cc.setCatalogId(nextId++);
                                                cc.setToolIdentifier(toolIdentifier);
                                                cc.setProcessName(processName);
                                                catalogConfigRepository.save(cc);
                                                existingSignatures.add(signature);
                                        }
                                }
                        }

                        // Add extension IDs as individual entries (skip if exists)
                        if (configTool.getExtensionIds() != null) {
                                for (String extensionId : configTool.getExtensionIds()) {
                                        String signature = toolIdentifier + ":null:" + extensionId + ":null:null";
                                        if (!existingSignatures.contains(signature)) {
                                                CatalogConfig cc = new CatalogConfig();
                                                cc.setCatalogId(nextId++);
                                                cc.setToolIdentifier(toolIdentifier);
                                                cc.setExtensionId(extensionId);
                                                catalogConfigRepository.save(cc);
                                                existingSignatures.add(signature);
                                        }
                                }
                        }

                        // Add known ports as individual entries (skip if exists)
                        if (configTool.getKnownPorts() != null) {
                                for (Integer port : configTool.getKnownPorts()) {
                                        String signature = toolIdentifier + ":null:null:" + port + ":null";
                                        if (!existingSignatures.contains(signature)) {
                                                CatalogConfig cc = new CatalogConfig();
                                                cc.setCatalogId(nextId++);
                                                cc.setToolIdentifier(toolIdentifier);
                                                cc.setPort(port);
                                                catalogConfigRepository.save(cc);
                                                existingSignatures.add(signature);
                                        }
                                }
                        }

                        // Add config paths from configFileSignatures (skip if exists)
                        if (configTool.getConfigFileSignatures() != null) {
                                for (String configPath : configTool.getConfigFileSignatures()) {
                                        String signature = toolIdentifier + ":null:null:null:" + configPath;
                                        if (!existingSignatures.contains(signature)) {
                                                CatalogConfig cc = new CatalogConfig();
                                                cc.setCatalogId(nextId++);
                                                cc.setToolIdentifier(toolIdentifier);
                                                cc.setConfigPath(configPath);
                                                catalogConfigRepository.save(cc);
                                                existingSignatures.add(signature);
                                        }
                                }
                        }
                }

                System.out.println("Synchronized " + catalogConfigRepository.count()
                                + " catalog config entries from configuration.");
        }
}
