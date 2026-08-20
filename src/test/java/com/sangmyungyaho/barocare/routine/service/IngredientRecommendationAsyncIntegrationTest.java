package com.sangmyungyaho.barocare.routine.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sangmyungyaho.barocare.ai.client.AiClient;
import com.sangmyungyaho.barocare.ai.client.ProductSearchClient;
import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.routine.entity.IngredientRecommendation;
import com.sangmyungyaho.barocare.routine.entity.RecommendationStatus;
import com.sangmyungyaho.barocare.routine.repository.IngredientRecommendationRepository;
import com.sangmyungyaho.barocare.skin.entity.ImageQualityRating;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.skin.entity.SkinImage;
import com.sangmyungyaho.barocare.skin.repository.SkinAnalysisRepository;
import com.sangmyungyaho.barocare.skin.repository.SkinImageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 추천 성분/제품 백그라운드 생성(@Async)의 실제 동작 검증(운영 환경 "PENDING에서 멈춤" 문제 재현/확인용).
 *
 * 기존 IngredientRecommendationServiceTest는 순수 Mockito 단위 테스트라 IngredientRecommendationService
 * 자체를 목이 아니라 실제 객체로 두고 메서드 호출만 검증한다 - Spring이 @Async를 실제로 백그라운드
 * 스레드로 디스패치하는지, AsyncConfig의 recommendationExecutor 빈이 정상 동작하는지는 그 테스트로는
 * 전혀 검증되지 않는다(프록시를 거치지 않기 때문). 이 테스트는 실제 Spring 컨텍스트(+실행 중인 MySQL)를
 * 띄우고 진짜 @Async 프록시/스레드풀을 그대로 써서, initializeTodayRecommendation()으로 만든 PENDING
 * row가 generateTodayRecommendation() 호출 후 실제로 PROCESSING을 거쳐 COMPLETED까지 전이되는지 확인한다.
 * AiClient/ProductSearchClient(외부 I/O)만 목으로 대체한다.
 *
 * UserFlowE2ETest와 달리 @Transactional을 쓰지 않는다 - 그걸 쓰면 이 테스트 메서드 전체가 하나의
 * 트랜잭션/커넥션에 묶여 끝날 때 롤백되는데, 그러면 별도 스레드(recommendationExecutor)에서 도는
 * @Async 작업이 이 트랜잭션의 커밋 전 데이터를 보지 못한다(그래서 UserFlowE2ETest는 recommendationExecutor
 * 자체를 동기 실행기로 바꿔치기했다). 여기서는 반대로 "실제 비동기 스레드가 실제로 도는지" 자체가
 * 검증 대상이므로 진짜 스레드풀을 그대로 쓰고, 대신 각 저장이 즉시 커밋되도록 @Transactional을 붙이지
 * 않았다(테스트 종료 후 직접 정리한다).
 */
@SpringBootTest
class IngredientRecommendationAsyncIntegrationTest {

	@Autowired
	private IngredientRecommendationService ingredientRecommendationService;
	@Autowired
	private IngredientRecommendationRepository ingredientRecommendationRepository;
	@Autowired
	private SkinAnalysisRepository skinAnalysisRepository;
	@Autowired
	private SkinImageRepository skinImageRepository;

	@MockitoBean
	private AiClient aiClient;
	@MockitoBean
	private ProductSearchClient productSearchClient;

	private ListAppender<ILoggingEvent> logAppender;
	private Long skinAnalysisId;

	@BeforeEach
	void setUp() {
		// IngredientRecommendationService의 실제 로그 출력을 가로채 "[SkinAnalysis] recommendation
		// background task started" 로그가 정말 찍히는지 직접 검증한다(운영 로그를 볼 수 없는 상황을
		// 코드로 재현/증명하기 위함).
		logAppender = new ListAppender<>();
		logAppender.start();
		((Logger) LoggerFactory.getLogger(IngredientRecommendationService.class)).addAppender(logAppender);
	}

	@AfterEach
	void tearDown() {
		((Logger) LoggerFactory.getLogger(IngredientRecommendationService.class)).detachAppender(logAppender);
		if (skinAnalysisId != null) {
			ingredientRecommendationRepository.findBySkinAnalysisId(skinAnalysisId)
					.ifPresent(ingredientRecommendationRepository::delete);
		}
	}

