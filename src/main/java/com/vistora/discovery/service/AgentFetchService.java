package com.vistora.discovery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vistora.discovery.entity.Agent;
import com.vistora.discovery.exception.PlatformApiException;
import com.vistora.discovery.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
@Service
public class AgentFetchService {

    private static final Logger log = LoggerFactory.getLogger(AgentFetchService.class);

    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentFetchService(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Value("${agents.details.ttl.minutes:15}")
    private long detailsTtlMinutes;

    /**
     * Fetch list of agents (clean JSON)
     */
//    @Transactional(readOnly = true)
//    public List<Object> fetchAgentList() {
//
//        return agentRepository.findAll()
//                .stream()
//                .filter(a -> Boolean.TRUE.equals(a.getIsActive()))
//                .map(a -> {
//
//                    JsonNode n = a.getFetchedAgentPrimaryData();
//                    Object base;
//
//                    try {
//                        base = objectMapper.convertValue(n, Object.class);
//                    } catch (Exception e) {
//                        base = new HashMap<>();
//                    }
//
//                    // Convert base object into mutable map
//                    HashMap<String, Object> map = new HashMap<>();
//                    if (base instanceof HashMap<?, ?> b) {
//                        b.forEach((k, v) -> map.put(k.toString(), v));
//                    }
//
//                    // Add platform + configuredSourceId as configurationName
//                    map.put("platform", a.getPlatform());
//                    map.put("configurationName", a.getConfiguredSourceId());
//
//                    return map;
//                })
//                .collect(Collectors.toList());
//    }

    @Transactional(readOnly = true)
    public List<Object> fetchAgentList() {

        log.info("=== Starting fetchAgentList ===");

        List<Agent> agents = agentRepository.findAll(); // ✅ NO FILTER

        LocalDateTime latestUpdate = agents.stream()
                .map(Agent::getAgentDataUpdatedOn)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        List<Object> result = agents.stream()
                .map(a -> {

                    JsonNode n = a.getFetchedAgentPrimaryData();
                    Object base;

                    try {
                        base = objectMapper.convertValue(n, Object.class);
                    } catch (Exception e) {
                        base = new HashMap<>();
                    }

                    Set<String> excludedFields = Set.of(
                            "ragPipeline",
                            "mcpPresence",
                            "apiGateway"
                    );

                    HashMap<String, Object> map = new HashMap<>();

                    if (base instanceof HashMap<?, ?> b) {
                        b.forEach((k, v) -> {
                            if (!excludedFields.contains(k.toString())) {
                                map.put(k.toString(), v);
                            }
                        });
                    }

                    map.put("platform", a.getPlatform());
                    map.put("configurationName", a.getConfiguredSourceId());
                    map.put("isActive", a.getIsActive()); // 🔥 ADD THIS (important)

                    if (latestUpdate != null) {
                        map.put("lastUpdated", latestUpdate.toString());
                    }

                    JsonNode platformData = a.getPlatformUpdatedAgentData();
                    if (platformData != null && !platformData.isNull() && !platformData.isEmpty()) {
                        Object platformDataObj = objectMapper.convertValue(platformData, Object.class);
                        map.put("platform_updated_agent_data", platformDataObj);
                    }

                    return map;
                })
                .collect(Collectors.toList());

        return result;
    }






    /**
     * Fetch agent details (with stale logic)
     */
    @Transactional
    public JsonNode fetchAgentDetails(
            String agentId,
            String platform,
            String configuredSourceId
    ) {

        Agent agent = agentRepository
                .findByAgentIdAndPlatformAndConfiguredSourceId(
                        agentId, platform, configuredSourceId
                )
                .orElseThrow(() ->
                        new RuntimeException("Agent not found: " + agentId)
                );

        if (agent.getFetchedAgentDetails() != null
                && agent.getDetailsDataUpdatedOn() != null) {

            Duration age = Duration.between(
                    agent.getDetailsDataUpdatedOn(),
                    LocalDateTime.now()
            );

            if (age.toMinutes() <= detailsTtlMinutes) {
                agent.setDetailsLastAccessed(LocalDateTime.now());
                agentRepository.save(agent);
                return agent.getFetchedAgentDetails();
            }
        }

        return fetchAndSaveAgentDetailsSync(agent);
    }


    /**
     * Fetch details again from platform (placeholder for Job #2)
     */
    private JsonNode fetchAndSaveAgentDetailsSync(Agent agent) {

        try {
            // For now use primary data as details (replace with real platform call)
            JsonNode detailsNode = agent.getFetchedAgentPrimaryData();

            agent.setFetchedAgentDetails(detailsNode);
            agent.setDetailsDataUpdatedOn(LocalDateTime.now());
            agent.setDetailsLastAccessed(LocalDateTime.now());
            agentRepository.save(agent);

            return detailsNode;

        } catch (Exception ex) {
            throw new PlatformApiException("Failed to fetch details for agent: " + agent.getAgentId(), ex);
        }
    }
}
