package com.vistora.discovery.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vistora.discovery.constants.AuditEventCodes;
import com.vistora.discovery.dto.AgentResponse;
import com.vistora.discovery.dto.GenericRequest;
import com.vistora.discovery.entity.Agent;
import com.vistora.discovery.entity.ConnectionEntity;
import com.vistora.discovery.exception.PlatformApiException;
import com.vistora.discovery.repository.AgentRepository;
import com.vistora.discovery.repository.ConnectionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

import static com.vistora.discovery.config.JsonNodeConverter.mapper;

@Service
@RequiredArgsConstructor
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private static final String SNOWFLAKE    = "snowflake";
    private static final String DATABRICKS   = "databricks";
    private static final String GOOGLEVERTEX = "googlevertex";
    private static final String EKS          = "eks";
    private static final String AGENTBRICKS  = "databricksagentbricks";

    private final ConnectionRepository repository;
    private final ObjectMapper objectMapper;
    private final AgentRepository agentRepository;
    private final AuditHistoryService auditHistoryService;

    @Autowired
    @Qualifier("tokenValidationRestTemplate")
    private RestTemplate tokenValidationRestTemplate;

    @Value("${api.databricks}")
    private String databricksApi;

    @Value("${api.snowflake}")
    private String snowflakeApi;

    @Value("${api.vertex}")
    private String vertexApi;

    @Value("${api.eks}")
    private String eksApi;

    @Value("${api.agentbricks}")
    private String agentBricksApi;

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public void fetchAndSaveAgents(String runByUser, String authToken) {
        log.info("[AGENT SERVICE] fetchAndSaveAgents — runByUser={}, authToken present={}",
                runByUser, authToken != null && !authToken.isBlank());

        List<AgentResponse> platforms = getAllAgents(authToken);
        LocalDateTime now = LocalDateTime.now();

        Set<String> managedPlatforms =
                Set.of(DATABRICKS, SNOWFLAKE, GOOGLEVERTEX, EKS, AGENTBRICKS);
        agentRepository.markInactiveByPlatforms(managedPlatforms);

        for (AgentResponse p : platforms) {
            String platform           = p.getSystem() == null ? "" : p.getSystem().toLowerCase();
            String configuredSourceId = p.getConnectionName();

            for (Map<String, Object> agentMap : p.getAgents()) {
                if (agentMap == null) continue;

                String agentId = extractAgentId(agentMap, platform);
                if (agentId == null || agentId.isBlank()) continue;

                String agentName = AGENTBRICKS.equals(platform)
                        ? Objects.toString(agentMap.get("agent_name"), "")
                        : Objects.toString(agentMap.get("name"), "");

                JsonNode primaryNode = mapper.valueToTree(agentMap);

                Optional<Agent> found = agentRepository
                        .findByAgentIdAndPlatformAndConfiguredSourceId(
                                agentId, platform, configuredSourceId);

                boolean isNewAgent = found.isEmpty();
                Agent agent = found.orElseGet(Agent::new);

                if (agent.getId() == null) {
                    agent.setCreatedBy(runByUser);
                    agent.setCreatedOn(now);
                }

                agent.setAgentId(agentId);
                agent.setAgentName(agentName);
                agent.setPlatform(platform);
                agent.setConfiguredSourceId(configuredSourceId);
                agent.setFetchedAgentPrimaryData(primaryNode);
                agent.setAgentDataUpdatedOn(now);
                agent.setIsActive(true);
                agent.setUpdatedBy(runByUser);
                agent.setUpdatedOn(now);
                agentRepository.save(agent);

                if (isNewAgent) {
                    auditHistoryService.logAuditEventAsync(
                            AuditEventCodes.AGENT_DISCOVERY,
                            "Agent", agentId,
                            String.format("Agent '%s' discovered during %s sync from source: %s",
                                    agentName, platform, configuredSourceId),
                            runByUser);
                    log.info("[AGENT SERVICE] New agent discovered — {} ({})", agentName, agentId);
                }
            }
        }
    }

    public List<AgentResponse> getAllAgents(String authToken) {
        List<ConnectionEntity> verifiedConnections = repository.findByVerificationTrue();

        log.info("[AGENT SERVICE] getAllAgents — authToken present={}, connections={}",
                authToken != null && !authToken.isBlank(),
                verifiedConnections.stream()
                        .map(c -> c.getType() + ":" + c.getName())
                        .toList());

        List<AgentResponse> results = new ArrayList<>();

        // EKS — fixed endpoint, no DB connection entry
        try {
            List<Map<String, Object>> eksAgents =
                    callEksApi(eksApi + "/scan/results", authToken);
            results.add(AgentResponse.builder()
                    .system(EKS)
                    .connectionName(EKS)
                    .agents(eksAgents)
                    .build());
            log.info("[AGENT SERVICE] Fetched {} agents from EKS", eksAgents.size());
        } catch (Exception e) {
            log.error("[AGENT SERVICE] Failed to fetch EKS agents: {}", e.getMessage(), e);
        }

        Set<String> allowedSystems = Set.of(DATABRICKS, SNOWFLAKE, GOOGLEVERTEX, AGENTBRICKS);

        for (ConnectionEntity conn : verifiedConnections) {
            if (!allowedSystems.contains(conn.getType().toLowerCase())) continue;

            try {
                List<Map<String, Object>> agents =
                        fetchAgentsFromSystem(conn.getType(), conn.getName(), authToken);

                log.info("[AGENT SERVICE] Fetched {} agents — platform={}, connection={}",
                        agents.size(), conn.getType(), conn.getName());

                results.add(AgentResponse.builder()
                        .system(conn.getType())
                        .connectionName(conn.getName())
                        .agents(agents)
                        .build());

            } catch (Exception e) {
                log.error("[AGENT SERVICE] Failed to fetch agents — platform={}, connection={}: {}",
                        conn.getType(), conn.getName(), e.getMessage(), e);
            }
        }

        return results;
    }

    @Transactional
    public JsonNode updatePlatformData(String agentId, JsonNode updateData) {
        Agent agent = agentRepository.findByAgentId(agentId)
                .orElseThrow(() -> new PlatformApiException("Agent not found: " + agentId));

        ObjectNode existing   = loadNode(agent.getPlatformUpdatedAgentData());
        ObjectNode updateNode = (ObjectNode) updateData;
        updateNode.fields().forEachRemaining(e -> existing.set(e.getKey(), e.getValue()));

        agent.setPlatformUpdatedAgentData(existing);
        agent.setPlatformDataUpdatedOn(LocalDateTime.now());
        agentRepository.save(agent);

        return existing;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PLATFORM DISPATCH
    // ═════════════════════════════════════════════════════════════════════════

    private List<Map<String, Object>> fetchAgentsFromSystem(
            String systemType, String source, String authToken) {

        log.info("[AGENT SERVICE] Fetching agents — platform={}, source={}, authToken present={}",
                systemType, source, authToken != null && !authToken.isBlank());

        // All platforms now receive the auth header — no selective logic
        HttpEntity<GenericRequest> entity =
                buildEntity(createGenericRequest(source), authToken);

        return switch (systemType.toLowerCase()) {
            case DATABRICKS   -> callPlatformApi(databricksApi  + "/agents",      entity);
            case SNOWFLAKE    -> callPlatformApi(snowflakeApi   + "/agents-list", entity);
            case GOOGLEVERTEX -> callVertexApi(vertexApi        + "/agents",      entity);
            case AGENTBRICKS  -> callPlatformApi(agentBricksApi + "/discover",    entity);
            default -> throw new PlatformApiException("Unsupported system type: " + systemType);
        };
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PLATFORM CALLERS
    // ═════════════════════════════════════════════════════════════════════════

    private List<Map<String, Object>> callEksApi(String url, String authToken) {
        log.info("[EKS] Fetching agents — url={}, authToken present={}",
                url, authToken != null && !authToken.isBlank());
        try {
            HttpEntity<Map<String, Object>> entity =
                    buildRawEntity(new HashMap<>(), authToken);

            ResponseEntity<String> response =
                    tokenValidationRestTemplate.postForEntity(url, entity, String.class);

            log.info("[EKS] Response status={}", response.getStatusCode());

            String body = response.getBody();
            if (body == null || body.isBlank()) {
                throw new PlatformApiException("Empty response from EKS API: " + url);
            }

            JsonNode root = objectMapper.readTree(body);
            String status = root.path("status").asText("");

            if (!"SUCCESS".equalsIgnoreCase(status)) {
                log.warn("[EKS] Non-SUCCESS status={}, message={}",
                        status, root.path("message").asText(""));
            }

            JsonNode dataNode = root.path("data");
            if (!dataNode.isArray() || dataNode.isEmpty()) {
                log.info("[EKS] Empty data array returned");
                return new ArrayList<>();
            }

            List<Map<String, Object>> agents =
                    objectMapper.convertValue(dataNode, new TypeReference<>() {});

            // Ensure consistent "name" field across all platforms
            agents.forEach(agentMap -> {
                if (!agentMap.containsKey("name")) {
                    agentMap.put("name", agentMap.get("agentDeployment"));
                }
            });

            log.info("[EKS] Fetched {} agents", agents.size());
            return agents;

        } catch (RestClientException e) {
            throw new PlatformApiException("Failed to call EKS API: " + url, e);
        } catch (Exception e) {
            throw new PlatformApiException("Failed to parse EKS API response from: " + url, e);
        }
    }

    private List<Map<String, Object>> callPlatformApi(
            String url, HttpEntity<GenericRequest> entity) {
        log.info("[AGENT SERVICE] POST {} ", url);
        try {
            ResponseEntity<String> response =
                    tokenValidationRestTemplate.postForEntity(url, entity, String.class);

            log.info("[AGENT SERVICE] Response status={} from {}", response.getStatusCode(), url);

            String body = response.getBody();
            if (body == null || body.isBlank()) {
                throw new PlatformApiException("Empty response from platform API: " + url);
            }

            return objectMapper.readValue(body, new TypeReference<>() {});

        } catch (RestClientException e) {
            throw new PlatformApiException("Failed to call platform API: " + url, e);
        } catch (Exception e) {
            throw new PlatformApiException("Failed to parse platform API response from: " + url, e);
        }
    }

    private List<Map<String, Object>> callVertexApi(
            String url, HttpEntity<GenericRequest> entity) {
        log.info("[VERTEX] Fetching agents — url={}", url);
        try {
            ResponseEntity<String> response =
                    tokenValidationRestTemplate.postForEntity(url, entity, String.class);

            log.info("[VERTEX] Response status={}", response.getStatusCode());

            String body = response.getBody();
            if (body == null || body.isBlank()) {
                throw new PlatformApiException("Empty response from Vertex API: " + url);
            }

            JsonNode json       = objectMapper.readTree(body);
            JsonNode agentsNode = json.path("agents");

            if (agentsNode.isArray()) {
                List<Map<String, Object>> agents =
                        objectMapper.convertValue(agentsNode, new TypeReference<>() {});
                log.info("[VERTEX] Fetched {} agents", agents.size());
                return agents;
            }

            log.warn("[VERTEX] Response did not contain 'agents' array");
            return new ArrayList<>();

        } catch (RestClientException e) {
            throw new PlatformApiException("Failed to call Vertex API: " + url, e);
        } catch (Exception e) {
            throw new PlatformApiException("Failed to parse Vertex API response from: " + url, e);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SHARED HELPERS — mirrors ModelService.buildAuthHeaders() exactly
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Single place where auth headers are set.
     * Normalises the token so there is always exactly one "Bearer " prefix,
     * regardless of whether the caller included it or not.
     */
    private HttpHeaders buildAuthHeaders(String authToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (authToken != null && !authToken.isBlank()) {
            String normalised = authToken.startsWith("Bearer ")
                    ? authToken
                    : "Bearer " + authToken;
            headers.set(HttpHeaders.AUTHORIZATION, normalised);
            log.debug("[AUTH HEADER] Authorization header set");
        } else {
            log.debug("[AUTH HEADER] No authToken — Authorization header omitted");
        }

        return headers;
    }

    private HttpEntity<GenericRequest> buildEntity(GenericRequest body, String authToken) {
        return new HttpEntity<>(body, buildAuthHeaders(authToken));
    }

    private HttpEntity<Map<String, Object>> buildRawEntity(
            Map<String, Object> body, String authToken) {
        return new HttpEntity<>(body, buildAuthHeaders(authToken));
    }

    private GenericRequest createGenericRequest(String source) {
        GenericRequest request = new GenericRequest();
        request.setSource(source);
        request.setParams(new HashMap<>());
        return request;
    }

    private String extractAgentId(Map<String, Object> agentMap, String platform) {
        if (SNOWFLAKE.equals(platform)) {
            String db     = Objects.toString(agentMap.get("databaseName"), "").trim();
            String schema = Objects.toString(agentMap.get("schemaName"),   "").trim();
            String name   = Objects.toString(agentMap.get("name"),         "").trim();
            if (db.isEmpty() || schema.isEmpty() || name.isEmpty()) return null;
            return (db + "." + schema + "." + name).toUpperCase();
        }

        if (EKS.equals(platform)) {
            String namespace       = Objects.toString(agentMap.get("namespace"),       "").trim();
            String agentDeployment = Objects.toString(agentMap.get("agentDeployment"), "").trim();
            if (agentDeployment.isEmpty()) return null;
            return namespace.isEmpty() ? agentDeployment : namespace + "/" + agentDeployment;
        }

        Object id = agentMap.getOrDefault("id", agentMap.get("agent_id"));
        return id == null ? null : id.toString();
    }

    private ObjectNode loadNode(JsonNode node) {
        return (node != null && node.isObject())
                ? (ObjectNode) node
                : mapper.createObjectNode();
    }
}