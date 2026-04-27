//package com.vistora.discovery.config;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.fasterxml.jackson.databind.SerializationFeature;
//import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.http.client.ClientHttpRequestInterceptor;
//import org.springframework.web.client.RestTemplate;
//
//import java.util.ArrayList;
//import java.util.List;
//
//@Slf4j
//@Configuration
//public class RestConfig {
//
//    @Bean(name = "tokenValidationRestTemplate")
//    public RestTemplate tokenValidationRestTemplate() {
//        log.info("Creating RestTemplate for token validation (no interceptors)");
//        return new RestTemplate();
//    }
//
//    @Bean
//    public RestTemplate restTemplate(AuthTokenInterceptor authTokenInterceptor,
//                                     SecretNameInterceptor secretNameInterceptor) {
//        log.info("Configuring RestTemplate with interceptors");
//
//        RestTemplate restTemplate = new RestTemplate();
//        List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
//        interceptors.add(authTokenInterceptor);
//        interceptors.add(secretNameInterceptor);
//        restTemplate.setInterceptors(interceptors);
//
//        log.info("RestTemplate configured successfully with {} interceptor(s)", interceptors.size());
//        return restTemplate;
//    }
//
//
//}

package com.vistora.discovery.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class RestConfig {

    private static final Logger log = LoggerFactory.getLogger(RestConfig.class);

    @Bean(name = "tokenValidationRestTemplate")
    public RestTemplate tokenValidationRestTemplate() {
        log.info("Creating RestTemplate for token validation (no interceptors)");
        return new RestTemplate();
    }

    @Bean
    public RestTemplate restTemplate(AuthTokenInterceptor authTokenInterceptor,
                                     SecretNameInterceptor secretNameInterceptor) {
        log.info("Configuring RestTemplate with interceptors");

        RestTemplate restTemplate = new RestTemplate();
        List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
        interceptors.add(authTokenInterceptor);
        interceptors.add(secretNameInterceptor);
        restTemplate.setInterceptors(interceptors);

        log.info("RestTemplate configured successfully with {} interceptor(s)", interceptors.size());
        return restTemplate;
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}

