package com.vistora.discovery.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.vistora.discovery.config.JsonNodeConverter;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agents",
        indexes = {
                @Index(name = "idx_agents_is_active", columnList = "is_active"),
                @Index(name = "idx_agents_platform", columnList = "platform"),
                @Index(name = "idx_agents_agent_name", columnList = "agent_name")
        }
)
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String agentId;
    private String agentName;
    private String platform;
    private String configuredSourceId;

    @Convert(converter = JsonNodeConverter.class)
    @Column(columnDefinition = "JSON")
    private JsonNode fetchedAgentPrimaryData;

    @Convert(converter = JsonNodeConverter.class)
    @Column(columnDefinition = "JSON")
    private JsonNode fetchedAgentDetails;

    @Convert(converter = JsonNodeConverter.class)
    @Column(columnDefinition = "JSON")
    private JsonNode platformUpdatedAgentData;

    private Boolean isActive = true;

    private LocalDateTime agentDataUpdatedOn;
    private LocalDateTime detailsDataUpdatedOn;
    private LocalDateTime platformDataUpdatedOn;
    private LocalDateTime detailsLastAccessed;

    private String createdBy;
    private LocalDateTime createdOn;
    private String updatedBy;
    private LocalDateTime updatedOn;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public String getConfiguredSourceId() { return configuredSourceId; }
    public void setConfiguredSourceId(String configuredSourceId) { this.configuredSourceId = configuredSourceId; }

    public JsonNode getFetchedAgentPrimaryData() { return fetchedAgentPrimaryData; }
    public void setFetchedAgentPrimaryData(JsonNode fetchedAgentPrimaryData) { this.fetchedAgentPrimaryData = fetchedAgentPrimaryData; }

    public JsonNode getFetchedAgentDetails() { return fetchedAgentDetails; }
    public void setFetchedAgentDetails(JsonNode fetchedAgentDetails) { this.fetchedAgentDetails = fetchedAgentDetails; }

    public JsonNode getPlatformUpdatedAgentData() { return platformUpdatedAgentData; }
    public void setPlatformUpdatedAgentData(JsonNode platformUpdatedAgentData) { this.platformUpdatedAgentData = platformUpdatedAgentData; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public LocalDateTime getAgentDataUpdatedOn() { return agentDataUpdatedOn; }
    public void setAgentDataUpdatedOn(LocalDateTime agentDataUpdatedOn) { this.agentDataUpdatedOn = agentDataUpdatedOn; }

    public LocalDateTime getDetailsDataUpdatedOn() { return detailsDataUpdatedOn; }
    public void setDetailsDataUpdatedOn(LocalDateTime detailsDataUpdatedOn) { this.detailsDataUpdatedOn = detailsDataUpdatedOn; }

    public LocalDateTime getPlatformDataUpdatedOn() { return platformDataUpdatedOn; }
    public void setPlatformDataUpdatedOn(LocalDateTime platformDataUpdatedOn) { this.platformDataUpdatedOn = platformDataUpdatedOn; }

    public LocalDateTime getDetailsLastAccessed() { return detailsLastAccessed; }
    public void setDetailsLastAccessed(LocalDateTime detailsLastAccessed) { this.detailsLastAccessed = detailsLastAccessed; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedOn() { return createdOn; }
    public void setCreatedOn(LocalDateTime createdOn) { this.createdOn = createdOn; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public LocalDateTime getUpdatedOn() { return updatedOn; }
    public void setUpdatedOn(LocalDateTime updatedOn) { this.updatedOn = updatedOn; }
}
