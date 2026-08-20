package com.sangmyungyaho.barocare.routine.service;

import com.sangmyungyaho.barocare.ai.client.AiClient;
import com.sangmyungyaho.barocare.ai.client.ProductSearchClient;
import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.routine.dto.IngredientRecommendationDto;
import com.sangmyungyaho.barocare.routine.entity.IngredientRecommendation;
import com.sangmyungyaho.barocare.routine.entity.RecommendationStatus;
import com.sangmyungyaho.barocare.routine.repository.IngredientRecommendationRepository;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ISSUE-30 / 피부 분석 응답 속도 개선: 추천 성분/제품(IngredientRecommendationService) 단위 테스트.
 *
 * 핵심 검증 대상: (1) initializeTodayRecommendation의 PENDING row 선점(idempotent), (2) 같은
 * skinAnalysisId에 대한 재실행 방지(상태가 이미 PENDING이 아니면 건너뜀), (3) 성분 추천/제품 검색이
 * 단계별로 row를 갱신하며 각 단계의 성공/실패가 서로 독립적으로 추적된다는 점(성분 실패 시엔 제품도
 * 함께 FAILED - 제품 검색이 성분명을 입력으로 쓰는 실제 의존성 때문), (4) 상태 조회(getIngredientStatus/
 * getProductStatus/getTodayRecommendation)는 저장된 값만 읽고 AI/웹검색을 유발하지 않는다는 점이다.
 */
@ExtendWith(MockitoExtension.class)
class IngredientRecommendationServiceTest {

	private static final Long USER_ID = 1L;
	private static final Long SKIN_ANALYSIS_ID = 55L;

	@Mock
	private IngredientRecommendationRepository ingredientRecommendationRepository;
	@Mock
	private AiClient aiClient;
	@Mock
	private ProductSearchClient productSearchClient;
	@Mock
	private ObjectMapper objectMapper;

	private IngredientRecommendationService service;

	@org.junit.jupiter.api.BeforeEach
	void setUp() {
		service = new IngredientRecommendationService(ingredientRecommendationRepository, aiClient, productSearchClient, objectMapper);
	}

