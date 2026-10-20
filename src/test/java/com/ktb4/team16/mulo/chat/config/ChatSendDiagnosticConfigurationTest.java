package com.ktb4.team16.mulo.chat.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ChatSendDiagnosticConfigurationTest {

    private final ChatSendDiagnosticConfiguration configuration = new ChatSendDiagnosticConfiguration();

    @Test
    @DisplayName("개발 배포 표식이 있으면 기존 Spring 프로필과 관계없이 진단 응답을 활성화한다")
    void enablesDiagnosticsForDevelopmentDeploymentWithoutChangingProfiles() {
        ChatSendDiagnosticAvailability availability = configuration.chatSendDiagnosticAvailability(
                new ChatSendDiagnosticProperties(true, "development"), new MockEnvironment());

        assertThat(availability.enabled()).isTrue();
    }

    @Test
    @DisplayName("기존 비운영 Spring 프로필은 변경하지 않고 유지할 수 있다")
    void allowsDiagnosticsWithExistingDevelopmentProfile() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.profiles.active", "dev");

        ChatSendDiagnosticAvailability availability = configuration.chatSendDiagnosticAvailability(
                new ChatSendDiagnosticProperties(true, "development"), environment);

        assertThat(availability.enabled()).isTrue();
    }

    @Test
    @DisplayName("개발 배포 표식이 없으면 플래그가 켜져 있어도 진단 응답을 비활성화한다")
    void disablesDiagnosticsWhenDeploymentMarkerIsMissing() {
        ChatSendDiagnosticAvailability availability = configuration.chatSendDiagnosticAvailability(
                new ChatSendDiagnosticProperties(true, ""), new MockEnvironment());

        assertThat(availability.enabled()).isFalse();
    }

    @Test
    @DisplayName("개발이 아닌 배포 표식에서는 진단 응답을 비활성화한다")
    void disablesDiagnosticsOutsideDevelopmentDeployment() {
        ChatSendDiagnosticAvailability availability = configuration.chatSendDiagnosticAvailability(
                new ChatSendDiagnosticProperties(true, "production"), new MockEnvironment());

        assertThat(availability.enabled()).isFalse();
    }

    @Test
    @DisplayName("운영 Spring 프로필에서는 개발 표식이 있어도 진단 응답을 비활성화한다")
    void disablesDiagnosticsWhenProductionProfileIsActive() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.profiles.active", "production");

        ChatSendDiagnosticAvailability availability = configuration.chatSendDiagnosticAvailability(
                new ChatSendDiagnosticProperties(true, "development"), environment);

        assertThat(availability.enabled()).isFalse();
    }

    @Test
    @DisplayName("진단 플래그가 꺼져 있으면 개발 배포에서도 진단 응답을 비활성화한다")
    void disablesDiagnosticsWhenFeatureFlagIsFalse() {
        ChatSendDiagnosticAvailability availability = configuration.chatSendDiagnosticAvailability(
                new ChatSendDiagnosticProperties(false, "development"), new MockEnvironment());

        assertThat(availability.enabled()).isFalse();
    }
}
