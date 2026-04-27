package com.vistora.discovery.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vistora.discovery.constants.AuditEventCodes;
import com.vistora.discovery.dto.UserResponse;
import com.vistora.discovery.entity.ConnectionEntity;
import com.vistora.discovery.entity.User;
import com.vistora.discovery.repository.ConnectionRepository;
import com.vistora.discovery.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final ConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final AuditHistoryService auditHistoryService;
    private final ObjectMapper objectMapper;

    public UserService(ConnectionRepository connectionRepository, UserRepository userRepository,
                       AuditHistoryService auditHistoryService, ObjectMapper objectMapper) {
        this.connectionRepository = connectionRepository;
        this.userRepository = userRepository;
        this.auditHistoryService = auditHistoryService;
        this.objectMapper = objectMapper;
    }

    @Value("${api.databricks}")
    private String databricksUrl;

    @Value("${api.snowflake}")
    private String snowflakeUrl;

    @Value("${api.bigquery}")
    private String bigqueryUrl;

    @Value("${api.microsoft}")
    private String azureAdUrl;

    @Value("${api.google}")
    private String googleUrl;

    @Value("${api.okta}")
    private String oktaUrl;

    private static final Set<String> ALLOWED_PLATFORMS =
            Set.of("databricks", "snowflake", "googlebigquery",
                    "microsoftazuread", "okta", "google");

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public void fetchAndSaveUsers(String runByUser, String authToken, List<String> platforms) {
        log.info("[USER SERVICE] fetchAndSaveUsers — runByUser={}, platforms={}, authToken present={}",
                runByUser, platforms.isEmpty() ? "all" : platforms,
                authToken != null && !authToken.isBlank());

        List<UserResponse> allPlatforms = getAllUsers(authToken);
        LocalDateTime now = LocalDateTime.now();

        // Filter to requested platforms if specified, otherwise process all
        List<UserResponse> toProcess;
        if (platforms != null && !platforms.isEmpty()) {
            Set<String> requested = new HashSet<>(platforms);
            toProcess = allPlatforms.stream()
                    .filter(p -> p.getSystem() != null &&
                            requested.contains(p.getSystem().toLowerCase(Locale.ROOT)))
                    .toList();

            log.info("[USER SERVICE] Filtered to {} platforms matching: {}",
                    toProcess.size(), requested);

            // Mark inactive only for the requested platforms
            requested.forEach(userRepository::markInactiveByPlatform);
        } else {
            toProcess = allPlatforms;
            userRepository.markAllInactive();
        }

        for (UserResponse p : toProcess) {
            if (p.getUsers() == null || p.getUsers().isEmpty()) {
                log.warn("[USER SERVICE] No users from platform={}, source={} — skipping",
                        p.getSystem(), p.getConnectionName());
                continue;
            }

            String platform = p.getSystem() == null
                    ? "" : p.getSystem().toLowerCase(Locale.ROOT);

            for (Map<String, Object> userMap : p.getUsers()) {
                if (userMap == null) continue;

                String email = extractEmail(userMap);
                if (email == null || email.isBlank()) continue;

                String   userName    = extractName(userMap);
                JsonNode primaryData = objectMapper.valueToTree(userMap);

                Optional<User> found = userRepository.findByEmailAndPlatformAndConfiguredSourceId(
                        email, platform, p.getConnectionName());

                boolean isNewUser = found.isEmpty();
                User    user      = found.orElseGet(User::new);

                if (user.getId() == null) {
                    user.setCreatedBy(runByUser);
                    user.setCreatedOn(now);
                }

                user.setEmail(email);
                user.setUserName(userName);
                user.setPlatform(platform);
                user.setConfiguredSourceId(p.getConnectionName());
                user.setFetchedUserPrimaryData(primaryData);
                user.setUserDataUpdatedOn(now);
                user.setIsActive(Boolean.TRUE);
                user.setUpdatedBy(runByUser);
                user.setUpdatedOn(now);
                userRepository.save(user);

                if (isNewUser) {
                    auditHistoryService.logAuditEventAsync(
                            AuditEventCodes.USER_DISCOVERY,
                            "User", email,
                            String.format("User '%s' discovered during %s sync from source: %s",
                                    userName, platform, p.getConnectionName()),
                            runByUser);
                    log.info("[USER SERVICE] New user discovered — {} ({})", userName, email);
                }
            }
        }
    }

    public List<UserResponse> getAllUsers(String authToken) {
        List<ConnectionEntity> verifiedConnections = connectionRepository.findByVerificationTrue();

        log.info("[USER SERVICE] getAllUsers — authToken present={}, connections={}",
                authToken != null && !authToken.isBlank(),
                verifiedConnections.stream()
                        .map(c -> c.getType() + ":" + c.getName())
                        .toList());

        List<UserResponse> results = new ArrayList<>();

        for (ConnectionEntity conn : verifiedConnections) {
            if (conn.getType() == null ||
                    !ALLOWED_PLATFORMS.contains(conn.getType().toLowerCase(Locale.ROOT))) {
                continue;
            }

            try {
                List<Map<String, Object>> users =
                        fetchUsersFromSystem(conn.getType(), conn.getName(), authToken);

                log.info("[USER SERVICE] Fetched {} users — platform={}, connection={}",
                        users.size(), conn.getType(), conn.getName());

                results.add(UserResponse.builder()
                        .system(conn.getType())
                        .connectionName(conn.getName())
                        .users(users)
                        .build());

            } catch (Exception e) {
                log.error("[USER SERVICE] Failed to fetch users — platform={}, connection={}: {}",
                        conn.getType(), conn.getName(), e.getMessage(), e);
            }
        }

        return results;
    }

    @Transactional
    public JsonNode updatePlatformData(String email, String platform,
                                       String configuredSourceId, JsonNode updateData) {
        User user = userRepository.findByEmailAndPlatformAndConfiguredSourceId(
                        email, platform, configuredSourceId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        ObjectNode existing = loadNode(user.getPlatformUpdatedUserData());

        if (updateData.isObject()) {
            ((ObjectNode) updateData).fields()
                    .forEachRemaining(e -> existing.set(e.getKey(), e.getValue()));
        }

        user.setPlatformUpdatedUserData(existing);
        user.setPlatformDataUpdatedOn(LocalDateTime.now());
        userRepository.save(user);

        return existing;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PLATFORM FETCHER
    // ═════════════════════════════════════════════════════════════════════════

    private List<Map<String, Object>> fetchUsersFromSystem(
            String systemType, String source, String authToken) throws Exception {

        String url = switch (systemType.toLowerCase(Locale.ROOT)) {
            case "databricks"      -> databricksUrl + "/users";
            case "snowflake"       -> snowflakeUrl   + "/users";
            case "googlebigquery"  -> bigqueryUrl    + "/users";
            case "microsoftazuread"-> azureAdUrl     + "/users";
            case "okta"            -> oktaUrl        + "/users";
            case "google"          -> googleUrl      + "/users";
            default -> throw new IllegalArgumentException(
                    "Unsupported system type: " + systemType);
        };

        log.info("[USER SERVICE] Fetching users — platform={}, url={}, authToken present={}",
                systemType, url, authToken != null && !authToken.isBlank());

        Map<String, Object> body = Map.of(
                "source", source,
                "params", Collections.emptyMap()
        );

        HttpEntity<Map<String, Object>> entity = buildRawEntity(body, authToken);

        RestTemplate rt = createRestTemplate();

        try {
            ResponseEntity<String> res = rt.postForEntity(url, entity, String.class);

            log.info("[USER SERVICE] Response status={} from platform={}",
                    res.getStatusCode(), systemType);

            if (res.getBody() == null || res.getBody().isBlank()) {
                log.warn("[USER SERVICE] Empty response from platform={}, source={}",
                        systemType, source);
                return Collections.emptyList();
            }

            JsonNode json = objectMapper.readTree(res.getBody());

            // Handle both { "users": [...] } and [...] response shapes
            if (json.has("users") && json.get("users").isArray()) {
                List<Map<String, Object>> users = objectMapper.convertValue(
                        json.get("users"), new TypeReference<>() {});
                log.info("[USER SERVICE] Parsed {} users (wrapped) from platform={}",
                        users.size(), systemType);
                return users;
            }

            if (json.isArray()) {
                List<Map<String, Object>> users = objectMapper.convertValue(
                        json, new TypeReference<>() {});
                log.info("[USER SERVICE] Parsed {} users (array) from platform={}",
                        users.size(), systemType);
                return users;
            }

            log.warn("[USER SERVICE] Unexpected response shape from platform={}: {}",
                    systemType, json.getNodeType());
            return Collections.emptyList();

        } catch (Exception e) {
            log.error("[USER SERVICE] Fetch failed — platform={}, url={}: {}",
                    systemType, url, e.getMessage(), e);
            throw e;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SHARED HELPERS — mirrors ModelService/AgentService pattern exactly
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Single place where auth headers are set across the user sync chain.
     * Normalises token to exactly one "Bearer " prefix regardless of input.
     */
    private HttpHeaders buildAuthHeaders(String authToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (authToken != null && !authToken.isBlank()) {
            String normalised = authToken.startsWith("Bearer ")
                    ? authToken
                    : "Bearer " + authToken;
            headers.set(HttpHeaders.AUTHORIZATION, normalised);
            log.debug("[AUTH HEADER] Authorization set for user sync");
        } else {
            log.debug("[AUTH HEADER] No authToken — Authorization header omitted");
        }

        return headers;
    }

    private HttpEntity<Map<String, Object>> buildRawEntity(
            Map<String, Object> body, String authToken) {
        return new HttpEntity<>(body, buildAuthHeaders(authToken));
    }

    /**
     * Dedicated RestTemplate with explicit timeouts.
     * No content-type interceptor — that's handled by buildAuthHeaders().
     */
    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(600_000);
        return new RestTemplate(factory);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // EXTRACTION HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private String extractEmail(Map<String, Object> userMap) {
        Object emailObj = userMap.get("email");
        String email = emailObj != null ? emailObj.toString().trim() : null;
        if (email != null && !email.isBlank()) return email;

        // Snowflake fallback — loginName is sometimes the email
        Object loginObj = userMap.get("loginName");
        String loginName = loginObj != null ? loginObj.toString().trim() : null;
        if (loginName != null && looksLikeEmail(loginName)) return loginName;

        return null;
    }

    private boolean looksLikeEmail(String value) {
        return value != null &&
                value.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    private String extractName(Map<String, Object> map) {
        for (String key : List.of("username", "name", "displayName")) {
            Object val = map.get(key);
            if (val != null && !val.toString().isBlank()) return val.toString();
        }
        return "";
    }

    private ObjectNode loadNode(JsonNode node) {
        return (node != null && node.isObject())
                ? (ObjectNode) node
                : objectMapper.createObjectNode();
    }
}