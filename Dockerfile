# 1단계: 빌드 환경 (Builder Stage)
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /build

# 그래들 래퍼 및 설정 파일 복사
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# gradlew 실행 권한 부여
RUN chmod +x ./gradlew

# 소스 코드 복사
COPY src src

# 애플리케이션 빌드 (테스트 제외)
RUN ./gradlew clean build -x test

# 2단계: 실행 환경 (Runtime Stage)
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 타임존 설정을 위한 패키지 설치 (옵션이지만 한국 시간대 설정에 유용)
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Seoul /etc/localtime && \
    echo "Asia/Seoul" > /etc/timezone

# 빌드 스테이지에서 생성된 JAR 파일 복사
COPY --from=builder /build/build/libs/*-SNAPSHOT.jar app.jar

# 컨테이너 포트 노출
EXPOSE 8080

# 실행 시 prod 프로파일 강제 적용 (원할 경우 환경변수로 덮어쓰기 가능)
ENV SPRING_PROFILES_ACTIVE=prod

# 애플리케이션 실행
ENTRYPOINT ["java", "-jar", "app.jar"]
