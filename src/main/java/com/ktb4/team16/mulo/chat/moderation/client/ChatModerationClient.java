package com.ktb4.team16.mulo.chat.moderation.client;

import com.ktb4.team16.mulo.chat.moderation.config.ChatModerationProperties;
import com.ktb4.team16.mulo.chat.moderation.dto.ChatModerationRequest;
import com.ktb4.team16.mulo.chat.moderation.dto.ChatModerationResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

@Component
public class ChatModerationClient {

    private static final String MODERATION_PATH = "/api/moderation/check";

    private final HttpClient httpClient;
    private final ChatModerationProperties properties;
    private final ObjectMapper objectMapper;

    public ChatModerationClient(
            @Qualifier("chatModerationHttpClient") HttpClient httpClient,
            ChatModerationProperties properties,
            ObjectMapper objectMapper
    ) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean isToxic(Long roomId, Long userId, String text) {
        if (!properties.isConfigured()) {
            throw new ChatModerationUnavailableException();
        }

        String requestId = UUID.randomUUID().toString();
        ChatModerationRequest request = new ChatModerationRequest(
                requestId, roomId.toString(), userId.toString(), text);
        ChatModerationResponse response;
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(moderationUri())
                    .timeout(properties.responseTimeout())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .header("X-Internal-Token", properties.internalToken())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(request), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (httpResponse.statusCode() != 200) {
                throw new ChatModerationUnavailableException();
            }
            response = objectMapper.readValue(httpResponse.body(), ChatModerationResponse.class);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ChatModerationUnavailableException();
        } catch (IOException | RuntimeException exception) {
            throw new ChatModerationUnavailableException();
        }

        if (response == null
                || !StringUtils.hasText(response.requestId())
                || !requestId.equals(response.requestId())
                || response.isToxic() == null
                || response.confidence() == null
                || !Double.isFinite(response.confidence())
                || response.confidence() < 0.0
                || response.confidence() > 1.0
                || !StringUtils.hasText(response.modelVersion())) {
            throw new ChatModerationUnavailableException();
        }
        return response.isToxic();
    }

    private URI moderationUri() {
        String baseUrl = properties.baseUrl().replaceAll("/+$", "");
        return URI.create(baseUrl + MODERATION_PATH);
    }
}
