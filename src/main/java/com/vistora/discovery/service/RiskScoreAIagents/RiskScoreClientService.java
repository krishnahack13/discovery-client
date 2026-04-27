package com.vistora.discovery.service.RiskScoreAIagents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RiskScoreClientService {

    private static final Logger log = LoggerFactory.getLogger(RiskScoreClientService.class);

    private final RestTemplate restTemplate;

    @Value("${risk.score.service.url:http://localhost:8000}")
    private String riskServiceUrl;

    public RiskScoreClientService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // --- DTOs mapping to Python FastAPI response ---
    public record ToolAggregate(
            String tool,
            String type,
            int device_count,
            int avg_score,
            int max_score,
            String risk_level
    ) {}

    public record RiskScoreResponse(
            int files_scanned,
            int files_skipped,
            List<ToolAggregate> tool_aggregate
    ) {}

    /**
     * Calls the external Python FastAPI service (/scan endpoint)
     * to aggregate risk scores from S3.
     *
     * @param bucketName    S3 Bucket containing JSON telemetry
     * @param prefix        Prefix indicating where data is stored
     * @param accessKey     AWS Access Key
     * @param secretKey     AWS Secret Key
     * @param region        AWS Region (default us-east-1)
     * @return RiskScoreResponse including tools and penalty aggregated metrics.
     */
    public RiskScoreResponse fetchAggregatedRiskScoresFromPython(
            String bucketName,
            String prefix,
            String accessKey,
            String secretKey,
            String region) {

        Map<String, String> requestPayload = new HashMap<>();
        requestPayload.put("bucket", bucketName);
        requestPayload.put("prefix", prefix != null ? prefix : "collector_data/");
        requestPayload.put("aws_access_key_id", accessKey);
        requestPayload.put("aws_secret_access_key", secretKey);
        requestPayload.put("aws_region", region != null ? region : "us-east-1");

        String url = riskServiceUrl + "/scan";
        
        log.info("Requesting risk scan from external Python engine at: {}", url);

        try {
            ResponseEntity<RiskScoreResponse> response = restTemplate.postForEntity(
                    url,
                    requestPayload,
                    RiskScoreResponse.class
            );

            log.info("Successfully fetched risk scan payload from Python engine.");
            return response.getBody();

        } catch (Exception e) {
            log.error("Failed to connect to Python Risk Engine on {}: {}", url, e.getMessage());
            throw new RuntimeException("Python Risk Engine communication failure", e);
        }
    }
}
