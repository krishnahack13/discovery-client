package com.vistora.discovery.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vistora.discovery.context.TenantContext;
import com.vistora.discovery.dto.ModelPlatformUpdateRequest;
import com.vistora.discovery.dto.ModelResponse;
import com.vistora.discovery.service.JobManagementService;
import com.vistora.discovery.service.ModelDetailsService;
import com.vistora.discovery.service.ModelFetchService;
import com.vistora.discovery.service.ModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
@Slf4j
@CrossOrigin(origins = "http://localhost:4200")
@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelController {

    private static final String ERROR = "error";
    private final ModelService modelService;
    private final ModelFetchService fetchService;
    private final ModelDetailsService detailsService;
    private final JobManagementService jobManagementService;
    private final ObjectMapper mapper = new ObjectMapper();

    @PostMapping("/all-models")
    public ResponseEntity<List<ModelResponse>> getAllModels() {
        return ResponseEntity.ok(modelService.getAllModels());
    }

    @PostMapping("/list")
    public ResponseEntity<?> fetchModelList(@RequestBody(required = false) String reqBody) {
        int refresh = 0;
        String createdBy = "admin";

        if (reqBody != null && !reqBody.isBlank()) {
            try {
                JsonNode req = mapper.readTree(reqBody);
                refresh = req.path("refresh").asInt(0);
                createdBy = req.path("createdBy").asText("admin");
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(mapper.createObjectNode().put(ERROR, "Invalid JSON"));
            }
        }

        if (refresh == 1) {
            jobManagementService.triggerSyncJob("MODEL_SYNC", createdBy);
            return ResponseEntity.ok(mapper.createObjectNode()
                    .put("message", "Model sync job triggered successfully"));
        }

        return ResponseEntity.ok(fetchService.fetchModelList());
    }

    /**
     * Request body example:
     * {
     *   "modelId": "abc123",
     *   "tabKey": "model-access-control",
     *   "params": { "force": true }
     * }
     */
    @PostMapping("/details")
    public ResponseEntity<Object> fetchDetails(@RequestBody String reqBody) {
        JsonNode req;
        try {
            req = mapper.readTree(reqBody);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(mapper.createObjectNode().put(ERROR, "invalid json"));
        }

        String modelId = req.path("modelId").asText(null);
        String tabKey = req.path("tabKey").asText(null);
        String platform = req.path("platform").asText(null);
        String configuredSourceId = req.path("configuredSourceId").asText(null);
        JsonNode params = req.path("params").isMissingNode() ? null : req.get("params");
        boolean forceRefresh = req.path("forceRefresh").asBoolean(false);

        // NEW: TTL sent from UI
        JsonNode cachedTimeNode = req.path("cached_time").isMissingNode()
                ? null
                : req.get("cached_time");

        if (modelId == null || tabKey == null) {
            return ResponseEntity.badRequest().body(mapper.createObjectNode().put(ERROR, "modelId and tabKey required"));
        }

        JsonNode res = detailsService.fetchTabDetails(modelId, platform, configuredSourceId, tabKey, params, forceRefresh, cachedTimeNode);


        try {
            Object response = mapper.readValue(res.toString(), Object.class);
            return ResponseEntity.status(HttpStatus.OK).body(response);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.OK).body(res);
        }
    }




    @PostMapping("/fetch-and-save")
    public ResponseEntity<String> fetchAndSaveModels(
            @RequestBody String reqBody,
            @RequestHeader(value = "X-Tenant-Name", required = false) String tenantName) {

        JsonNode req;
        try {
            req = mapper.readTree(reqBody);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("{\"" + ERROR + "\":\"Invalid JSON\"}");
        }

        String runByUser = req.path("runByUser").asText("system");
        log.info("runByUser: {}", runByUser);
        // ✅ Extract authToken from request body
        String authToken = req.path("authToken").asText(null);
        log.info("authToken: {}", authToken);

        if (tenantName != null && !tenantName.isBlank()) {
            TenantContext.setTenantInfo(null, tenantName);
            log.info("Tenant context set from header: {}", tenantName);
        } else {
            log.warn("No X-Tenant-Name header present in fetch-and-save request");
        }

        try {
            modelService.fetchAndSaveModels(runByUser, authToken); // ✅ pass authToken
        } finally {
            TenantContext.clear();
        }

        return ResponseEntity.ok("{\"message\":\"Models fetched and saved successfully\"}");
    }

//    @PostMapping("/platform-data/update")
//    public ResponseEntity<JsonNode> updatePlatformData(@RequestBody JsonNode req) {
//        String modelId = req.path("modelId").asText(null);
//        JsonNode data = req.path("data").isMissingNode() ? null : req.get("data");
//
//        if (modelId == null) {
//            return ResponseEntity.badRequest().body(mapper.createObjectNode().put(ERROR, "modelId required"));
//        }
//        JsonNode updated = modelService.updatePlatformData(modelId, data);
//        return ResponseEntity.ok(updated);
//    }

    @PostMapping("/platform-data/update")
    public ResponseEntity<Object> updatePlatformData(@RequestBody ModelPlatformUpdateRequest req) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode dataNode = mapper.valueToTree(req.getData());
        JsonNode response = modelService.updatePlatformData(req.getModelId(), dataNode);
        return ResponseEntity.ok(mapper.convertValue(response, Object.class));
    }
}