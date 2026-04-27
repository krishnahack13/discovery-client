package com.vistora.discovery.entity;


import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "connections")
public class ConnectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false, name = "secret_name")
    private String secretName;

    @Column(nullable = false)
    private Boolean verification = false; // default value

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_on")
    private LocalDateTime createdOn;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_on")
    private LocalDateTime updatedOn;

    public ConnectionEntity() {}

    public ConnectionEntity(Long id, String name, String description, String category, String type, 
                            String secretName, Boolean verification, String createdBy, 
                            LocalDateTime createdOn, String updatedBy, LocalDateTime updatedOn) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.category = category;
        this.type = type;
        this.secretName = secretName;
        this.verification = verification;
        this.createdBy = createdBy;
        this.createdOn = createdOn;
        this.updatedBy = updatedBy;
        this.updatedOn = updatedOn;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSecretName() {
        return secretName;
    }

    public void setSecretName(String secretName) {
        this.secretName = secretName;
    }

    public Boolean getVerification() {
        return verification;
    }

    public void setVerification(Boolean verification) {
        this.verification = verification;
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

    public static ConnectionEntityBuilder builder() {
        return new ConnectionEntityBuilder();
    }

    public static class ConnectionEntityBuilder {
        private Long id;
        private String name;
        private String description;
        private String category;
        private String type;
        private String secretName;
        private Boolean verification = false;
        private String createdBy;
        private LocalDateTime createdOn;
        private String updatedBy;
        private LocalDateTime updatedOn;

        public ConnectionEntityBuilder id(Long id) { this.id = id; return this; }
        public ConnectionEntityBuilder name(String name) { this.name = name; return this; }
        public ConnectionEntityBuilder description(String description) { this.description = description; return this; }
        public ConnectionEntityBuilder category(String category) { this.category = category; return this; }
        public ConnectionEntityBuilder type(String type) { this.type = type; return this; }
        public ConnectionEntityBuilder secretName(String secretName) { this.secretName = secretName; return this; }
        public ConnectionEntityBuilder verification(Boolean verification) { this.verification = verification; return this; }
        public ConnectionEntityBuilder createdBy(String createdBy) { this.createdBy = createdBy; return this; }
        public ConnectionEntityBuilder createdOn(LocalDateTime createdOn) { this.createdOn = createdOn; return this; }
        public ConnectionEntityBuilder updatedBy(String updatedBy) { this.updatedBy = updatedBy; return this; }
        public ConnectionEntityBuilder updatedOn(LocalDateTime updatedOn) { this.updatedOn = updatedOn; return this; }

        public ConnectionEntity build() {
            return new ConnectionEntity(id, name, description, category, type, secretName, verification, createdBy, createdOn, updatedBy, updatedOn);
        }
    }
}
