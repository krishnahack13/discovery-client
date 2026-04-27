package com.vistora.discovery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vistora.discovery.entity.ApiGateway;
import com.vistora.discovery.entity.ConnectionEntity;
import com.vistora.discovery.repository.ApiGatewayRepository;
import com.vistora.discovery.repository.ConnectionRepository;
import com.vistora.discovery.util.ApiGatewayResponseStandardizer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class ApiGatewayService {

    private static final Logger log = LoggerFactory.getLogger(ApiGatewayService.class);

    private final ApiGatewayRepository apiGatewayRepository;
    private final ConnectionRepository connectionRepository;
    private final ObjectMapper objectMapper;

    public ApiGatewayService(ApiGatewayRepository apiGatewayRepository,
                             ConnectionRepository connectionRepository,
                             ObjectMapper objectMapper) {
        this.apiGatewayRepository = apiGatewayRepository;
        this.connectionRepository = connectionRepository;
        this.objectMapper = objectMapper;
    }

    @PersistenceContext
    private EntityManager entityManager;

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${api.apigateway.live:http://localhost:8082/grafyn-agent/api/v1/api-gateway/live}")
    private String apiGatewayLiveUrl;

    @Value("${api.apigateway.aws:http://localhost:8082/grafyn-agent/api/v1/api-gateway/aws}")
    private String apiGatewayAwsUrl;

    // =========================================================================
    // LIST + OPTIONAL REFRESH
    // =========================================================================

    @Transactional
    public List<Map<String, Object>> listApiGateways(boolean forceRefresh) {

        if (forceRefresh) {
            log.info("[ApiGateway] Force refresh triggered");
            return refreshAllFromConnections();
        }

        List<ApiGateway> gateways = apiGatewayRepository.findByIsActiveTrue();

        if (!gateways.isEmpty()) {
            updateLastAccessed(gateways);
        }

        return toResponseList(gateways);
    }

    // =========================================================================
    // REFRESH (AWS + LIVE ONLY)
    // =========================================================================

    @Transactional
    public List<Map<String, Object>> refreshAllFromConnections() {

        List<ConnectionEntity> connections =
                connectionRepository.findByVerificationTrue();

        LocalDateTime now = LocalDateTime.now();

        // ✅ STEP 1: DELETE old AWS + LIVE
        apiGatewayRepository.deleteNonGithubEntries();

        // ✅ CRITICAL: clear Hibernate session
        entityManager.clear();

        for (ConnectionEntity conn : connections) {
            try {
                String type = conn.getType();

                // ❌ Skip GitHub (handled by scanner job)
                if ("github".equalsIgnoreCase(type)) {
                    continue;
                }

                // ================= LIVE =================
                if ("Live".equalsIgnoreCase(type)) {

                    Map<String, Object> result =
                            callLiveDiscovery(conn.getSecretName(), conn.getName());

                    // LIVE returns SINGLE object
                    saveFreshGateway(result, conn.getName(), now);
                }

                // ================= AWS =================
                else if ("AWS".equalsIgnoreCase(type)) {

                    List<Map<String, Object>> results =
                            callAwsDiscovery(conn.getSecretName(), conn.getName());

                    for (Map<String, Object> r : results) {
                        saveFreshGateway(r, conn.getName(), now);
                    }
                }

            } catch (Exception e) {
                log.error("[ApiGateway] Failed for {}: {}", conn.getName(), e.getMessage());
            }
        }

        return toResponseList(apiGatewayRepository.findByIsActiveTrue());
    }

    // =========================================================================
    // SAVE (FRESH INSERT ONLY)
    // =========================================================================

    private void saveFreshGateway(Map<String, Object> raw, String conn, LocalDateTime now) {

        if (raw == null || raw.isEmpty()) return;

        Map<String, Object> std =
                ApiGatewayResponseStandardizer.standardize(
                        raw,
                        raw.getOrDefault("scanType", "LIVE_URL").toString(),
                        conn
                );

        String name = std.get("name").toString();
        String scanType = std.get("scanType").toString();

        try {
            // ✅ FIND EXISTING (based on your UNIQUE key)
            Optional<ApiGateway> existing =
                    apiGatewayRepository.findByNameAndScanTypeAndConfiguredSourceId(
                            name, scanType, conn
                    );

            ApiGateway e = existing.orElseGet(ApiGateway::new);

            // ✅ set created only once
            if (e.getId() == null) {
                e.setCreatedBy("system");
                e.setCreatedOn(now);
            }

            // ✅ always update
            e.setName(name);
            e.setScanType(scanType);
            e.setStatus(std.get("status").toString());
            e.setConfiguredSourceId(conn);

            e.setTotalRoutes((Integer) std.getOrDefault("totalRoutes", 0));
            e.setTotalEndpoints((Integer) std.getOrDefault("totalEndpoints", 0));

            e.setRoutes(objectMapper.valueToTree(std.get("routes")));
            e.setDetails(objectMapper.valueToTree(std.get("details")));

            e.setIsActive(true);
            e.setLastAccessed(now);
            e.setUpdatedBy("system");
            e.setUpdatedOn(now);

            apiGatewayRepository.save(e);

        } catch (Exception ex) {
            log.error("Upsert failed for {}|{}|{}: {}", name, scanType, conn, ex.getMessage());
        }
    }
    // =========================================================================
    // CALL GRAFYN-AGENT (LIVE)
    // =========================================================================

    private Map<String, Object> callLiveDiscovery(String secretName, String source) {

        try {
            Map<String, Object> body = Map.of(
                    "source", source,
                    "secretName", secretName
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> resp =
                    restTemplate.postForEntity(
                            apiGatewayLiveUrl + "/discover",
                            new HttpEntity<>(body, headers),
                            String.class
                    );

            return objectMapper.readValue(resp.getBody(), Map.class);

        } catch (Exception e) {
            throw new RuntimeException("Live discovery failed", e);
        }
    }

    // =========================================================================
    // CALL GRAFYN-AGENT (AWS)
    // =========================================================================

    private List<Map<String, Object>> callAwsDiscovery(String secretName, String source) {

        try {
            Map<String, Object> body = Map.of(
                    "source", source,
                    "secretName", secretName
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> resp =
                    restTemplate.postForEntity(
                            apiGatewayAwsUrl + "/discover",
                            new HttpEntity<>(body, headers),
                            String.class
                    );

            return objectMapper.readValue(resp.getBody(), List.class);

        } catch (Exception e) {
            throw new RuntimeException("AWS discovery failed", e);
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private void updateLastAccessed(List<ApiGateway> gateways) {
        LocalDateTime now = LocalDateTime.now();
        for (ApiGateway g : gateways) g.setLastAccessed(now);
        apiGatewayRepository.saveAll(gateways);
    }

    private List<Map<String, Object>> toResponseList(List<ApiGateway> gateways) {

        List<Map<String, Object>> list = new ArrayList<>();

        for (ApiGateway g : gateways) {

            Map<String, Object> m = new LinkedHashMap<>();

            m.put("id", g.getId());
            m.put("scanType", g.getScanType());
            m.put("status", g.getStatus());
            m.put("name", g.getName());
            m.put("configuredSourceId", g.getConfiguredSourceId());
            m.put("totalRoutes", g.getTotalRoutes());
            m.put("totalEndpoints", g.getTotalEndpoints());

            m.put("routes", g.getRoutes() != null
                    ? objectMapper.convertValue(g.getRoutes(), Object.class)
                    : Collections.emptyList());

            m.put("details", g.getDetails() != null
                    ? objectMapper.convertValue(g.getDetails(), Object.class)
                    : Collections.emptyMap());

            m.put("isActive", g.getIsActive());
            m.put("lastAccessed", g.getLastAccessed() != null ? g.getLastAccessed().toString() : null);
            m.put("createdBy", g.getCreatedBy());
            m.put("createdOn", g.getCreatedOn() != null ? g.getCreatedOn().toString() : null);
            m.put("updatedBy", g.getUpdatedBy());
            m.put("updatedOn", g.getUpdatedOn() != null ? g.getUpdatedOn().toString() : null);

            list.add(m);
        }

        return list;
    }
}