package com.ktb4.team16.mulo.chat.auth;

import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(BackendAuthProperties.class)
public class BackendAuthClientConfiguration {

    @Bean
    RestClient backendAuthRestClient(BackendAuthProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.responseTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Bean
    Clock backendAuthClock() {
        return Clock.systemUTC();
    }
}
