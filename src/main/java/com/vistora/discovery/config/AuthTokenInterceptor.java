package com.vistora.discovery.config;

import lombok.RequiredArgsConstructor;
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
public class AuthTokenInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenInterceptor.class);

    private final RestTemplate tokenValidationRestTemplate;

    public AuthTokenInterceptor(RestTemplate tokenValidationRestTemplate) {
        this.tokenValidationRestTemplate = tokenValidationRestTemplate;
    }

    @Value("${token.validation.url:http://localhost:8080/common-service/api/auth/validate-token}")
    private String tokenValidationUrl;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        log.debug("Intercepting outgoing request to: {}", request.getURI());

        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        if (attributes == null) {
            log.error("No request attributes found, cannot extract Authorization token");
            throw new IOException("Unauthorized: no request context available");
        }

        String authToken = attributes.getRequest().getHeader("Authorization");

        if (authToken == null || authToken.isBlank()) {
            log.error("No Authorization token found in incoming request for URI: {}", request.getURI());
            throw new IOException("Unauthorized: missing Authorization token");
        }

        String accessToken = authToken.startsWith("Bearer ") ? authToken.substring(7) : authToken;

        log.debug("Validating token before forwarding request");
        boolean isValid = validateToken(accessToken);

        if (!isValid) {
            log.error("Token validation failed for request to: {}", request.getURI());
            throw new IOException("Unauthorized: invalid or expired token");
        }

        log.debug("Token validated successfully, adding to outgoing request");
        if (!authToken.startsWith("Bearer ")) {
            authToken = "Bearer " + authToken;
        }
        request.getHeaders().set("Authorization", authToken);

        ClientHttpResponse response = execution.execute(request, body);
        log.debug("Received response with status: {} for URI: {}", response.getStatusCode(), request.getURI());

        return response;
    }

    private boolean validateToken(String accessToken) {
        try {
            log.info("Calling token validation service: {}", tokenValidationUrl);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + accessToken);

            Map<String, String> requestBody = Map.of("accessToken", accessToken);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = tokenValidationRestTemplate.exchange(
                    tokenValidationUrl,
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Boolean isValid = (Boolean) response.getBody().get("valid");
                log.info("Token validation result: {}", isValid);

                if (Boolean.TRUE.equals(isValid)) {
                    log.debug("User details - Role: {}, Tenant: {}, Email: {}",
                            response.getBody().get("role"),
                            response.getBody().get("tenantName"),
                            response.getBody().get("workEmail"));
                }

                return Boolean.TRUE.equals(isValid);
            }

            log.warn("Invalid response from token validation service");
            return false;

        } catch (Exception e) {
            log.error("Error validating token: {}", e.getMessage(), e);
            return false;
        }
    }
}
