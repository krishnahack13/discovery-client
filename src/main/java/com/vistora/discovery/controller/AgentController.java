package com.vistora.discovery.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vistora.discovery.dto.AgentPlatformUpdateRequest;
import com.vistora.discovery.dto.AgentResponse;
import com.vistora.discovery.service.AgentDetailsService;
import com.vistora.discovery.service.AgentFetchService;
import com.vistora.discovery.service.AgentService;
import com.vistora.discovery.service.JobManagementService;
import com.vistora.discovery.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.vistora.discovery.config.JsonNodeConverter.mapper;

@CrossOrigin(origins = "${app.cors.allowed-origins:http://localhost:4200}")
@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agentService;
    private final AgentFetchService fetchService;
    private final AgentDetailsService detailsService;
    private final JobManagementService jobManagementService;

    public AgentController(AgentService agentService, AgentFetchService fetchService, 
                           AgentDetailsService detailsService, JobManagementService jobManagementService) {
        this.agentService = agentService;
        this.fetchService = fetchService;
        this.detailsService = detailsService;
        this.jobManagementService = jobManagementService;
    }

    @PostMapping("/all-agents")
    public ResponseEntity<List<AgentResponse>> getAllAgents() {
        // Direct UI call — no auth token needed for listing from DB
        return ResponseEntity.ok(agentService.getAllAgents(null));
    }

    @PostMapping("/list")
    public ResponseEntity<?> fetchAgentList(@RequestBody(required = false) String reqBody) {
        ObjectMapper mapper = new ObjectMapper();
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
            jobManagementService.triggerSyncJob("AGENT_SYNC", createdBy);
            return ResponseEntity.ok(mapper.createObjectNode()
                    .put("message", "Agent sync job triggered successfully"));
        }

        return ResponseEntity.ok(fetchService.fetchAgentList());
    }

    @PostMapping("/details")
    public ResponseEntity<String> fetchDetails(@RequestBody String reqBody) {
        JsonNode req;
        try {
            req = mapper.readTree(reqBody);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("{\"error\":\"Invalid JSON\"}");
        }

        String  agentId            = req.path("agentId").asText(null);
        String  tabKey             = req.path("tabKey").asText(null);
        String  platform           = req.path("platform").asText(null);
        String  configuredSourceId = req.path("configuredSourceId").asText(null);
        boolean forceRefresh       = req.path("forceRefresh").asBoolean(false);

        JsonNode params = req.path("params").isMissingNode()
                ? mapper.createObjectNode() : req.get("params");

        JsonNode cachedTimeNode = req.path("cached_time").isMissingNode()
                ? null : req.get("cached_time");

        JsonNode result = detailsService.fetchTabDetails(
                agentId, platform, configuredSourceId,
                tabKey, params, forceRefresh, cachedTimeNode);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.toString());
    }

    @PostMapping("/fetch-and-save")
    public ResponseEntity<String> fetchAndSaveAgents(
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

        log.info("[AGENT CONTROLLER] fetch-and-save — runByUser={}, tenantName={}, authToken present={}",
                runByUser, tenantName, authToken != null && !authToken.isBlank());

        // Set TenantContext on THIS thread before calling the service
        // (service call is synchronous — same thread, ThreadLocal is safe)
        if (tenantName != null && !tenantName.isBlank()) {
            TenantContext.setTenantInfo(null, tenantName);
            log.info("[AGENT CONTROLLER] TenantContext set — tenantName={}", tenantName);
        } else {
            log.warn("[AGENT CONTROLLER] No X-Tenant-Name header — TenantContext will be empty");
        }

        try {
            agentService.fetchAndSaveAgents(runByUser, authToken);
        } finally {
            // Always clear — prevents ThreadLocal leak if thread is reused
            TenantContext.clear();
        }

        return ResponseEntity.ok("{\"message\":\"Agents fetched and saved successfully\"}");
    }

    @PostMapping("/platform-data/update")
    public ResponseEntity<Object> updatePlatformData(@RequestBody AgentPlatformUpdateRequest req) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode dataNode   = mapper.valueToTree(req.getData());
        JsonNode response   = agentService.updatePlatformData(req.getAgentId(), dataNode);
        return ResponseEntity.ok(mapper.convertValue(response, Object.class));
    }
}