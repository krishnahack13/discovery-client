package com.vistora.discovery.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.vistora.discovery.config.JsonNodeConverter;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "api_gateways",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_api_gateway",
                        columnNames = {"name", "scan_type", "configured_source_id"})
        },
        indexes = {
                @Index(name = "idx_apigw_scan_type", columnList = "scan_type"),
                @Index(name = "idx_apigw_is_active", columnList = "is_active"),
                @Index(name = "idx_apigw_configured_source", columnList = "configured_source_id")
        }
)
public class ApiGateway {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ================= CORE =================

    @Column(name = "scan_type", nullable = false)
    private String scanType;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private String name;

    @Column(name = "configured_source_id", nullable = false)
    private String configuredSourceId;

    // ================= METRICS =================

    @Column(name = "total_routes")
    private Integer totalRoutes = 0;

    @Column(name = "total_endpoints")
    private Integer totalEndpoints = 0;

    // ================= JSON FIELDS =================

    @Convert(converter = JsonNodeConverter.class)
    @Column(columnDefinition = "JSON")
    private JsonNode routes;     // structured routes

    @Convert(converter = JsonNodeConverter.class)
    @Column(columnDefinition = "JSON")
    private JsonNode details;    // ALL extra info (baseUrl, apiId, auth, etc.)

    // ================= STATE =================

    @Column(name = "is_active")
    private Boolean isActive = true;

    // ================= TIMESTAMPS =================

    @Column(name = "last_accessed")
    private LocalDateTime lastAccessed;

    private String createdBy;

    @Column(name = "created_on")
    private LocalDateTime createdOn;

    private String updatedBy;

    @Column(name = "updated_on")
    private LocalDateTime updatedOn;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getScanType() { return scanType; }
    public void setScanType(String scanType) { this.scanType = scanType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getConfiguredSourceId() { return configuredSourceId; }
    public void setConfiguredSourceId(String configuredSourceId) { this.configuredSourceId = configuredSourceId; }

    public Integer getTotalRoutes() { return totalRoutes; }
    public void setTotalRoutes(Integer totalRoutes) { this.totalRoutes = totalRoutes; }

    public Integer getTotalEndpoints() { return totalEndpoints; }
    public void setTotalEndpoints(Integer totalEndpoints) { this.totalEndpoints = totalEndpoints; }

    public JsonNode getRoutes() { return routes; }
    public void setRoutes(JsonNode routes) { this.routes = routes; }

    public JsonNode getDetails() { return details; }
    public void setDetails(JsonNode details) { this.details = details; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public LocalDateTime getLastAccessed() { return lastAccessed; }
    public void setLastAccessed(LocalDateTime lastAccessed) { this.lastAccessed = lastAccessed; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedOn() { return createdOn; }
    public void setCreatedOn(LocalDateTime createdOn) { this.createdOn = createdOn; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public LocalDateTime getUpdatedOn() { return updatedOn; }
    public void setUpdatedOn(LocalDateTime updatedOn) { this.updatedOn = updatedOn; }
}