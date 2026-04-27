package com.vistora.discovery.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vistora.discovery.context.TenantContext;
import com.vistora.discovery.service.JobManagementService;
import com.vistora.discovery.service.UserDetailsService;
import com.vistora.discovery.service.UserFetchService;
import com.vistora.discovery.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@Slf4j
@CrossOrigin(origins = "${app.cors.allowed-origins:http://localhost:4200}")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class AllUsersController {

    private final UserService userService;
    private final UserFetchService fetchService;
    private final UserDetailsService detailsService;
    private final JobManagementService jobManagementService;
    private final ObjectMapper mapper;

    @PostMapping("/list")
    public ResponseEntity<?> fetchUserList(@RequestBody(required = false) String reqBody) {
        int refresh = 0;
        String createdBy = "system";

        if (reqBody != null && !reqBody.isBlank()) {
            try {
                JsonNode req = mapper.readTree(reqBody);
                refresh   = req.path("refresh").asInt(0);
                createdBy = req.path("createdBy").asText("system");
            } catch (Exception e) {
                return ResponseEntity.badRequest().body("{\"error\":\"Invalid JSON\"}");
            }
        }

        if (refresh == 1) {
            jobManagementService.triggerSyncJob("ALL_USERS_SYNC", createdBy);
            return ResponseEntity.ok(mapper.createObjectNode()
                    .put("message", "Users sync job triggered successfully"));
        }

        return ResponseEntity.ok(fetchService.fetchUserList());
    }

    @PostMapping("/details")
    public ResponseEntity<String> fetchDetails(@RequestBody String reqBody) {
        JsonNode req;
        try {
            req = mapper.readTree(reqBody);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("{\"error\":\"Invalid JSON\"}");
        }

        String   email         = req.path("email").asText(null);
        String   platform      = req.path("platform").asText(null);
        String   configuration = req.path("configurationName").asText(null);
        String   tabKey        = req.path("tabKey").asText(null);
        boolean  forceRefresh  = req.path("forceRefresh").asBoolean(false);

        JsonNode params = req.path("params").isMissingNode()
                ? mapper.createObjectNode() : req.get("params");

        JsonNode cachedTimeNode = req.path("cached_time").isMissingNode()
                ? null : req.get("cached_time");

        JsonNode result = detailsService.fetchTabDetails(
                email, platform, configuration, tabKey, params, forceRefresh, cachedTimeNode);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.toString());
    }

    @PostMapping("/fetch-and-save")
    public ResponseEntity<String> fetchAndSaveUsers(
            @RequestBody String reqBody,
            @RequestHeader(value = "X-Tenant-Name", required = false) String tenantName) {

        JsonNode req;
        try {
            req = mapper.readTree(reqBody);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("{\"error\":\"Invalid JSON\"}");
        }

        String runByUser = req.path("runByUser").asText("system");
        String authToken = req.path("authToken").asText(null);

        // Parse platforms array — empty list means sync all
        List<String> platforms = Collections.emptyList();
        JsonNode platformsNode = req.path("platforms");
        if (platformsNode.isArray() && !platformsNode.isEmpty()) {
            platforms = mapper.convertValue(platformsNode,
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        }

        log.info("[USER CONTROLLER] fetch-and-save — runByUser={}, tenantName={}, platforms={}, authToken present={}",
                runByUser, tenantName, platforms.isEmpty() ? "all" : platforms,
                authToken != null && !authToken.isBlank());

        // Set TenantContext on THIS thread — same fix as agent sync
        if (tenantName != null && !tenantName.isBlank()) {
            TenantContext.setTenantInfo(null, tenantName);
            log.info("[USER CONTROLLER] TenantContext set — tenantName={}", tenantName);
        } else {
            log.warn("[USER CONTROLLER] No X-Tenant-Name header — TenantContext will be empty");
        }

        try {
            userService.fetchAndSaveUsers(runByUser, authToken, platforms);
        } finally {
            TenantContext.clear();
        }

        return ResponseEntity.ok("{\"message\":\"Users fetched and saved successfully\"}");
    }

    @PostMapping("/platform-data/update")
    public ResponseEntity<JsonNode> updatePlatformData(@RequestBody ObjectNode req) {
        String   email         = req.path("email").asText(null);
        String   platform      = req.path("platform").asText(null);
        String   configuration = req.path("configurationName").asText(null);
        JsonNode data          = req.path("data");

        JsonNode response = userService.updatePlatformData(email, platform, configuration, data);
        return ResponseEntity.ok(response);
    }
}