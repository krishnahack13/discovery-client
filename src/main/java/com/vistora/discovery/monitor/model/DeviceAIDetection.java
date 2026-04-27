package com.vistora.discovery.monitor.model;

import jakarta.persistence.*;

/**
 * Device Detection Entity - Maps to device_detection table
 * device_id = Primary Key (one row per device)
 * tool_info = JSON array of detected AI tools
 * browser_info = JSON array of detected browser extensions
 */
@Entity
@Table(name = "device_detection")
public class DeviceAIDetection {

    @Id
    @Column(name = "device_id")
    private String deviceId;

    @Column(name = "hostname", nullable = false)
    private String hostname;

    @Column(name = "os")
    private String os;

    // Browser extensions as JSON: [{"browser_type": "Chrome", "extension_id": "...", "extension_name": "...", "category": "...", "last_seen": "..."}]
    @Column(name = "browser_info", columnDefinition = "JSON")
    private String browserInfo;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    // AI Tools as JSON: [{"tool_name": "VS Code", "last_seen": "...", "detected_in": "..."}]
    @Column(name = "tool_info", columnDefinition = "JSON")
    private String toolInfo;

    // Site/Event Info as JSON: {"shadowAiEvents": [], "agenticTools": [], "userIdentity": {}}
    @Column(name = "site_info", columnDefinition = "JSON")
    private String siteInfo;

    public DeviceAIDetection() {}

    public DeviceAIDetection(String deviceId, String hostname, String userEmail) {
        this.deviceId = deviceId;
        this.hostname = hostname;
        this.userEmail = userEmail;
    }

    // Getters and Setters
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }

    public String getOs() { return os; }
    public void setOs(String os) { this.os = os; }

    public String getBrowserInfo() { return browserInfo; }
    public void setBrowserInfo(String browserInfo) { this.browserInfo = browserInfo; }

    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }

    public String getToolInfo() { return toolInfo; }
    public void setToolInfo(String toolInfo) { this.toolInfo = toolInfo; }

    public String getSiteInfo() { return siteInfo; }
    public void setSiteInfo(String siteInfo) { this.siteInfo = siteInfo; }
}
