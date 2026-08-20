package com.sangmyungyaho.barocare;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
// SkinImageCleanupService의 미분석 이미지 정리 배치(@Scheduled)를 동작시키기 위해 활성화.
@EnableScheduling
public class BarocareApplication {

	@PostConstruct
	public void init() {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
	}

	public static void main(String[] args) {
		SpringApplication.run(BarocareApplication.class, args);
	}

}
