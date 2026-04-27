package com.vistora.discovery.monitor.controller;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vistora.discovery.monitor.model.CatalogConfig;
import com.vistora.discovery.monitor.model.Device;
import com.vistora.discovery.monitor.model.Response;
import com.vistora.discovery.monitor.model.dto.DeviceResponse;
import com.vistora.discovery.monitor.repository.CatalogConfigRepository;
import com.vistora.discovery.monitor.repository.DeviceRepository;
import com.vistora.discovery.monitor.repository.ResponseRepository;
import com.vistora.discovery.monitor.service.DetectionEngine;

@RestController
@RequestMapping("/agent")
public class ResponseIngestionController {

    private final DeviceRepository deviceRepository;
    private final ResponseRepository responseRepository;
    private final DetectionEngine detectionEngine;
    private final ObjectMapper objectMapper;
    private final S3Client s3Client;
    private final CatalogConfigRepository catalogConfigRepository;
    private final RestTemplate restTemplate;
    private final String orgAwsAccessKey;
    private final String orgAwsSecretKey;

    @Value("${risk.score.service.url:http://localhost:8000}")
    private String riskScoreServiceUrl;

    // AWS S3 Configuration for Backend ORG (Placeholders to be replaced by Backend Infrastructure Team)
    private static final String ORG_AWS_REGION = "us-west-1"; 
    private static final String ORG_S3_BUCKET_NAME = "qa-vectordb";

    // In-memory command queue for on-demand pull
    private static final Map<String, List<String>> commandQueue = new java.util.concurrent.ConcurrentHashMap<>();

    public ResponseIngestionController(DeviceRepository deviceRepository,
            ResponseRepository responseRepository,
            DetectionEngine detectionEngine,
            ObjectMapper objectMapper,
            CatalogConfigRepository catalogConfigRepository,
            @Value("${org.aws.access-key-id:YOUR_ORG_ACCESS_KEY}") String orgAwsAccessKey,
            @Value("${org.aws.secret-access-key:YOUR_ORG_SECRET_KEY}") String orgAwsSecretKey) {
        this.deviceRepository = deviceRepository;
        this.responseRepository = responseRepository;
        this.detectionEngine = detectionEngine;
        this.objectMapper = objectMapper;
        this.catalogConfigRepository = catalogConfigRepository;
        this.orgAwsAccessKey = orgAwsAccessKey;
        this.orgAwsSecretKey = orgAwsSecretKey;
        this.s3Client = S3Client.builder()
                .region(Region.of(ORG_AWS_REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(orgAwsAccessKey, orgAwsSecretKey)))
                .build();
        this.restTemplate = new RestTemplate();
    }

    private Map<String, Object> callRiskScoreService(String bucket, String prefix, String accessKey, String secretKey) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("bucket", bucket);
            requestBody.put("prefix", prefix);
            requestBody.put("aws_access_key_id", accessKey);
            requestBody.put("aws_secret_access_key", secretKey);
            requestBody.put("aws_region", ORG_AWS_REGION);

            HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    riskScoreServiceUrl + "/scan",
                    request,
                    Map.class);

            return response;
        } catch (Exception e) {
            System.err.println("WARNING: Risk score service unavailable: " + e.getMessage());
            return null;
        }
    }

    @PostMapping("/info")
    public ResponseEntity<String> receiveResponse(@RequestBody DeviceResponse response) {
        String rawJson = null;
        try {
            rawJson = objectMapper.writeValueAsString(response);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return ResponseEntity.status(500).body("Error processing JSON: " + e.getMessage());
        }
        
        // Try database operations, but don't fail if DB is unreachable
        try {
            // 1. Update/Create Device
            Device device = deviceRepository.findById(response.deviceId())
                    .orElse(new Device());
            device.setId(response.deviceId());
            device.setHostname(response.hostname());
            device.setOs(response.os());
            device.setLastSeenAt(LocalDateTime.now());
            
            if (response.userIdentity() != null) {
                device.setUserEmail(response.userIdentity().email());
                device.setTenantName(response.userIdentity().tenantName());
            }
            
            deviceRepository.save(device);

            // 2. Store Raw Response in Database
            Response responseEntity = new Response();
            responseEntity.setDeviceId(response.deviceId());
            responseEntity.setTimestamp(response.timestamp());
            responseEntity.setRawJson(rawJson);
            responseRepository.save(responseEntity);
        } catch (Exception dbException) {
            // Log but continue - DB is optional, S3 is the source of truth
            System.err.println("WARNING: Database unavailable, skipping DB persistence: " + dbException.getMessage());
        }
        
        // Continue with S3 operations even if DB failed
        try {
            // Backup the processed outcome physically to the Organization's specific AWS Bucket!
            String fileName = "final_output_" + response.deviceId() + "_" + System.currentTimeMillis() + ".json";
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(ORG_S3_BUCKET_NAME)
                    .key("collector_data/" + fileName)
                    .contentType("application/json")
                    .build();
            s3Client.putObject(putObjectRequest, software.amazon.awssdk.core.sync.RequestBody.fromString(rawJson));

            // 3. Trigger Detection & Generate Filtered Report
            DetectionEngine.DetectionReport report = detectionEngine.processResponse(response);

            // 4. Upload FINAL Filtered Results (Only Active AI Agents) to new S3 Path
            String finalFileName = "active_ai_agents_" + response.deviceId() + "_" + System.currentTimeMillis() + ".json";
            String filteredJson = objectMapper.writeValueAsString(report);
            
            PutObjectRequest finalPutRequest = PutObjectRequest.builder()
                    .bucket(ORG_S3_BUCKET_NAME)
                    .key("processed_discovery_results/" + finalFileName)
                    .contentType("application/json")
                    .build();
            
            s3Client.putObject(finalPutRequest, software.amazon.awssdk.core.sync.RequestBody.fromString(filteredJson));

            // 5. Call Risk Score Service to get risk scores for detected AI tools
            Map<String, Object> riskScoreResult = callRiskScoreService(
                    ORG_S3_BUCKET_NAME,
                    "collector_data/",
                    orgAwsAccessKey,
                    orgAwsSecretKey
            );

            if (riskScoreResult != null) {
                System.out.println("Risk scores calculated: " + riskScoreResult.get("tool_aggregate"));
            }

            return ResponseEntity.ok("Response processed, AI report vaulted to: processed_discovery_results/, " +
                    "Risk scores: " + (riskScoreResult != null ? "calculated" : "unavailable"));
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            return ResponseEntity.status(500).body("Error backing up to S3: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Unexpected error processing response: " + e.getMessage());
        }
    }

    @GetMapping("/catalog")
    public List<CatalogConfig> getCatalog() {
        return catalogConfigRepository.findAll();
    }

    @PostMapping("/active")
    public ResponseEntity<Iterable<Device>> getActiveAgents() {
        return ResponseEntity.ok(deviceRepository.findAll());
    }

    @GetMapping("/commands")
    public ResponseEntity<List<String>> pollCommands(@RequestParam String deviceId) {
        List<String> commands = commandQueue.getOrDefault(deviceId, Collections.emptyList());
        commandQueue.remove(deviceId); // Clear after polling
        return ResponseEntity.ok(commands);
    }

    @PostMapping("/used")
    public ResponseEntity<String> triggerResponse(@RequestBody java.util.Map<String, String> payload) {
        String deviceId = payload.get("deviceId");
        if (deviceId == null || deviceId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Error: 'deviceId' is required in the JSON body");
        }
        commandQueue.computeIfAbsent(deviceId, k -> new java.util.ArrayList<>()).add("COLLECT_RESPONSE");
        return ResponseEntity.ok("Trigger command queued for device: " + deviceId);
    }
}
