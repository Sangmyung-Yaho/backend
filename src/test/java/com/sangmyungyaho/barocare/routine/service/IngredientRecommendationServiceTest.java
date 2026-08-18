package com.sangmyungyaho.barocare.routine.service;

import com.sangmyungyaho.barocare.ai.client.AiClient;
import com.sangmyungyaho.barocare.ai.client.ProductSearchClient;
import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.routine.dto.IngredientRecommendationDto;
import com.sangmyungyaho.barocare.routine.entity.IngredientRecommendation;
import com.sangmyungyaho.barocare.routine.repository.IngredientRecommendationRepository;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ISSUE-30: 추천 성분/제품(IngredientRecommendationService) 단위 테스트.
 *
 * 핵심 검증 대상은 (1) 같은 skinAnalysisId에 대한 재호출 방지, (2) 성분 추천/제품 검색 각각의 실패가
 * 서로에게, 그리고 호출부(RoutineService)에 영향을 주지 않는 fallback, (3) 오늘 조회는 저장된 값만
 * 읽고 AI/웹검색을 유발하지 않는다는 점이다.
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
	void 이미_생성된_추천이_있으면_AI와_웹검색을_전혀_호출하지_않는다() {
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID))
				.thenReturn(Optional.of(mock(IngredientRecommendation.class)));

		service.generateTodayRecommendation(USER_ID, skinAnalysis());

		verifyNoInteractions(aiClient, productSearchClient);
		verify(ingredientRecommendationRepository, never()).save(any());
	}

	@Test
	void 성분_추천과_제품_검색이_모두_성공하면_함께_저장한다() {
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID)).thenReturn(Optional.empty());
		when(aiClient.recommendIngredients(any(), any(), any())).thenReturn(
				new AiDto.IngredientRecommendationResult(List.of(new AiDto.IngredientSuggestion("판테놀", "진정과 보습에 도움을 줄 수 있습니다."))));
		when(productSearchClient.search(List.of("판테놀"))).thenReturn(List.of(
				new ProductSearchClient.ProductSuggestion("라로슈포제", "시카플라스트 밤 B5+", "판테놀", "관련 제품입니다.", "https://example.com/a")));

		service.generateTodayRecommendation(USER_ID, skinAnalysis());

		verify(ingredientRecommendationRepository).save(argThatMatches(
				entity -> entity.getUserId().equals(USER_ID) && entity.getSkinAnalysisId().equals(SKIN_ANALYSIS_ID)));
	}

	@Test
	void 성분_추천_AI_호출이_실패하면_제품_검색도_하지_않고_아무것도_저장하지_않는다() {
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID)).thenReturn(Optional.empty());
		when(aiClient.recommendIngredients(any(), any(), any())).thenThrow(new RuntimeException("AI 실패"));

		service.generateTodayRecommendation(USER_ID, skinAnalysis());

		verifyNoInteractions(productSearchClient);
		verify(ingredientRecommendationRepository, never()).save(any());
	}

	@Test
	void 제품_검색이_실패해도_성분_추천은_저장된다() {
		when(ingredientRecommendationRepository.findBySkinAnalysisId(SKIN_ANALYSIS_ID)).thenReturn(Optional.empty());
		when(aiClient.recommendIngredients(any(), any(), any())).thenReturn(
				new AiDto.IngredientRecommendationResult(List.of(new AiDto.IngredientSuggestion("판테놀", "진정과 보습에 도움을 줄 수 있습니다."))));
		when(productSearchClient.search(any())).thenThrow(new RuntimeException("웹검색 실패"));

		service.generateTodayRecommendation(USER_ID, skinAnalysis());

		verify(ingredientRecommendationRepository).save(any());
	}

	@Test
	void 오늘_추천이_없으면_빈_결과를_반환한다() {
		when(ingredientRecommendationRepository.findByUserIdAndRecommendationDate(USER_ID, LocalDate.now()))
				.thenReturn(Optional.empty());

		IngredientRecommendationService.TodayRecommendation result = service.getTodayRecommendation(USER_ID);

		assertThat(result.ingredients()).isEmpty();
		assertThat(result.products()).isEmpty();
		verifyNoInteractions(aiClient, productSearchClient);
	}

	@Test
	void 오늘_추천이_있으면_저장된_JSON을_파싱해서_반환한다() {
		IngredientRecommendation saved = new IngredientRecommendation(
				USER_ID, SKIN_ANALYSIS_ID, LocalDate.now(), "ingredients-json", "products-json");
		when(ingredientRecommendationRepository.findByUserIdAndRecommendationDate(USER_ID, LocalDate.now()))
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

	private IngredientRecommendation argThatMatches(java.util.function.Predicate<IngredientRecommendation> predicate) {
		return org.mockito.ArgumentMatchers.argThat(predicate::test);
	}
}
