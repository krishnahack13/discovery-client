package com.vistora.discovery.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.Map;

@Component
public class SecretNameInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SecretNameInterceptor.class);

    private final RestTemplate tokenValidationRestTemplate;
    private final ObjectMapper objectMapper;

    public SecretNameInterceptor(RestTemplate tokenValidationRestTemplate, ObjectMapper objectMapper) {
        this.tokenValidationRestTemplate = tokenValidationRestTemplate;
        this.objectMapper = objectMapper;
    }

    @Value("${common.base-url:http://localhost:8080}")
    private String commonServiceBaseUrl;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {

        if (body != null && body.length > 0) {
            Map<String, Object> bodyMap;

            try {
                bodyMap = objectMapper.readValue(body, Map.class);
            } catch (Exception e) {
                log.warn("SecretNameInterceptor: Could not parse request body as JSON, forwarding as-is. Reason: {}", e.getMessage());
                return execution.execute(request, body);
            }

            if (bodyMap.containsKey("source")) {
                String source = (String) bodyMap.get("source");
                log.debug("Found 'source' in request body: {}, fetching secretName", source);

                String authToken = extractAuthToken(); // throws IOException if missing
                String secretName = fetchSecretName(source, authToken);
                log.info("Fetched secretName = {} for source: {}", secretName, source);

                bodyMap.put("secretName", secretName);
                byte[] modifiedBody = objectMapper.writeValueAsBytes(bodyMap);
                log.debug("Modified body with secretName: {}", new String(modifiedBody));

                request.getHeaders().setContentLength(modifiedBody.length);
                return execution.execute(request, modifiedBody); // real connection error will surface here
            }
        }

        return execution.execute(request, body);
    }

    private String extractAuthToken() throws IOException {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            String authHeader = attributes.getRequest().getHeader("Authorization");
            if (authHeader != null && !authHeader.isBlank()) {
                return authHeader.startsWith("Bearer ") ? authHeader : "Bearer " + authHeader;
            }
        }
        log.error("SecretNameInterceptor: No Authorization token found in incoming request");
        throw new IOException("Unauthorized: missing Authorization token");
    }

    private String fetchSecretName(String source, String authToken) {
        String url = commonServiceBaseUrl + "/common-service/api/v1/get-secretname";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", authToken);

        Map<String, String> requestBody = Map.of("source", source);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = tokenValidationRestTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                String.class
        );

        return response.getBody();
    }
}
