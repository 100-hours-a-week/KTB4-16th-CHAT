package com.ktb4.team16.mulo.chat.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

class ChatMessageWordFilterTest {

    private final ChatMessageWordFilter filter = new ChatMessageWordFilter(
            new ObjectMapper(), new DefaultResourceLoader());

    @Test
    @DisplayName("2026-11-03 정책의 네 카테고리 단어를 감지한다")
    void detectsWordsFromEveryPolicyCategory() {
        assertThat(filter.filter("시발").hasBadWord()).isTrue();
        assertThat(filter.filter("한남충").hasBadWord()).isTrue();
        assertThat(filter.filter("섹스").hasBadWord()).isTrue();
        assertThat(filter.filter("죽여버").hasBadWord()).isTrue();
    }

    @Test
    @DisplayName("숫자, 공백, 문장부호, 대소문자와 한글 자모로 변형한 표현을 감지한다")
    void detectsNormalizedVariants() {
        assertThat(filter.filter("아 시1발 진짜").hasBadWord()).isTrue();
        assertThat(filter.filter("씨 발").hasBadWord()).isTrue();
        assertThat(filter.filter("시.발").hasBadWord()).isTrue();
        assertThat(filter.filter("TLQKF").hasBadWord()).isTrue();
        assertThat(filter.filter("ㅅ ㅂ").hasBadWord()).isTrue();
        assertThat(filter.filter("시발").hasBadWord()).isTrue();
    }

    @Test
    @DisplayName("정책 예외에 완전히 포함된 표현은 허용하고 별도 욕설은 계속 감지한다")
    void appliesExceptionsOnlyToCoveredMatches() {
        assertThat(filter.filter("시발점부터 다시 보자").hasBadWord()).isFalse();
        assertThat(filter.filter("한남동에서 만나자").hasBadWord()).isFalse();
        assertThat(filter.filter("병신년의 간지는 언제야").hasBadWord()).isFalse();
        assertThat(filter.filter("시발점 얘기 말고 시발").hasBadWord()).isTrue();
    }

    @Test
    @DisplayName("정규화가 문장 구분 문장부호도 제거하므로 경계를 가로지르는 매칭을 수행한다")
    void appliesConfiguredNormalizationAcrossSentencePunctuation() {
        assertThat(filter.filter("시. 발표 자료").hasBadWord()).isTrue();
        assertThat(filter.filter("회의를 마쳤다. 발표는 내일이다.").hasBadWord()).isFalse();
    }

    @Test
    @DisplayName("스팸 정규식은 전화번호, 링크, 오픈채팅과 홍보 패턴을 원문에서 검사한다")
    void detectsConfiguredSpamPatternsAgainstOriginalText() {
        assertThat(filter.filter("010-1234-5678 연락주세요").isSpam()).isTrue();
        assertThat(filter.filter("공 일 공으로 연락주세요").isSpam()).isTrue();
        assertThat(filter.filter("www.example.test").isSpam()).isTrue();
        assertThat(filter.filter("bit . ly/offer").isSpam()).isTrue();
        assertThat(filter.filter("오픈 채팅 들어오세요").isSpam()).isTrue();
        assertThat(filter.filter("카톡 아이디 @mulo").isSpam()).isTrue();
        assertThat(filter.filter("계좌 123-456-789012").isSpam()).isTrue();
        assertThat(filter.filter("팔로워 늘려드립니다").isSpam()).isTrue();
        assertThat(filter.filter("입금하면 안내할게요").isSpam()).isTrue();
        assertThat(filter.filter("회의실 010호에서 만나요").isSpam()).isFalse();
    }

    @Test
    @DisplayName("Unicode 이모지와 surrogate pair가 포함된 입력을 안전하게 정규화한다")
    void handlesSupplementaryUnicodeCharacters() {
        assertThat(filter.filter("😀시1발😀").hasBadWord()).isTrue();
        assertThat(filter.filter("시발").hasBadWord()).isTrue();
        assertThat(filter.filter("🎵 보컬 고음 죽인다 🎵").hasBadWord()).isFalse();
        assertThat(filter.filter("🎵 안녕하세요 🎵").hasBadWord()).isFalse();
    }

    @Test
    @DisplayName("짧은 자모 단어는 부분 일치로 정상 문맥에서도 오탐될 수 있다")
    void recordsSubstringFalsePositiveRiskForShortJamoTerms() {
        assertThat(filter.filter("ㅅㅂㅅ 어쩌라고").hasBadWord()).isTrue();
        assertThat(filter.filter("ㅅㅅ 캡처했어").hasBadWord()).isTrue();
        assertThat(filter.filter("Essex 여행 사진").hasBadWord()).isFalse();
    }

    @Test
    @DisplayName("500자 입력에서도 스팸 정규식을 안전하게 적용한다")
    void processesMaximumLengthInputWithinBoundedTime() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(1), () -> filter.filter("가-".repeat(250)));
    }

    @Test
    @DisplayName("빈 문자열과 공백 문자열은 예외 없이 원문 그대로 반환한다")
    void acceptsEmptyAndWhitespaceInputAtFilterBoundary() {
        assertThat(filter.filter("")).isEqualTo(new ChatMessageWordFilter.Result(false, false));
        assertThat(filter.filter(" \t\n ")).isEqualTo(new ChatMessageWordFilter.Result(false, false));
    }
}
