# =========================================
# 1: 스프링부트 애플리케이션 빌드 스테이지
# =========================================
FROM eclipse-temurin:21-jdk-noble AS builder

WORKDIR /app

# Gradle 래퍼 및 빌드 설정 파일만 먼저 복사
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./

# 의존성만 먼저 다운로드 (레이어 캐싱 + BuildKit 캐시 마운트 활용)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon || true

COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew clean bootJar -x test --no-daemon

# =========================================
# 2: 실행 스테이지
# =========================================
FROM eclipse-temurin:21-jre-noble AS runner

WORKDIR /app

# 보안을 위해 root가 아닌 일반 실행 사용자로 실행
RUN adduser --disabled-password --gecos "" springuser
USER springuser

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
