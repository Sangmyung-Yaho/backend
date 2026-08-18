package com.sangmyungyaho.barocare.routine.service;

import com.sangmyungyaho.barocare.ai.client.AiClient;
import com.sangmyungyaho.barocare.ai.client.ProductSearchClient;
import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.routine.dto.IngredientRecommendationDto;
import com.sangmyungyaho.barocare.routine.entity.IngredientRecommendation;
import com.sangmyungyaho.barocare.routine.repository.IngredientRecommendationRepository;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 추천 성분 + 관련 제품(ISSUE-30).
 *
 * RoutineService의 규칙 기반 루틴 생성 로직과는 완전히 독립된 부가 기능이다: 오늘 SkinAnalysis 등급을
 * 근거로 AiClient(성분 추천)와 ProductSearchClient(실시간 웹 검색으로 제품 매칭)를 순서대로 호출하고,
 * 결과를 skinAnalysisId 기준 find-or-create로 저장한다(Report와 동일한 패턴 - 같은 SkinAnalysis에
 * 대해 반복 GET이 와도 AI/웹검색을 다시 호출하지 않는다).
 *
 * 두 외부 호출(AI, 웹검색) 중 어느 하나가 실패해도 나머지 기능(루틴 자체, 혹은 성분 추천)은 항상
 * 정상 동작해야 한다는 요구사항 때문에, 실패를 여기서 전부 흡수하고 절대 예외를 던지지 않는다
 * (generateTodayRecommendation은 void, 호출부인 RoutineService.generateRoutines는 이 메서드의 실패
 * 여부를 신경 쓸 필요가 없다). getTodayIngredients류 조회 메서드도 저장된 값만 읽는 순수 조회이며
 * AI/웹검색을 유발하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class IngredientRecommendationService {

	private static final Logger log = LoggerFactory.getLogger(IngredientRecommendationService.class);

	private static final List<IngredientRecommendationDto.IngredientItem> EMPTY_INGREDIENTS = List.of();
	private static final List<IngredientRecommendationDto.ProductItem> EMPTY_PRODUCTS = List.of();

	private final IngredientRecommendationRepository ingredientRecommendationRepository;
	private final AiClient aiClient;
	private final ProductSearchClient productSearchClient;
	private final ObjectMapper objectMapper;

	// skinAnalysisId 기준 동시 생성 방지용 락(단일 인스턴스 기준). Report/Routine과 동일한 목적 -
	// DB unique 제약이 최종 안전장치이므로 여기서는 "같은 순간 요청이 AI/웹검색을 중복 호출하지 않도록"
	// 최소화하는 목적만 가진다.
	private final Map<Long, Object> recommendationGenerationLocks = new ConcurrentHashMap<>();

	/**
	 * 오늘 추천 성분/제품 생성. RoutineService.generateRoutines()가 오늘 루틴을 저장한 직후에만 호출된다.
	 * 이미 같은 skinAnalysisId로 생성된 추천이 있으면 아무것도 하지 않고 반환한다(AI/웹검색 재호출 없음).
	 *
	 * 실패해도 예외를 던지지 않는다: 성분 추천(AiClient) 자체가 실패하면 이번 호출은 아무 것도 저장하지
	 * 않고 조용히 끝난다(다음 분석 때 다시 시도됨). 성분 추천은 성공했는데 제품 검색(ProductSearchClient)만
	 * 실패하면, 성분만 저장하고 제품은 빈 배열로 저장한다.
	 */
	public void generateTodayRecommendation(Long userId, SkinAnalysis todaySkinAnalysis) {
		Long skinAnalysisId = todaySkinAnalysis.getId();
		Object lock = recommendationGenerationLocks.computeIfAbsent(skinAnalysisId, id -> new Object());
		synchronized (lock) {
			if (ingredientRecommendationRepository.findBySkinAnalysisId(skinAnalysisId).isPresent()) {
				log.info("이미 생성된 오늘 추천을 재사용(AI/웹검색 재호출 없음): skinAnalysisId={}", skinAnalysisId);
				return;
			}

			List<IngredientRecommendationDto.IngredientItem> ingredients;
			try {
				AiDto.IngredientRecommendationResult result = aiClient.recommendIngredients(
						todaySkinAnalysis.getRednessLevel(), todaySkinAnalysis.getTroubleLevel(), todaySkinAnalysis.getSkinLevel());
				ingredients = result.ingredients().stream().map(IngredientRecommendationDto.IngredientItem::from).toList();
			} catch (RuntimeException e) {
				log.warn("성분 추천 실패 - 오늘 추천 없이 스킵(루틴 기능에는 영향 없음): skinAnalysisId={}", skinAnalysisId, e);
				return;
			}

			List<IngredientRecommendationDto.ProductItem> products;
			try {
				List<String> ingredientNames = ingredients.stream().map(IngredientRecommendationDto.IngredientItem::name).toList();
				products = productSearchClient.search(ingredientNames).stream()
						.map(IngredientRecommendationDto.ProductItem::from)
						.toList();
			} catch (RuntimeException e) {
				log.warn("제품 웹 검색 실패 - 성분 추천만 저장: skinAnalysisId={}", skinAnalysisId, e);
				products = EMPTY_PRODUCTS;
			}

			IngredientRecommendation entity = new IngredientRecommendation(
					userId, skinAnalysisId, todaySkinAnalysis.getAnalyzedAt().toLocalDate(),
					toJson(ingredients), toJson(products)
			);
			try {
				ingredientRecommendationRepository.save(entity);
				log.info("추천 성분/제품 저장 완료: skinAnalysisId={}, ingredients={}건, products={}건",
						skinAnalysisId, ingredients.size(), products.size());
			} catch (DataIntegrityViolationException e) {
				// 락으로 막지 못한 경합(예: 다중 인스턴스 배포)에 대한 최종 안전장치. Report와 동일한 패턴.
				log.warn("추천 저장 중 unique 제약 충돌 - 동시 요청으로 이미 저장됨: skinAnalysisId={}", skinAnalysisId);
			}
		}
	}

	/**
	 * GET /api/v1/routines/today 전용 순수 조회. 오늘자 추천이 없으면(아직 생성 전, 또는 생성이
	 * 실패했던 경우) 빈 결과를 반환한다 - 에러가 아니다.
	 */
	public TodayRecommendation getTodayRecommendation(Long userId) {
		Optional<IngredientRecommendation> recommendation =
				ingredientRecommendationRepository.findByUserIdAndRecommendationDate(userId, LocalDate.now());
		if (recommendation.isEmpty()) {
			return TodayRecommendation.EMPTY;
		}
		IngredientRecommendation found = recommendation.get();
		return new TodayRecommendation(
				parseIngredients(found.getIngredientsJson()),
				parseProducts(found.getProductsJson())
		);
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JacksonException e) {
			throw new IllegalStateException("추천 성분/제품 직렬화에 실패했습니다.", e);
		}
	}

	private List<IngredientRecommendationDto.IngredientItem> parseIngredients(String json) {
		try {
			return objectMapper.readValue(json, new TypeReference<List<IngredientRecommendationDto.IngredientItem>>() {
			});
		} catch (JacksonException e) {
			throw new IllegalStateException("추천 성분 역직렬화에 실패했습니다.", e);
		}
	}

	private List<IngredientRecommendationDto.ProductItem> parseProducts(String json) {
		try {
			return objectMapper.readValue(json, new TypeReference<List<IngredientRecommendationDto.ProductItem>>() {
			});
		} catch (JacksonException e) {
			throw new IllegalStateException("추천 제품 역직렬화에 실패했습니다.", e);
		}
	}

	public record TodayRecommendation(
			List<IngredientRecommendationDto.IngredientItem> ingredients,
			List<IngredientRecommendationDto.ProductItem> products
	) {
		public static final TodayRecommendation EMPTY = new TodayRecommendation(EMPTY_INGREDIENTS, EMPTY_PRODUCTS);
	}
}
