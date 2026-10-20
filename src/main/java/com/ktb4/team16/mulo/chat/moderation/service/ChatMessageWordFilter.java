package com.ktb4.team16.mulo.chat.moderation.service;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ChatMessageWordFilter {

    private static final int SPAM_PATTERN_FLAGS = Pattern.CASE_INSENSITIVE
            | Pattern.UNICODE_CASE
            | Pattern.UNICODE_CHARACTER_CLASS;

    private final List<String> words;
    private final List<String> exceptions;
    private final List<Pattern> spamPatterns;

    public ChatMessageWordFilter(ObjectMapper objectMapper, ResourceLoader resourceLoader) {
        FilterDefinition definition = loadDefinition(objectMapper, resourceLoader);
        this.words = definition.words().stream().distinct().toList();
        this.exceptions = List.copyOf(definition.exceptions());
        this.spamPatterns = definition.spamPatterns().stream()
                .map(pattern -> Pattern.compile(pattern, SPAM_PATTERN_FLAGS))
                .toList();
    }

    public Result filter(String text) {
        String normalized = normalize(text);
        return new Result(containsUnprotectedWord(normalized),
                spamPatterns.stream().anyMatch(pattern -> pattern.matcher(text).find()));
    }

    private boolean containsUnprotectedWord(String normalized) {
        boolean[] protectedCharacters = new boolean[normalized.length()];
        for (String exception : exceptions) {
            for (int index = normalized.indexOf(exception); index >= 0;
                    index = normalized.indexOf(exception, index + 1)) {
                java.util.Arrays.fill(protectedCharacters, index, index + exception.length(), true);
            }
        }
        for (String word : words) {
            for (int index = normalized.indexOf(word); index >= 0;
                    index = normalized.indexOf(word, index + 1)) {
                boolean protectedMatch = true;
                for (int position = index; position < index + word.length(); position++) {
                    if (!protectedCharacters[position]) {
                        protectedMatch = false;
                        break;
                    }
                }
                if (!protectedMatch) {
                    return true;
                }
            }
        }
        return false;
    }

    private String normalize(String text) {
        StringBuilder normalized = new StringBuilder();
        Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).codePoints().forEach(codePoint -> {
            if (isKept(codePoint)) {
                normalized.appendCodePoint(codePoint);
            }
        });
        return normalized.toString();
    }

    private boolean isKept(int codePoint) {
        return codePoint >= '가' && codePoint <= '힣'
                || codePoint >= 'ㄱ' && codePoint <= 'ㅎ'
                || codePoint >= 'ㅏ' && codePoint <= 'ㅣ'
                || codePoint >= 'a' && codePoint <= 'z';
    }

    private FilterDefinition loadDefinition(ObjectMapper objectMapper, ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource("classpath:badwords.json");
        try (InputStream input = resource.getInputStream()) {
            tools.jackson.databind.JsonNode root = objectMapper.readTree(input);
            tools.jackson.databind.JsonNode badwordCategories = root.path("badwords");
            tools.jackson.databind.JsonNode exceptionEntries = root.path("exceptions");
            tools.jackson.databind.JsonNode spamEntries = root.path("spam_regex");
            List<String> loadedWords = new java.util.ArrayList<>();
            badwordCategories.properties().forEach(category -> category.getValue().forEach(word -> {
                String value = word.asString();
                if (value.isBlank()) {
                    throw new IllegalStateException("욕설 필터 단어가 비어 있습니다.");
                }
                loadedWords.add(value);
            }));
            List<String> loadedExceptions = new java.util.ArrayList<>();
            exceptionEntries.forEach(exception -> {
                String value = exception.asString();
                if (value.isBlank()) {
                    throw new IllegalStateException("욕설 필터 예외 단어가 비어 있습니다.");
                }
                loadedExceptions.add(value);
            });
            List<String> loadedSpamPatterns = new java.util.ArrayList<>();
            spamEntries.forEach(entry -> {
                String value = entry.path("pattern").asString();
                if (value.isBlank()) {
                    throw new IllegalStateException("스팸 필터 정규식이 비어 있습니다.");
                }
                loadedSpamPatterns.add(value);
            });
            if (badwordCategories.isMissingNode() || badwordCategories.isEmpty()
                    || exceptionEntries.isMissingNode() || spamEntries.isMissingNode()
                    || loadedWords.isEmpty() || loadedExceptions.isEmpty() || loadedSpamPatterns.isEmpty()) {
                throw new IllegalStateException("욕설 필터 설정이 올바르지 않습니다.");
            }
            return new FilterDefinition(loadedWords, loadedExceptions, loadedSpamPatterns);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("욕설 필터 설정을 불러올 수 없습니다.", exception);
        }
    }

    public record Result(boolean hasBadWord, boolean isSpam) {
    }

    private record FilterDefinition(List<String> words, List<String> exceptions, List<String> spamPatterns) {
    }
}
