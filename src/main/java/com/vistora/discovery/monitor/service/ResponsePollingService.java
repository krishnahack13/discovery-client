package com.vistora.discovery.monitor.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vistora.discovery.monitor.controller.ResponseIngestionController;
import com.vistora.discovery.monitor.model.dto.DeviceResponse;

@Service
public class ResponsePollingService {

    private static final Logger log = LoggerFactory.getLogger(ResponsePollingService.class);

    @Value("${grafyn.agent.url:http://localhost:8082/middle-agent/sync-s3}")
    private String grafynAgentUrl;

    private final RestTemplate restTemplate;
    private final ResponseIngestionController ingestionController; // Resuing DB insertion logic cleanly
    private final ObjectMapper objectMapper;

    public ResponsePollingService(ResponseIngestionController ingestionController) {
        this.restTemplate = new RestTemplate();
        this.ingestionController = ingestionController;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    // Removed @Scheduled constraint. This is now executed perfectly on-demand via the ControlPlane API.
    public void executeS3OrchestrationPull() {
        log.info("Waking up... Initiating programmatic highly-scheduled Pull from Grafyn Agent.");
        try {
            HttpHeaders headers = new HttpHeaders();
            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
// Here we call the grafyn agent to get the data from the S3 bucket
            ResponseEntity<List<String>> response = restTemplate.exchange(
                    grafynAgentUrl,
                    HttpMethod.GET,
                    requestEntity,
                    new ParameterizedTypeReference<List<String>>() {
            }
            );

            List<String> rawJsonPayloads = response.getBody();

            if (rawJsonPayloads != null && !rawJsonPayloads.isEmpty()) {
                log.info("Successfully fetched {} raw untouched response payloads from Grafyn Agent via S3. Injecting to Detection Engine...", rawJsonPayloads.size());

                for (String rawJson : rawJsonPayloads) {
                    try {
                        DeviceResponse fetchedResponse = objectMapper.readValue(rawJson, DeviceResponse.class);

                        // Push immediately directly into existing detection logic mapping correctly to Data JPA
                        ingestionController.receiveResponse(fetchedResponse);

                    } catch (Exception e) {
                        log.error("Failed to parse footprint directly returned from Customer S3 JSON.", e);
                    }
                }
            } else {
                log.info("Grafyn Agent reports absolutely no new responses waiting in Customer S3.");
            }

        } catch (Exception e) {
            log.error("Critical Failure dialing Grafyn Agent REST Endpoint: {}", e.getMessage());
        }
    }
}
