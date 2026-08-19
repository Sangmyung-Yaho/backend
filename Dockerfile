FROM eclipse-temurin:17-jre

WORKDIR /app

COPY build/libs/*.jar app.jar

# -Duser.timezone: JVM이 부팅되는 가장 이른 시점부터 기본 타임존을 Asia/Seoul로 고정한다.
# 애플리케이션 코드(BarocareApplication의 static 블록)의 TimeZone.setDefault() 호출보다도 먼저
# 적용되므로, 어떤 Spring 빈이 먼저 초기화되어 JVM 기본존을 참조하더라도 항상 Asia/Seoul을 보게 된다.
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]