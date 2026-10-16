package com.ktb4.team16.mulo.chat.auth.client;

import com.ktb4.team16.mulo.chat.auth.config.BackendAuthProperties;
import com.ktb4.team16.mulo.chat.auth.dto.BackendAuthResponse;
import com.ktb4.team16.mulo.chat.auth.exception.BackendAuthException;
import com.ktb4.team16.mulo.chat.auth.service.BackendTokenVerifier;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class BackendAuthClient implements BackendTokenVerifier {

    private static final String VERIFY_PATH = "/internal/auth/verify";

    private final RestClient restClient;
    private final BackendAuthProperties properties;
    private final Clock clock;

    public BackendAuthClient(RestClient backendAuthRestClient, BackendAuthProperties properties, Clock clock) {
        this.restClient = backendAuthRestClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public BackendAuthResponse verify(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            throw BackendAuthException.unauthorized();
        }
        if (!properties.isConfigured()) {
            throw BackendAuthException.unavailable();
        }

        BackendAuthResponse response;
        try {
            response = restClient.post()
                    .uri(verificationUri())
                    .header("X-Internal-Api-Key", properties.internalApiKey())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(BackendAuthResponse.class);
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode().value() == 401) {
                throw BackendAuthException.unauthorized();
            }
            if (exception.getStatusCode().value() == 403) {
                throw BackendAuthException.forbidden();
            }
            throw BackendAuthException.invalidResponse();
        } catch (HttpServerErrorException | ResourceAccessException exception) {
            throw BackendAuthException.unavailable();
        } catch (RestClientException exception) {
            throw BackendAuthException.invalidResponse();
        }

        if (response == null
                || response.userId() == null
                || response.userId() <= 0
                || response.expiresAt() == null) {
            throw BackendAuthException.invalidResponse();
        }
        if (!response.expiresAt().isAfter(OffsetDateTime.now(clock))) {
            throw BackendAuthException.unauthorized();
        }
        return response;
    }

    private URI verificationUri() {
        String baseUrl = properties.url().replaceAll("/+$", "");
        return URI.create(baseUrl + VERIFY_PATH);
    }
}
