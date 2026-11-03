package com.ktb4.team16.mulo.chat.moderation.service;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ChatMessageWordFilter {

    private static final int SPAM_PATTERN_FLAGS = Pattern.CASE_INSENSITIVE
            | Pattern.UNICODE_CASE
            | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Set<String> REQUIRED_CATEGORIES = Set.of("ABUSE", "HATE", "SEXUAL", "THREAT");

    private final List<String> words;
    private final List<String> exceptions;
    private final List<Pattern> spamPatterns;
    private final Pattern removePattern;
    private final boolean lowercase;

    public ChatMessageWordFilter(ObjectMapper objectMapper, ResourceLoader resourceLoader) {
        FilterDefinition definition = loadDefinition(objectMapper, resourceLoader);
        this.words = definition.words().stream().distinct().toList();
        this.exceptions = List.copyOf(definition.exceptions());
        this.spamPatterns = definition.spamPatterns().stream()
                .map(pattern -> Pattern.compile(pattern, SPAM_PATTERN_FLAGS))
                .toList();
        this.removePattern = definition.removePattern();
        this.lowercase = definition.lowercase();
    }

    public Result filter(String text) {
        String normalized = normalize(text);
        boolean hasBadWord = containsUnprotectedWord(normalized);
        boolean isSpam = spamPatterns.stream().anyMatch(pattern -> pattern.matcher(text).find());
        return new Result(hasBadWord, isSpam);
    }

    private boolean containsUnprotectedWord(String normalized) {
        boolean[] protectedCharacters = new boolean[normalized.length()];
        for (Match exception : findMatches(normalized, exceptions)) {
            for (int index = exception.start(); index < exception.end(); index++) {
                protectedCharacters[index] = true;
            }
        }
        for (Match word : findMatches(normalized, words)) {
            if (!isProtected(word, protectedCharacters)) {
                return true;
            }
        }
        return false;
    }

    private List<Match> findMatches(String text, List<String> terms) {
        List<Match> matches = new ArrayList<>();
        for (String term : terms) {
            for (int index = text.indexOf(term); index >= 0; index = text.indexOf(term, index + 1)) {
                matches.add(new Match(index, index + term.length()));
            }
        }
        return matches;
    }

    private boolean isProtected(Match match, boolean[] protectedCharacters) {
        for (int index = match.start(); index < match.end(); index++) {
            if (!protectedCharacters[index]) {
                return false;
            }
        }
        return true;
    }

    private String normalize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        if (lowercase) {
            normalized = normalized.toLowerCase(Locale.ROOT);
        }
        return removePattern.matcher(normalized).replaceAll("");
    }

    private FilterDefinition loadDefinition(ObjectMapper objectMapper, ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource("classpath:badwords.json");
        try (InputStream input = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            JsonNode badwordCategories = root.path("badwords");
            JsonNode normalize = root.path("normalize");
            JsonNode exceptionEntries = root.path("exceptions");
            JsonNode spamEntries = root.path("spam_regex");
            if (!badwordCategories.isObject() || !normalize.isObject() || !exceptionEntries.isArray()
                    || !spamEntries.isArray()) {
                throw new IllegalStateException("욕설 필터 설정의 형식이 올바르지 않습니다.");
            }
            String removeRegex = normalize.path("remove_regex").asString();
            if (removeRegex == null || removeRegex.isBlank()) {
                throw new IllegalStateException("욕설 필터 설정이 올바르지 않습니다.");
            }
            Pattern configuredRemovePattern = Pattern.compile(removeRegex);
            boolean configuredLowercase = normalize.path("lowercase").asBoolean();
            Set<String> categories = new HashSet<>();
            List<String> loadedWords = new ArrayList<>();
            badwordCategories.properties().forEach(category -> {
                categories.add(category.getKey());
                if (!category.getValue().isArray()) {
                    throw new IllegalStateException("욕설 필터 단어 목록의 형식이 올바르지 않습니다.");
                }
                category.getValue().forEach(word -> loadedWords.add(normalizeDefinitionValue(
                        word.asString(), configuredRemovePattern, configuredLowercase)));
            });
            if (!categories.containsAll(REQUIRED_CATEGORIES) || loadedWords.isEmpty()) {
                throw new IllegalStateException("욕설 필터 카테고리가 누락되었습니다.");
            }
            List<String> loadedExceptions = new ArrayList<>();
            exceptionEntries.forEach(exception -> loadedExceptions.add(normalizeDefinitionValue(
                    exception.asString(), configuredRemovePattern, configuredLowercase)));
            List<String> loadedSpamPatterns = new ArrayList<>();
            spamEntries.forEach(entry -> {
                String value = entry.path("pattern").asString();
                if (value == null || value.isBlank()) {
                    throw new IllegalStateException("스팸 필터 정규식이 비어 있습니다.");
                }
                loadedSpamPatterns.add(value);
            });
            if (loadedSpamPatterns.isEmpty()) {
                throw new IllegalStateException("욕설 필터 설정이 올바르지 않습니다.");
            }
            return new FilterDefinition(loadedWords, loadedExceptions, loadedSpamPatterns,
                    configuredRemovePattern, configuredLowercase);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("욕설 필터 설정을 불러올 수 없습니다.", exception);
        }
    }

    private String normalizeDefinitionValue(String value, Pattern removePattern, boolean lowercase) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("욕설 필터 단어 또는 예외 단어가 비어 있습니다.");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        if (lowercase) {
            normalized = normalized.toLowerCase(Locale.ROOT);
        }
        String result = removePattern.matcher(normalized).replaceAll("");
        if (result.isBlank()) {
            throw new IllegalStateException("욕설 필터 단어 또는 예외 단어가 비어 있습니다.");
        }
        return result;
    }

    public record Result(boolean hasBadWord, boolean isSpam) {
    }

    private record Match(int start, int end) {
    }

    private record FilterDefinition(
            List<String> words,
            List<String> exceptions,
            List<String> spamPatterns,
            Pattern removePattern,
            boolean lowercase
    ) {
    }
}
