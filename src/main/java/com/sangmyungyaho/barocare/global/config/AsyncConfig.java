package com.sangmyungyaho.barocare.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 추천 성분/추천 제품 백그라운드 생성(IngredientRecommendationService.generateTodayRecommendation)에
 * 쓰는 전용 스레드풀. POST /skin-analyses는 피부 분석 + 원인 분석 + 오늘의 루틴까지만 끝나면 즉시
 * 응답하고, 그 이후의 추천 성분/제품 생성(OpenAI 호출 2단계)은 이 풀에서 백그라운드로 처리된다.
 *
 * Boot 기본 비동기 실행기({@code SimpleAsyncTaskExecutor})는 태스크마다 스레드를 무제한으로 새로
 * 만들어 운영 환경에서 위험하므로, 크기가 제한된 {@link ThreadPoolTaskExecutor}를 명시적으로 만들어
 * {@code @Async("recommendationExecutor")}로 지정해 쓴다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

	@Bean(name = "recommendationExecutor")
	public Executor recommendationExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(8);
		executor.setQueueCapacity(50);
		executor.setThreadNamePrefix("recommendation-");
		executor.initialize();
		return executor;
	}
}
