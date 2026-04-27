package com.vistora.discovery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vistora.discovery.dto.GenericRequest;
import com.vistora.discovery.dto.ModelResponse;
import com.vistora.discovery.entity.ConnectionEntity;
import com.vistora.discovery.entity.Model;
import com.vistora.discovery.exception.PlatformApiException;
import com.vistora.discovery.repository.ConnectionRepository;
import com.vistora.discovery.repository.ModelRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
public class ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);

    // ── Platform constants ────────────────────────────────────────────────────
    private static final String DATABRICKS   = "databricks";
    private static final String SNOWFLAKE    = "snowflake";
    private static final String GOOGLE_VERTEX = "googlevertex";
    private static final String HUGGINGFACES = "huggingfaces";
    private static final String SAGEMAKER    = "sagemaker";

    // ── Field name constants ──────────────────────────────────────────────────
    private static final String MODEL_NAME = "modelName";
    private static final String NAME       = "name";
    private static final String ID         = "id";
    private static final String MODEL_ID   = "model_id";
    private static final String DATABASE   = "database";
    private static final String SCHEMA     = "schema";
    private static final String TAGS       = "tags";
    private static final String SOURCE     = "source";

    // ── API path constants ────────────────────────────────────────────────────
    private static final String MLFLOW_MODELS_LIST  = "/mlflow-models/list-with-versions";
    private static final String ML_MODELS_PATH      = "/ml-models";
    private static final String MODELS_PATH         = "/models";
    private static final String SNOWFLAKE_TAGS_PATH = "/tags";

    // ── Misc constants ────────────────────────────────────────────────────────
    private static final String UNSUPPORTED_SYSTEM_TYPE = "Unsupported system type: ";
    private static final String MODEL_NOT_FOUND         = "Model not found: ";
    private static final int    THREAD_POOL_SIZE        = 20;

    // Note: NOT using the injected restTemplate for platform calls —
    // each call builds its own headers with auth. The bean is kept for
    // any future interceptor-based usage.
    private final RestTemplate restTemplate;
    private final ConnectionRepository repository;
    private final ModelRepository modelRepository;
    private final AuditHistoryService auditHistoryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${api.databricks}")
    private String databricksApi;

    @Value("${api.snowflake}")
    private String snowflakeApi;

    @Value("${api.vertex}")
    private String vertexApi;

    @Value("${api.huggingfaces}")
    private String huggingfacesApi;

    @Value("${api.sagemaker}")
    private String sagemakerApi;

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public void fetchAndSaveModels(String runByUser, String authToken) {
        log.info("[MODEL SERVICE] fetchAndSaveModels — runByUser={}, authToken present={}",
                runByUser, authToken != null && !authToken.isBlank());

        List<ModelResponse> responses = getAllModels(authToken);
        LocalDateTime now = LocalDateTime.now();

        Set<String> managedPlatforms =
                Set.of(DATABRICKS, SNOWFLAKE, GOOGLE_VERTEX, HUGGINGFACES, SAGEMAKER);
        modelRepository.markInactiveByPlatforms(managedPlatforms);

        for (ModelResponse resp : responses) {
            if (resp == null || resp.getModels() == null) continue;

            String platform          = resp.getSystem();
            String configuredSourceId = resp.getConnectionName();

            for (Map<String, Object> modelMap : resp.getModels()) {
                if (modelMap == null) continue;

                String modelId = extractModelId(modelMap, platform, configuredSourceId);
                if (modelId == null || modelId.isBlank()) continue;

                String modelName = Objects.toString(
                        modelMap.getOrDefault(NAME, modelMap.get(MODEL_NAME)), "");

                upsertModel(modelId, modelName, platform, configuredSourceId,
                        objectMapper.valueToTree(modelMap), runByUser, now);
            }
        }
    }

    /** No-arg overload — keeps existing callers (e.g. /all-models endpoint) compiling. */
    public List<ModelResponse> getAllModels() {
        return getAllModels(null);
    }

    public List<ModelResponse> getAllModels(String authToken) {
        List<ConnectionEntity> verifiedConnections = repository.findByVerificationTrue();
        List<ModelResponse> results = new ArrayList<>();

        Set<String> allowedSystems =
                Set.of(DATABRICKS, SNOWFLAKE, GOOGLE_VERTEX, HUGGINGFACES, SAGEMAKER);

        for (ConnectionEntity conn : verifiedConnections) {
            if (!allowedSystems.contains(conn.getType().toLowerCase())) continue;

            try {
                List<Map<String, Object>> models =
                        fetchModelsFromSystem(conn.getType(), conn.getName(), authToken);

                log.info("[MODEL SERVICE] Fetched {} models — platform={}, connection={}",
                        models.size(), conn.getType(), conn.getName());

                results.add(ModelResponse.builder()
                        .system(conn.getType())
                        .connectionName(conn.getName())
                        .models(models)
                        .build());

            } catch (Exception e) {
                // Log full details so we can see exactly which platform failed and why
                log.error("[MODEL SERVICE] Failed to fetch models — platform={}, connection={}: {}",
                        conn.getType(), conn.getName(), e.getMessage(), e);
            }
        }

        return results;
    }

    @Transactional
    public JsonNode updatePlatformData(String modelId, JsonNode updateData) {
        Model model = modelRepository.findByModelId(modelId)
                .orElseThrow(() -> new PlatformApiException(MODEL_NOT_FOUND + modelId));

        ObjectNode existing  = loadNode(model.getPlatformUpdatedModelData());
        ObjectNode updateNode = (ObjectNode) updateData;
        updateNode.fields().forEachRemaining(e -> existing.set(e.getKey(), e.getValue()));

        model.setPlatformUpdatedModelData(existing);
        model.setPlatformDataUpdatedOn(LocalDateTime.now());
        modelRepository.save(model);

        return existing;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PLATFORM DISPATCH
    // ═════════════════════════════════════════════════════════════════════════

    private List<Map<String, Object>> fetchModelsFromSystem(
            String systemType, String source, String authToken) throws Exception {

        // Each platform call gets a fresh RestTemplate instance so there is
        // no shared state between concurrent platform fetches.
        RestTemplate rt = new RestTemplate();

        log.info("[MODEL SERVICE] Fetching from platform={}, source={}, authToken present={}",
                systemType, source, authToken != null && !authToken.isBlank());

        return switch (systemType.toLowerCase()) {
            case DATABRICKS    -> fetchDatabricksModels(rt, source, authToken);
            case SNOWFLAKE     -> fetchSnowflakeModels(rt, source, authToken);
            case GOOGLE_VERTEX -> fetchVertexModels(rt, source, authToken);
            case HUGGINGFACES  -> fetchHuggingfacesModels(rt, source, authToken);
            case SAGEMAKER     -> fetchSagemakerModels(rt, source, authToken);
            default -> throw new PlatformApiException(UNSUPPORTED_SYSTEM_TYPE + systemType);
        };
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PLATFORM FETCHERS
    // ═════════════════════════════════════════════════════════════════════════

    private List<Map<String, Object>> fetchDatabricksModels(
            RestTemplate rt, String source, String authToken) throws Exception {

        String url = databricksApi + MLFLOW_MODELS_LIST;
        log.info("[DATABRICKS] Fetching models — url={}", url);

        HttpEntity<GenericRequest> entity =
                buildEntity(createGenericRequest(source), authToken);

        try {
            ResponseEntity<String> response =
                    rt.exchange(url, HttpMethod.POST, entity, String.class);

            log.info("[DATABRICKS] Response status={}, body length={}",
                    response.getStatusCode(),
                    response.getBody() != null ? response.getBody().length() : 0);

            JsonNode dbResponse = objectMapper.readTree(response.getBody());

            if (dbResponse == null || dbResponse.isNull()) {
                log.warn("[DATABRICKS] Response body parsed to null");
                return new ArrayList<>();
            }

            if (!dbResponse.isArray()) {
                log.warn("[DATABRICKS] Expected array response but got: {}",
                        dbResponse.getNodeType());
                return new ArrayList<>();
            }

            List<Map<String, Object>> models =
                    objectMapper.convertValue(dbResponse, List.class);
            log.info("[DATABRICKS] Parsed {} models", models.size());
            return models;

        } catch (Exception e) {
            log.error("[DATABRICKS] Fetch failed — url={}, error={}", url, e.getMessage(), e);
            throw e;
        }
    }

    private List<Map<String, Object>> fetchSnowflakeModels(
            RestTemplate rt, String source, String authToken) throws Exception {

        String url = snowflakeApi + ML_MODELS_PATH;
        log.info("[SNOWFLAKE] Fetching models — url={}", url);

        HttpEntity<GenericRequest> entity =
                buildEntity(createGenericRequest(source), authToken);

        ResponseEntity<String> response =
                rt.exchange(url, HttpMethod.POST, entity, String.class);

        log.info("[SNOWFLAKE] Response status={}", response.getStatusCode());

        JsonNode sfResponse = objectMapper.readTree(response.getBody());
        if (!sfResponse.isArray()) {
            log.warn("[SNOWFLAKE] Expected array but got: {}", sfResponse.getNodeType());
            return new ArrayList<>();
        }

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        try {
            List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
            for (JsonNode modelNode : sfResponse) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> enrichSnowflakeModelWithTags(modelNode, source, rt, authToken),
                        executor));
            }
            List<Map<String, Object>> models =
                    futures.stream().map(CompletableFuture::join).toList();
            log.info("[SNOWFLAKE] Parsed {} models", models.size());
            return models;
        } finally {
            executor.shutdown();
        }
    }

    private Map<String, Object> enrichSnowflakeModelWithTags(
            JsonNode modelNode, String source, RestTemplate rt, String authToken) {

        Map<String, Object> model = objectMapper.convertValue(modelNode, Map.class);
        try {
            String database  = modelNode.get(DATABASE).asText().toUpperCase();
            String schema    = modelNode.get(SCHEMA).asText().toUpperCase();
            String modelName = modelNode.get(MODEL_NAME).asText().toUpperCase();

            // New endpoint — POST with body instead of path params
            String tagsUrl = "http://localhost:8070/snowflake-client/api/v1/snowflake/get-tags-in-model";

            // Build request body matching the expected shape
            Map<String, Object> params = new HashMap<>();
            params.put("db", database);
            params.put("schema", schema);
            params.put("model", modelName);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put(SOURCE, source);
            requestBody.put("params", params);

            HttpEntity<Map<String, Object>> tagsEntity = buildRawEntity(requestBody, authToken);

            log.debug("[SNOWFLAKE TAGS] Fetching tags — url={}, db={}, schema={}, model={}",
                    tagsUrl, database, schema, modelName);

            ResponseEntity<String> tagsResponse =
                    rt.exchange(tagsUrl, HttpMethod.POST, tagsEntity, String.class);

            log.debug("[SNOWFLAKE TAGS] Response status={}", tagsResponse.getStatusCode());

            JsonNode tagsJson = objectMapper.readTree(tagsResponse.getBody());
            model.put(TAGS, objectMapper.convertValue(tagsJson, List.class));

        } catch (Exception e) {
            log.warn("[SNOWFLAKE TAGS] Failed to fetch tags for source={}, model={}: {}",
                    source, modelNode.path(MODEL_NAME).asText(), e.getMessage());
            model.put(TAGS, Collections.emptyList());
        }
        return model;
    }

    private List<Map<String, Object>> fetchVertexModels(
            RestTemplate rt, String source, String authToken) throws Exception {

        String url = vertexApi + MODELS_PATH;
        log.info("[VERTEX] Fetching models — url={}", url);

        HttpEntity<GenericRequest> entity =
                buildEntity(createGenericRequest(source), authToken);

        ResponseEntity<String> response =
                rt.exchange(url, HttpMethod.POST, entity, String.class);

        log.info("[VERTEX] Response status={}", response.getStatusCode());

        JsonNode vtxResponse = objectMapper.readTree(response.getBody());
        List<Map<String, Object>> models = new ArrayList<>();

        if (vtxResponse != null && vtxResponse.has("models")) {
            for (JsonNode modelNode : vtxResponse.get("models")) {
                Map<String, Object> model = new HashMap<>();
                model.put("modelId",      modelNode.path("modelId").asText());
                model.put(MODEL_NAME,     modelNode.path(MODEL_NAME).asText());
                model.put("projectId",    modelNode.path("projectId").asText());
                model.put("projectName",  modelNode.path("projectName").asText());
                model.put("region",       modelNode.path("region").asText());
                model.put("modelType",    modelNode.path("modelType").asText());
                model.put("owner",        modelNode.path("owner").asText());
                model.put("modelTags",    objectMapper.convertValue(modelNode.get("modelTags"), List.class));
                model.put("modelStatus",  modelNode.path("modelStatus").asText());
                model.put("client",       modelNode.path("client").asText());
                model.put("framework",    modelNode.path("framework").asText());
                model.put("task",         objectMapper.convertValue(modelNode.get("task"), List.class));
                model.put("createdOn",    modelNode.path("createdOn").asText());
                model.put("versions",     objectMapper.convertValue(modelNode.get("versions"), List.class));
                models.add(model);
            }
        }

        log.info("[VERTEX] Parsed {} models", models.size());
        return models;
    }

    private List<Map<String, Object>> fetchHuggingfacesModels(
            RestTemplate rt, String source, String authToken) throws Exception {

        String url = huggingfacesApi + MODELS_PATH;
        log.info("[HUGGINGFACES] Fetching models — url={}", url);

        HttpEntity<GenericRequest> entity =
                buildEntity(createGenericRequest(source), authToken);

        ResponseEntity<String> response =
                rt.exchange(url, HttpMethod.POST, entity, String.class);

        log.info("[HUGGINGFACES] Response status={}", response.getStatusCode());

        List<Map<String, Object>> models =
                jsonToList(objectMapper.readTree(response.getBody()));
        log.info("[HUGGINGFACES] Parsed {} models", models.size());
        return models;
    }

    private List<Map<String, Object>> fetchSagemakerModels(
            RestTemplate rt, String source, String authToken) throws Exception {

        String url = sagemakerApi + "/models/list";
        log.info("[SAGEMAKER] Fetching models — url={}", url);

        // SageMaker agent only accepts { "source": "..." } — no "params" wrapper
        Map<String, Object> sagemakerBody = new HashMap<>();
        sagemakerBody.put(SOURCE, source);

        HttpEntity<Map<String, Object>> entity = buildRawEntity(sagemakerBody, authToken);

        ResponseEntity<String> response =
                rt.exchange(url, HttpMethod.POST, entity, String.class);

        log.info("[SAGEMAKER] Response status={}", response.getStatusCode());

        JsonNode smResponse = objectMapper.readTree(response.getBody());

        if (smResponse == null || !smResponse.has("models")
                || smResponse.get("models").isNull()) {
            log.warn("[SAGEMAKER] No 'models' field in response");
            return new ArrayList<>();
        }

        List<Map<String, Object>> models =
                objectMapper.convertValue(smResponse.get("models"), List.class);
        log.info("[SAGEMAKER] Parsed {} models, fetching versions...", models.size());

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        try {
            List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
            for (Map<String, Object> modelMap : models) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> enrichSagemakerModelWithVersions(modelMap, source, rt, authToken),
                        executor));
            }
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdown();
        }
    }

    private Map<String, Object> enrichSagemakerModelWithVersions(
            Map<String, Object> modelMap, String source, RestTemplate rt, String authToken) {

        String modelPackageGroupName =
                Objects.toString(modelMap.get(MODEL_NAME), "").trim();

        if (modelPackageGroupName.isEmpty()) {
            log.warn("[SAGEMAKER] Model has no modelName, skipping version fetch");
            modelMap.put("modelVersions", Collections.emptyList());
            return modelMap;
        }

        try {
            String versionsUrl = sagemakerApi + "/models/versions/list";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put(SOURCE, source);
            requestBody.put("modelPackageGroupName", modelPackageGroupName);

            HttpEntity<Map<String, Object>> entity = buildRawEntity(requestBody, authToken);

            ResponseEntity<String> versionsResponse =
                    rt.exchange(versionsUrl, HttpMethod.POST, entity, String.class);

            JsonNode versionsJson = objectMapper.readTree(versionsResponse.getBody());

            if (versionsJson != null && versionsJson.has("modelVersions")
                    && !versionsJson.get("modelVersions").isNull()) {
                modelMap.put("modelVersions",
                        objectMapper.convertValue(versionsJson.get("modelVersions"), List.class));
                log.info("[SAGEMAKER] Fetched versions for model={}", modelPackageGroupName);
            } else {
                modelMap.put("modelVersions", Collections.emptyList());
            }

        } catch (Exception e) {
            log.error("[SAGEMAKER] Failed to fetch versions for model={}: {}",
                    modelPackageGroupName, e.getMessage(), e);
            modelMap.put("modelVersions", Collections.emptyList());
        }

        return modelMap;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SHARED HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Builds an HttpEntity with JSON content type and Authorization header.
     * Handles the "Bearer " prefix correctly — if the token already starts with
     * "Bearer " it is used as-is; otherwise the prefix is added.
     * This is the single place in the class that touches auth headers.
     */
    private HttpHeaders buildAuthHeaders(String authToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (authToken != null && !authToken.isBlank()) {
            // Normalise: ensure exactly one "Bearer " prefix regardless of what was passed in
            String normalised = authToken.startsWith("Bearer ")
                    ? authToken
                    : "Bearer " + authToken;
            headers.set(HttpHeaders.AUTHORIZATION, normalised);
            log.debug("[AUTH HEADER] Authorization header set (normalised prefix)");
        } else {
            log.debug("[AUTH HEADER] No authToken — Authorization header omitted");
        }

        return headers;
    }

    /** Wraps a GenericRequest body with auth headers. */
    private HttpEntity<GenericRequest> buildEntity(GenericRequest body, String authToken) {
        return new HttpEntity<>(body, buildAuthHeaders(authToken));
    }

    /** Wraps a raw Map body with auth headers (used for SageMaker which doesn't use GenericRequest). */
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

    private void upsertModel(String modelId, String modelName, String platform,
                             String configuredSourceId, JsonNode primaryJson,
                             String runByUser, LocalDateTime now) {
        Optional<Model> existing = modelRepository.findByModelIdAndPlatformAndConfiguredSourceId(
                modelId, platform, configuredSourceId);

        boolean isNewModel = existing.isEmpty();
        Model model = existing.orElseGet(Model::new);

        if (model.getId() == null) {
            model.setCreatedBy(runByUser);
            model.setCreatedOn(now);
        }

        model.setModelId(modelId);
        model.setModelName(modelName);
        model.setPlatform(platform);
        model.setConfiguredSourceId(configuredSourceId);
        model.setFetchedModelPrimaryData(primaryJson);
        model.setModelDataUpdatedOn(now);
        model.setIsActive(true);
        model.setUpdatedBy(runByUser);
        model.setUpdatedOn(now);
        modelRepository.save(model);

        if (isNewModel) {
            auditHistoryService.logEvent(
                    "Discovered", "modelId", modelId,
                    String.format("Model discovered during %s sync from source: %s",
                            platform, configuredSourceId),
                    runByUser);
            log.info("[MODEL SERVICE] Audit logged: new model discovered — {}", modelId);
        }
    }

    private String extractModelId(
            Map<String, Object> modelMap, String platform, String configuredSourceId) {

        if (SNOWFLAKE.equalsIgnoreCase(platform)) {
            String db     = Objects.toString(modelMap.get(DATABASE), "").trim();
            String schema  = Objects.toString(modelMap.get(SCHEMA), "").trim();
            String name    = Objects.toString(modelMap.get(MODEL_NAME), "").trim();
            if (db.isEmpty() || schema.isEmpty() || name.isEmpty()) return null;
            return (configuredSourceId + "." + db + "." + schema + "." + name).toUpperCase();
        }

        if (SAGEMAKER.equalsIgnoreCase(platform)) {
            Object smName = modelMap.get(MODEL_NAME);
            return smName == null ? null : smName.toString();
        }

        Object id = modelMap.getOrDefault(ID, modelMap.get(MODEL_ID));
        return id == null ? null : id.toString();
    }

    private List<Map<String, Object>> jsonToList(JsonNode node) {
        if (node == null || node.isNull()) return new ArrayList<>();
        if (node.isArray()) return objectMapper.convertValue(node, List.class);
        if (node.isObject()) {
            for (JsonNode val : node) {
                if (val.isArray()) return objectMapper.convertValue(val, List.class);
            }
        }
        return new ArrayList<>();
    }

    private ObjectNode loadNode(JsonNode node) {
        return (node != null && node.isObject())
                ? (ObjectNode) node
                : objectMapper.createObjectNode();
    }
}