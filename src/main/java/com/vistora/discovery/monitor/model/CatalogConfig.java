package com.vistora.discovery.monitor.model;

import jakarta.persistence.*;

/**
 * Catalog Config Entity - Maps to catalog_config table
 * Stores all AI tool catalog data in a single flattened table
 */
@Entity
@Table(name = "catalog_config")
public class CatalogConfig {

    @Id
    @Column(name = "catalog_id")
    private Long catalogId;

    @Column(name = "tool_identifier")
    private String toolIdentifier;

    @Column(name = "config_path")
    private String configPath;

    @Column(name = "extension_id")
    private String extensionId;

    @Column(name = "port")
    private Integer port;

    @Column(name = "process_name")
    private String processName;

    public CatalogConfig() {}

    // Getters and Setters
    public Long getCatalogId() { return catalogId; }
    public void setCatalogId(Long catalogId) { this.catalogId = catalogId; }

    public String getConfigPath() { return configPath; }
    public void setConfigPath(String configPath) { this.configPath = configPath; }

    public String getExtensionId() { return extensionId; }
    public void setExtensionId(String extensionId) { this.extensionId = extensionId; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getProcessName() { return processName; }
    public void setProcessName(String processName) { this.processName = processName; }

    public String getToolIdentifier() { return toolIdentifier; }
    public void setToolIdentifier(String toolIdentifier) { this.toolIdentifier = toolIdentifier; }
}
