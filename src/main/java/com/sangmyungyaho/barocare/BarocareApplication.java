package com.sangmyungyaho.barocare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class BarocareApplication {

	// 서비스 전역 타임존을 Asia/Seoul로 고정한다. @PostConstruct(빈 초기화 순서에 의존)가 아니라
	// 정적 초기화 블록을 쓰는 이유: 이 블록은 이 클래스가 로드되는 시점(=main() 진입 직후, 아직
	// SpringApplication.run()도 호출되기 전)에 실행되므로, DataSource/HikariCP/Hibernate
	// SessionFactory 같은 다른 빈이 먼저 초기화되면서 그 시점의 JVM 기본 타임존(도커 컨테이너의
	// 기본값인 UTC)을 참조/캐시해버리는 초기화 순서 문제를 원천적으로 배제한다.
	// Dockerfile의 -Duser.timezone=Asia/Seoul 옵션이 이보다도 먼저(JVM 부팅 자체 시점) 적용되므로
	// 운영 환경에서는 사실 이 블록이 실행되기도 전에 이미 Asia/Seoul이지만, 로컬 gradle bootRun/test처럼
	// 그 JVM 옵션 없이 실행되는 경로에서도 동일하게 동작하도록 여기서도 명시적으로 고정한다.
	static {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
	}

	public static void main(String[] args) {
		SpringApplication.run(BarocareApplication.class, args);
	}

}