	@Test
	void 실제_Async_스레드에서_PENDING이_PROCESSING을_거쳐_COMPLETED로_전이된다() {
		long testUserId = System.nanoTime();
		SkinImage skinImage = skinImageRepository.save(new SkinImage(testUserId, "http://test/x.jpg", "async-test.jpg"));
		SkinAnalysis skinAnalysis = skinAnalysisRepository.save(new SkinAnalysis(
				testUserId, skinImage,
				SkinAnalysisLevel.CAUTION, List.of(), null,
				SkinAnalysisLevel.SAFE, List.of(), null,
				SkinAnalysisLevel.CAUTION,
				ImageQualityRating.GOOD, ImageQualityRating.GOOD, ImageQualityRating.GOOD, ImageQualityRating.GOOD,
				"async-test-v1"
		));
		skinAnalysisId = skinAnalysis.getId();

		when(aiClient.recommendIngredients(any(), any(), any())).thenReturn(new AiDto.IngredientRecommendationResult(
				List.of(new AiDto.IngredientSuggestion("판테놀", "진정과 보습에 도움을 줄 수 있어요."))));
		when(productSearchClient.search(List.of("판테놀"))).thenReturn(List.of(
				new ProductSearchClient.ProductSuggestion("라로슈포제", "시카플라스트 밤 B5+", "판테놀", "관련 제품입니다.", "https://example.com/a")));

		// SkinAnalysisService.analyzeSkin()과 동일한 순서: 동기로 PENDING row를 먼저 만들고, @Async를 트리거한다.
		ingredientRecommendationService.initializeTodayRecommendation(testUserId, skinAnalysis);
		IngredientRecommendation pending = ingredientRecommendationRepository.findBySkinAnalysisId(skinAnalysisId).orElseThrow();
		assertThat(pending.getIngredientStatus()).isEqualTo(RecommendationStatus.PENDING);
		assertThat(pending.getProductStatus()).isEqualTo(RecommendationStatus.PENDING);

		ingredientRecommendationService.generateTodayRecommendation(testUserId, skinAnalysis);

		// generateTodayRecommendation()은 @Async라 호출 즉시 반환된다 - 별도 스레드에서 실제로 완료될
		// 때까지 폴링한다. 목 응답이라 실제로는 수십~수백ms 안에 끝나지만, 스레드풀 스케줄링 여유를 두고
		// 최대 10초까지 기다린다(실패 시 이 타임아웃 자체가 "비동기 작업이 아예 시작되지 않았다"는 신호).
		IngredientRecommendation finalState = awaitTerminalState(skinAnalysisId, Duration.ofSeconds(10));

		assertThat(finalState.getIngredientStatus()).isEqualTo(RecommendationStatus.COMPLETED);
		assertThat(finalState.getProductStatus()).isEqualTo(RecommendationStatus.COMPLETED);
		assertThat(finalState.getIngredientsJson()).isNotNull();
		assertThat(finalState.getProductsJson()).isNotNull();

		// 운영에서 확인해달라고 요청한 바로 그 로그 라인이 실제로 찍히는지 직접 검증한다.
		boolean startedLogPresent = logAppender.list.stream()
				.anyMatch(event -> event.getFormattedMessage().contains("recommendation background task started"));
		assertThat(startedLogPresent)
				.as("[SkinAnalysis] recommendation background task started 로그가 찍혀야 한다")
				.isTrue();
	}

	private IngredientRecommendation awaitTerminalState(Long skinAnalysisId, Duration timeout) {
		Instant deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			Optional<IngredientRecommendation> current = ingredientRecommendationRepository.findBySkinAnalysisId(skinAnalysisId);
			if (current.isPresent()) {
				RecommendationStatus ingredientStatus = current.get().getIngredientStatus();
				RecommendationStatus productStatus = current.get().getProductStatus();
				boolean ingredientDone = ingredientStatus == RecommendationStatus.COMPLETED || ingredientStatus == RecommendationStatus.FAILED;
				boolean productDone = productStatus == RecommendationStatus.COMPLETED || productStatus == RecommendationStatus.FAILED;
				if (ingredientDone && productDone) {
					return current.get();
				}
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("대기 중 인터럽트됨", e);
			}
		}
		throw new AssertionError("추천 상태가 " + timeout.getSeconds() + "초 안에 COMPLETED/FAILED로 끝나지 않음(PENDING/PROCESSING에 멈춰있을 가능성) - skinAnalysisId=" + skinAnalysisId);
	}

}
