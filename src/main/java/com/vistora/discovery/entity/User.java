package com.vistora.discovery.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.vistora.discovery.config.JsonNodeConverter;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "users",
        indexes = {
                @Index(name = "idx_users_is_active", columnList = "is_active"),
                @Index(name = "idx_users_platform", columnList = "platform"),
                @Index(name = "idx_users_email", columnList = "email")
        })
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;
    private String userName;
    private String platform;
    private String configuredSourceId;

    @Convert(converter = JsonNodeConverter.class)
    @Column(columnDefinition = "JSON")
    private JsonNode fetchedUserPrimaryData;

    @Convert(converter = JsonNodeConverter.class)
    @Column(columnDefinition = "JSON")
    private JsonNode fetchedUserDetails;

    @Convert(converter = JsonNodeConverter.class)
    @Column(columnDefinition = "JSON")
    private JsonNode platformUpdatedUserData;

    private Boolean isActive = true;

    private LocalDateTime userDataUpdatedOn;
    private LocalDateTime detailsDataUpdatedOn;
    private LocalDateTime platformDataUpdatedOn;
    private LocalDateTime detailsLastAccessed;

    private String createdBy;
    private LocalDateTime createdOn;
    private String updatedBy;
    private LocalDateTime updatedOn;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getConfiguredSourceId() {
        return configuredSourceId;
    }

    public void setConfiguredSourceId(String configuredSourceId) {
        this.configuredSourceId = configuredSourceId;
    }

    public JsonNode getFetchedUserPrimaryData() {
        return fetchedUserPrimaryData;
    }

    public void setFetchedUserPrimaryData(JsonNode fetchedUserPrimaryData) {
        this.fetchedUserPrimaryData = fetchedUserPrimaryData;
    }

    public JsonNode getFetchedUserDetails() {
        return fetchedUserDetails;
    }

    public void setFetchedUserDetails(JsonNode fetchedUserDetails) {
        this.fetchedUserDetails = fetchedUserDetails;
    }

    public JsonNode getPlatformUpdatedUserData() {
        return platformUpdatedUserData;
    }

    public void setPlatformUpdatedUserData(JsonNode platformUpdatedUserData) {
        this.platformUpdatedUserData = platformUpdatedUserData;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public LocalDateTime getUserDataUpdatedOn() {
        return userDataUpdatedOn;
    }

    public void setUserDataUpdatedOn(LocalDateTime userDataUpdatedOn) {
        this.userDataUpdatedOn = userDataUpdatedOn;
    }

    public LocalDateTime getDetailsDataUpdatedOn() {
        return detailsDataUpdatedOn;
    }

    public void setDetailsDataUpdatedOn(LocalDateTime detailsDataUpdatedOn) {
        this.detailsDataUpdatedOn = detailsDataUpdatedOn;
    }

    public LocalDateTime getPlatformDataUpdatedOn() {
        return platformDataUpdatedOn;
    }

    public void setPlatformDataUpdatedOn(LocalDateTime platformDataUpdatedOn) {
        this.platformDataUpdatedOn = platformDataUpdatedOn;
    }

    public LocalDateTime getDetailsLastAccessed() {
        return detailsLastAccessed;
    }

    public void setDetailsLastAccessed(LocalDateTime detailsLastAccessed) {
        this.detailsLastAccessed = detailsLastAccessed;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(LocalDateTime createdOn) {
        this.createdOn = createdOn;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public LocalDateTime getUpdatedOn() {
        return updatedOn;
    }

    public void setUpdatedOn(LocalDateTime updatedOn) {
        this.updatedOn = updatedOn;
    }
}