	@Test
	void initializeTodayRecommendation은_row가_없으면_PENDING으로_새로_만든다() {
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID)).thenReturn(Optional.empty());

		service.initializeTodayRecommendation(USER_ID, skinAnalysis());

		ArgumentCaptor<IngredientRecommendation> captor = ArgumentCaptor.forClass(IngredientRecommendation.class);
		verify(ingredientRecommendationRepository).save(captor.capture());
		assertThat(captor.getValue().getIngredientStatus()).isEqualTo(RecommendationStatus.PENDING);
		assertThat(captor.getValue().getProductStatus()).isEqualTo(RecommendationStatus.PENDING);
	}

	@Test
	void initializeTodayRecommendation은_이미_row가_있으면_새로_만들지_않는다() {
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID))
				.thenReturn(Optional.of(mock(IngredientRecommendation.class)));

		service.initializeTodayRecommendation(USER_ID, skinAnalysis());

		verify(ingredientRecommendationRepository, never()).save(any());
	}

	@Test
	void 이미_처리됐거나_처리중인_추천이_있으면_AI와_웹검색을_전혀_호출하지_않는다() {
		IngredientRecommendation existing = mock(IngredientRecommendation.class);
		when(existing.getIngredientStatus()).thenReturn(RecommendationStatus.COMPLETED);
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID)).thenReturn(Optional.of(existing));

		service.generateTodayRecommendation(USER_ID, skinAnalysis());

		verifyNoInteractions(aiClient, productSearchClient);
		verify(ingredientRecommendationRepository, never()).save(any());
	}

	@Test
	void 성분_추천과_제품_검색이_모두_성공하면_둘_다_COMPLETED로_저장된다() {
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID)).thenReturn(Optional.empty());
		when(ingredientRecommendationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(objectMapper.writeValueAsString(any())).thenReturn("[]");
		when(aiClient.recommendIngredients(any(), any(), any())).thenReturn(
				new AiDto.IngredientRecommendationResult(List.of(new AiDto.IngredientSuggestion("판테놀", "진정과 보습에 도움을 줄 수 있습니다."))));
		when(productSearchClient.search(List.of("판테놀"))).thenReturn(List.of(
				new ProductSearchClient.ProductSuggestion("라로슈포제", "시카플라스트 밤 B5+", "판테놀", "관련 제품입니다.", "https://example.com/a")));

		service.generateTodayRecommendation(USER_ID, skinAnalysis());

		IngredientRecommendation last = lastSaved();
		assertThat(last.getUserId()).isEqualTo(USER_ID);
		assertThat(last.getSkinAnalysisId()).isEqualTo(SKIN_ANALYSIS_ID);
		assertThat(last.getIngredientStatus()).isEqualTo(RecommendationStatus.COMPLETED);
		assertThat(last.getProductStatus()).isEqualTo(RecommendationStatus.COMPLETED);
		assertThat(last.getIngredientsJson()).isNotNull();
		assertThat(last.getProductsJson()).isNotNull();
	}

	@Test
	void 성분_추천_AI_호출이_실패하면_제품_검색도_하지_않고_둘_다_FAILED로_저장된다() {
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID)).thenReturn(Optional.empty());
		when(ingredientRecommendationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(aiClient.recommendIngredients(any(), any(), any())).thenThrow(new RuntimeException("AI 실패"));

		service.generateTodayRecommendation(USER_ID, skinAnalysis());

		verifyNoInteractions(productSearchClient);
		IngredientRecommendation last = lastSaved();
		assertThat(last.getIngredientStatus()).isEqualTo(RecommendationStatus.FAILED);
		assertThat(last.getProductStatus()).isEqualTo(RecommendationStatus.FAILED); // 성분이 없어 제품 검색 자체가 불가능하므로 함께 FAILED
	}

	@Test
	void FAILED_상태_저장_자체가_예외를_던져도_비동기_메서드_밖으로_예외가_전파되지_않는다() {
		// 회귀 테스트(PROCESSING 영구 고착 버그): 배포 환경에서 ingredientStatus/productStatus가
		// PROCESSING에서 멈추는 현상의 원인이었다 - AI 호출은 실패해 FAILED로 되돌리려 했지만, 그
		// "FAILED로 되돌리는 save()" 자체가 DB 일시 장애(커넥션 풀 고갈 등)로 예외를 던지면 그 예외가
		// @Async 메서드 밖으로 새 나가 Spring 기본 핸들러가 조용히 삼켰다 - 이 경우 row는 그 직전에
		// 마지막으로 성공한 PROCESSING 상태에 영구히 머문다. 지금은 이 save() 실패를 흡수해서 메서드가
		// 예외 없이 정상 종료돼야 한다(=이후 어떤 이유로도 응답 스레드나 다른 로직에 영향을 주지 않음).
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID)).thenReturn(Optional.empty());
		when(ingredientRecommendationRepository.save(any()))
				.thenAnswer(invocation -> invocation.getArgument(0)) // find-or-create 최초 생성 저장 - 성공
				.thenAnswer(invocation -> invocation.getArgument(0)) // PROCESSING 표시 저장 - 성공
				.thenThrow(new RuntimeException("DB 커넥션 풀 고갈(일시 장애 시뮬레이션)")); // FAILED 표시 저장 - 실패
		when(aiClient.recommendIngredients(any(), any(), any())).thenThrow(new RuntimeException("AI 실패"));

		assertThatCode(() -> service.generateTodayRecommendation(USER_ID, skinAnalysis()))
				.doesNotThrowAnyException();

		verifyNoInteractions(productSearchClient);
	}

	@Test
	void AI가_성분_reason에_영어_상태값을_그대로_반환하면_FAILED로_저장하고_제품_검색은_하지_않는다() {
		// 프롬프트 지침을 어기고 GPT가 "POOR" 같은 내부 상태값을 reason에 그대로 섞어 반환한 상황을
		// 시뮬레이션한다. 성분 추천 실패와 동일하게 취급되어(캐치되는 RuntimeException) 제품 검색도
		// 하지 않고 둘 다 FAILED로 저장돼야 한다.
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID)).thenReturn(Optional.empty());
		when(ingredientRecommendationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(aiClient.recommendIngredients(any(), any(), any())).thenReturn(new AiDto.IngredientRecommendationResult(
				List.of(new AiDto.IngredientSuggestion("판테놀", "피부 상태가 POOR로 판정되어 진정에 도움이 됩니다."))));

		service.generateTodayRecommendation(USER_ID, skinAnalysis());

		verifyNoInteractions(productSearchClient);
		IngredientRecommendation last = lastSaved();
		assertThat(last.getIngredientStatus()).isEqualTo(RecommendationStatus.FAILED);
		assertThat(last.getProductStatus()).isEqualTo(RecommendationStatus.FAILED);
	}

	@Test
	void AI가_성분_name에_영어_상태값을_그대로_반환하면_FAILED로_저장하고_제품_검색은_하지_않는다() {
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID)).thenReturn(Optional.empty());
		when(ingredientRecommendationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(aiClient.recommendIngredients(any(), any(), any())).thenReturn(new AiDto.IngredientRecommendationResult(
				List.of(new AiDto.IngredientSuggestion("CAUTION 성분", "진정과 보습에 도움을 줄 수 있습니다."))));

		service.generateTodayRecommendation(USER_ID, skinAnalysis());

		verifyNoInteractions(productSearchClient);
		IngredientRecommendation last = lastSaved();
		assertThat(last.getIngredientStatus()).isEqualTo(RecommendationStatus.FAILED);
	}

	@Test
	void 제품_검색이_실패해도_성분_추천은_COMPLETED로_유지되고_제품만_FAILED된다() {
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID)).thenReturn(Optional.empty());
		when(ingredientRecommendationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(objectMapper.writeValueAsString(any())).thenReturn("[]");
		when(aiClient.recommendIngredients(any(), any(), any())).thenReturn(
				new AiDto.IngredientRecommendationResult(List.of(new AiDto.IngredientSuggestion("판테놀", "진정과 보습에 도움을 줄 수 있습니다."))));
		when(productSearchClient.search(any())).thenThrow(new RuntimeException("웹검색 실패"));

		service.generateTodayRecommendation(USER_ID, skinAnalysis());

		IngredientRecommendation last = lastSaved();
		assertThat(last.getIngredientStatus()).isEqualTo(RecommendationStatus.COMPLETED);
		assertThat(last.getIngredientsJson()).isNotNull();
		assertThat(last.getProductStatus()).isEqualTo(RecommendationStatus.FAILED);
	}

	@Test
	void getIngredientStatus는_row가_없으면_PENDING과_빈_배열을_반환한다() {
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID)).thenReturn(Optional.empty());

		var result = service.getIngredientStatus(SKIN_ANALYSIS_ID);

		assertThat(result.status()).isEqualTo(RecommendationStatus.PENDING);
		assertThat(result.ingredients()).isEmpty();
	}

	@Test
	void getIngredientStatus는_COMPLETED면_저장된_JSON을_파싱해서_반환한다() {
		IngredientRecommendation saved = new IngredientRecommendation(USER_ID, SKIN_ANALYSIS_ID, LocalDate.now());
		saved.completeIngredients("ingredients-json");
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID)).thenReturn(Optional.of(saved));
		var ingredient = new IngredientRecommendationDto.IngredientItem("판테놀", "진정 관리에 도움을 줄 수 있습니다.");
		when(objectMapper.readValue(eq("ingredients-json"), org.mockito.ArgumentMatchers.<tools.jackson.core.type.TypeReference<List<IngredientRecommendationDto.IngredientItem>>>any()))
				.thenReturn(List.of(ingredient));

		var result = service.getIngredientStatus(SKIN_ANALYSIS_ID);

		assertThat(result.status()).isEqualTo(RecommendationStatus.COMPLETED);
		assertThat(result.ingredients()).containsExactly(ingredient);
	}

	@Test
	void getIngredientStatus는_레거시_저장데이터에_내부_상태값이_남아있어도_자연어로_치환해_반환한다() {
		// 1차 방어선 도입 이전에 저장된 레거시 ingredients-json을 흉내낸다 - 조회 시점
		// (UserFacingTextGuard.sanitize)에서 걸러져야 한다.
		IngredientRecommendation saved = new IngredientRecommendation(USER_ID, SKIN_ANALYSIS_ID, LocalDate.now());
		saved.completeIngredients("ingredients-json");
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID)).thenReturn(Optional.of(saved));
		var legacyIngredient = new IngredientRecommendationDto.IngredientItem("판테놀", "피부 상태가 POOR로 판정되어 CAUTION이 필요합니다.");
		when(objectMapper.readValue(eq("ingredients-json"), org.mockito.ArgumentMatchers.<tools.jackson.core.type.TypeReference<List<IngredientRecommendationDto.IngredientItem>>>any()))
				.thenReturn(List.of(legacyIngredient));

		var result = service.getIngredientStatus(SKIN_ANALYSIS_ID);

		assertThat(result.ingredients()).hasSize(1);
		assertThat(result.ingredients().get(0).reason()).isEqualTo("피부 상태가 부족로 판정되어 주의이 필요합니다.");
	}

	@Test
	void getProductStatus는_레거시_저장데이터에_내부_상태값이_남아있어도_자연어로_치환해_반환한다() {
		IngredientRecommendation saved = new IngredientRecommendation(USER_ID, SKIN_ANALYSIS_ID, LocalDate.now());
		saved.completeProducts("products-json");
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID)).thenReturn(Optional.of(saved));
		var legacyProduct = new IngredientRecommendationDto.ProductItem(
				"라로슈포제", "시카플라스트 밤 B5+", "판테놀", "DANGER 수준의 피부에도 SAFE합니다.", "https://example.com/a");
		when(objectMapper.readValue(eq("products-json"), org.mockito.ArgumentMatchers.<tools.jackson.core.type.TypeReference<List<IngredientRecommendationDto.ProductItem>>>any()))
				.thenReturn(List.of(legacyProduct));

		var result = service.getProductStatus(SKIN_ANALYSIS_ID);

		assertThat(result.products()).hasSize(1);
		assertThat(result.products().get(0).reason()).isEqualTo("위험 수준의 피부에도 안전합니다.");
		assertThat(result.products().get(0).productUrl()).isEqualTo("https://example.com/a"); // URL은 치환 대상이 아니다
	}

	@Test
	void getIngredientStatus는_PROCESSING이면_빈_배열과_함께_상태만_반환한다() {
		IngredientRecommendation saved = new IngredientRecommendation(USER_ID, SKIN_ANALYSIS_ID, LocalDate.now());
		saved.markIngredientProcessing();
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID)).thenReturn(Optional.of(saved));

		var result = service.getIngredientStatus(SKIN_ANALYSIS_ID);

		assertThat(result.status()).isEqualTo(RecommendationStatus.PROCESSING);
		assertThat(result.ingredients()).isEmpty();
		verifyNoInteractions(objectMapper);
	}

	@Test
	void getProductStatus는_FAILED면_빈_배열과_함께_상태만_반환한다() {
		IngredientRecommendation saved = new IngredientRecommendation(USER_ID, SKIN_ANALYSIS_ID, LocalDate.now());
		saved.failIngredients();
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID)).thenReturn(Optional.of(saved));

		var result = service.getProductStatus(SKIN_ANALYSIS_ID);

		assertThat(result.status()).isEqualTo(RecommendationStatus.FAILED);
		assertThat(result.products()).isEmpty();
	}

	@Test
	void 오늘_추천이_없으면_빈_결과를_반환한다() {
		when(ingredientRecommendationRepository.findTopByUserIdAndRecommendationDateOrderByCreatedAtDesc(USER_ID, LocalDate.now()))
				.thenReturn(Optional.empty());

		IngredientRecommendationService.TodayRecommendation result = service.getTodayRecommendation(USER_ID);

		assertThat(result.ingredients()).isEmpty();
		assertThat(result.products()).isEmpty();
		verifyNoInteractions(aiClient, productSearchClient);
	}

	@Test
	void 오늘_추천이_아직_진행중이면_빈_결과를_반환한다() {
		// json이 아직 null인 PROCESSING 상태에서도 예외 없이 빈 목록으로 안전하게 처리돼야 한다.
		IngredientRecommendation processing = new IngredientRecommendation(USER_ID, SKIN_ANALYSIS_ID, LocalDate.now());
		processing.markIngredientProcessing();
		when(ingredientRecommendationRepository.findTopByUserIdAndRecommendationDateOrderByCreatedAtDesc(USER_ID, LocalDate.now()))
				.thenReturn(Optional.of(processing));

		IngredientRecommendationService.TodayRecommendation result = service.getTodayRecommendation(USER_ID);

		assertThat(result.ingredients()).isEmpty();
		assertThat(result.products()).isEmpty();
	}

	@Test
	void 오늘_추천이_완료됐으면_저장된_JSON을_파싱해서_반환한다() {
		IngredientRecommendation saved = new IngredientRecommendation(USER_ID, SKIN_ANALYSIS_ID, LocalDate.now());
		saved.completeIngredients("ingredients-json");
		saved.completeProducts("products-json");
		when(ingredientRecommendationRepository.findTopByUserIdAndRecommendationDateOrderByCreatedAtDesc(USER_ID, LocalDate.now()))
				.thenReturn(Optional.of(saved));

		var ingredient = new IngredientRecommendationDto.IngredientItem("판테놀", "진정 관리에 도움을 줄 수 있습니다.");
		var product = new IngredientRecommendationDto.ProductItem("라로슈포제", "시카플라스트 밤 B5+", "판테놀", "관련 제품입니다.", "https://example.com/a");
		when(objectMapper.readValue(eq("ingredients-json"), org.mockito.ArgumentMatchers.<tools.jackson.core.type.TypeReference<List<IngredientRecommendationDto.IngredientItem>>>any()))
				.thenReturn(List.of(ingredient));
		when(objectMapper.readValue(eq("products-json"), org.mockito.ArgumentMatchers.<tools.jackson.core.type.TypeReference<List<IngredientRecommendationDto.ProductItem>>>any()))
				.thenReturn(List.of(product));

		IngredientRecommendationService.TodayRecommendation result = service.getTodayRecommendation(USER_ID);

		assertThat(result.ingredients()).containsExactly(ingredient);
		assertThat(result.products()).containsExactly(product);
	}

	private SkinAnalysis skinAnalysis() {
		SkinAnalysis skinAnalysis = mock(SkinAnalysis.class);
		when(skinAnalysis.getId()).thenReturn(SKIN_ANALYSIS_ID);
		org.mockito.Mockito.lenient().when(skinAnalysis.getAnalyzedAt()).thenReturn(LocalDateTime.of(2026, 8, 19, 10, 0));
		org.mockito.Mockito.lenient().when(skinAnalysis.getRednessLevel()).thenReturn(SkinAnalysisLevel.CAUTION);
		org.mockito.Mockito.lenient().when(skinAnalysis.getTroubleLevel()).thenReturn(SkinAnalysisLevel.SAFE);
		org.mockito.Mockito.lenient().when(skinAnalysis.getSkinLevel()).thenReturn(SkinAnalysisLevel.CAUTION);
		return skinAnalysis;
	}

	private IngredientRecommendation lastSaved() {
		ArgumentCaptor<IngredientRecommendation> captor = ArgumentCaptor.forClass(IngredientRecommendation.class);
		verify(ingredientRecommendationRepository, atLeastOnce()).save(captor.capture());
		return captor.getValue();
	}
}
