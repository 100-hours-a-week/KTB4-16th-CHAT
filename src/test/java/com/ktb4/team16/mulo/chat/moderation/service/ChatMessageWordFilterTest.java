package com.ktb4.team16.mulo.chat.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

class ChatMessageWordFilterTest {

    private final ChatMessageWordFilter filter = new ChatMessageWordFilter(
            new ObjectMapper(), new DefaultResourceLoader());

    @Test
    @DisplayName("공백과 숫자로 변형한 욕설을 감지한다")
    void detectsNormalizedBadWordVariants() {
        assertThat(filter.filter("아 시1발 진짜").hasBadWord()).isTrue();
        assertThat(filter.filter("씨 발").hasBadWord()).isTrue();
        assertThat(filter.filter("TLQKF").hasBadWord()).isTrue();
    }

    @Test
    @DisplayName("예외 단어에 완전히 포함된 욕설은 감지하지 않는다")
    void protectsConfiguredExceptionWords() {
        assertThat(filter.filter("시발점부터 다시 보자").hasBadWord()).isFalse();
        assertThat(filter.filter("수박씨 발라 먹어").hasBadWord()).isFalse();
    }

    @Test
    @DisplayName("일반적인 음악 감탄 표현은 목록에 없는 단독 표현을 차단하지 않는다")
    void allowsNormalMusicExpression() {
        assertThat(filter.filter("보컬 고음 죽인다").hasBadWord()).isFalse();
        assertThat(filter.filter("이 노래 개미쳤다").hasBadWord()).isFalse();
    }

    @Test
    @DisplayName("스팸 패턴은 원문에서 전화번호와 오픈채팅 링크를 감지한다")
    void detectsSpamAgainstOriginalText() {
        assertThat(filter.filter("010-1234-5678 연락주세요").isSpam()).isTrue();
        assertThat(filter.filter("오픈 채팅 들어오세요").isSpam()).isTrue();
        assertThat(filter.filter("www.example.test").isSpam()).isTrue();
    }

    @Test
    @DisplayName("Unicode 이모지와 surrogate pair를 안전하게 정규화한다")
    void handlesSupplementaryUnicodeCharacters() {
        assertThat(filter.filter("😀시1발😀").hasBadWord()).isTrue();
        assertThat(filter.filter("시발").hasBadWord()).isTrue();
        assertThat(filter.filter("🎵 보컬 고음 죽인다 🎵").hasBadWord()).isFalse();
    }

    @Test
    @DisplayName("목록의 짧은 초성 욕설은 포함 문자열에서 오탐될 수 있다")
    void documentsKnownShortJamoFalsePositive() {
        assertThat(filter.filter("ㅅㅂㅅ 어쩌라고").hasBadWord()).isTrue();
        assertThat(filter.filter("시발 점부터 다시 보자").hasBadWord()).isFalse();
    }

    @Test
    @DisplayName("500자 입력에서도 스팸 정규식을 안전하게 적용한다")
    void processesMaximumLengthInputWithinBoundedTime() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(1), () -> filter.filter("가-".repeat(250)));
    }

    @Test
    @DisplayName("빈 문자열과 공백 문자열은 필터에서 예외를 발생시키지 않는다")
    void acceptsEmptyAndWhitespaceInputAtFilterBoundary() {
        assertThat(filter.filter("")).isEqualTo(new ChatMessageWordFilter.Result(false, false));
        assertThat(filter.filter(" \t\n ")).isEqualTo(new ChatMessageWordFilter.Result(false, false));
    }
}
