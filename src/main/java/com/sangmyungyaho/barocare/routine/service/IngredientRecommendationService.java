package com.sangmyungyaho.barocare.routine.service;

import com.sangmyungyaho.barocare.ai.client.AiClient;
import com.sangmyungyaho.barocare.ai.client.ProductSearchClient;
import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.routine.dto.IngredientRecommendationDto;
import com.sangmyungyaho.barocare.routine.entity.IngredientRecommendation;
import com.sangmyungyaho.barocare.routine.entity.RecommendationStatus;
import com.sangmyungyaho.barocare.routine.repository.IngredientRecommendationRepository;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 추천 성분 + 관련 제품(ISSUE-30).
 *
 * RoutineService의 규칙 기반 루틴 생성 로직과는 완전히 독립된 부가 기능이다: 오늘 SkinAnalysis 등급을
 * 근거로 AiClient(성분 추천)와 ProductSearchClient(실시간 웹 검색으로 제품 매칭)를 순서대로 호출하고,
 * 결과를 skinAnalysisId 기준 find-or-create로 저장한다(Report와 동일한 패턴 - 같은 SkinAnalysis에
 * 대해 반복 GET이 와도 AI/웹검색을 다시 호출하지 않는다).
 *
 * POST /skin-analyses 응답 지연 개선: 이 생성 작업 전체(성분 추천 → 제품 검색)는 더 이상 메인 응답을
 * 기다리지 않는다. 호출부(SkinAnalysisService)가 {@link #initializeTodayRecommendation}으로 PENDING
 * row를 동기로 먼저 만들어두고(응답 직후 바로 상태 조회를 해도 404가 아니게 하기 위함), 실제 AI/웹검색
 * 호출은 {@link #generateTodayRecommendation}이 백그라운드({@code @Async})에서 단계적으로 처리하며
 * row를 갱신한다. 성분/제품 각각의 상태(PENDING/PROCESSING/COMPLETED/FAILED)는
 * GET /api/v1/skin-analyses/{id}/ingredients, /products로 별도 조회한다.
 *
 * 두 외부 호출(AI, 웹검색) 중 어느 하나가 실패해도 나머지 기능(루틴 자체, 혹은 성분 추천)은 항상
 * 정상 동작해야 한다는 요구사항 때문에, 실패를 여기서 전부 흡수하고 절대 예외를 던지지 않는다.
 * getTodayRecommendation/getIngredientStatus/getProductStatus는 저장된 값만 읽는 순수 조회이며
 * AI/웹검색을 유발하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class IngredientRecommendationService {

	private static final Logger log = LoggerFactory.getLogger(IngredientRecommendationService.class);

	private static final List<IngredientRecommendationDto.IngredientItem> EMPTY_INGREDIENTS = List.of();
	private static final List<IngredientRecommendationDto.ProductItem> EMPTY_PRODUCTS = List.of();

	// ReportService.INTERNAL_STATE_LEAK_PATTERN과 동일한 목적/패턴(REP-101 원인 분석 문구 수정과 같은
	// 근본 원인). \b가 아니라 "앞뒤에 영문 알파벳이 없다"는 부정 전후방탐색을 쓰는 이유도 동일하다 - \b가
	// 한글과의 경계를 인식하는 방식이 JDK 버전에 따라 달라(이 프로젝트가 쓰는 JDK 17에서는 한글이 바로
	// 붙으면 매치되지 않음) 실측으로 확인됐다.
	private static final Pattern INTERNAL_STATE_LEAK_PATTERN = Pattern.compile(
			"(?<![A-Za-z])(IMPROVED|WORSENED|UNCHANGED|INCREASED|DECREASED|STABLE|GOOD|MODERATE|POOR|SAFE|CAUTION|DANGER)(?![A-Za-z])",
			Pattern.CASE_INSENSITIVE
	);

	private final IngredientRecommendationRepository ingredientRecommendationRepository;
	private final AiClient aiClient;
	private final ProductSearchClient productSearchClient;
	private final ObjectMapper objectMapper;

	// skinAnalysisId 기준 동시 생성 방지용 락(단일 인스턴스 기준). Report/Routine과 동일한 목적 -
	// DB unique 제약이 최종 안전장치이므로 여기서는 "같은 순간 요청이 AI/웹검색을 중복 호출하지 않도록"
	// 최소화하는 목적만 가진다.
	private final Map<Long, Object> recommendationGenerationLocks = new ConcurrentHashMap<>();

	/**
	 * 오늘 추천 성분/제품 row를 PENDING/PENDING 상태로 미리 만들어둔다. POST /skin-analyses의 메인 응답
	 * 경로에서 동기로 호출된다 - 이래야 응답 직후 클라이언트가 바로 상태 조회 GET을 호출해도 404 대신
	 * PENDING을 받는다. 이미 같은 skinAnalysisId로 row가 있으면(재분석 등) 그대로 재사용한다.
	 */
	public void initializeTodayRecommendation(Long userId, SkinAnalysis todaySkinAnalysis) {
		Long skinAnalysisId = todaySkinAnalysis.getId();
		if (ingredientRecommendationRepository.findBySkinAnalysisId(skinAnalysisId).isPresent()) {
			return;
		}
		try {
			ingredientRecommendationRepository.save(
					new IngredientRecommendation(userId, skinAnalysisId, todaySkinAnalysis.getAnalyzedAt().toLocalDate()));
		} catch (DataIntegrityViolationException e) {
			// 동시 요청 경합(예: 같은 순간 재분석)에 대한 안전장치 - 이미 누군가 만들어뒀으면 그대로 둔다.
			log.info("추천 row 초기화 중 unique 제약 충돌 - 이미 존재함: skinAnalysisId={}", skinAnalysisId);
		}
	}

	/**
	 * 실제 성분 추천(AiClient) → 제품 웹검색(ProductSearchClient) 생성. {@link #initializeTodayRecommendation}이
	 * 미리 만들어둔 row가 없으면 여기서도 find-or-create로 보정한다(단독 호출도 안전하도록 - 기존 테스트가
	 * 이 메서드만 직접 호출하는 방식을 그대로 유지한다).
	 *
	 * POST /skin-analyses 메인 응답과는 완전히 분리된 백그라운드 스레드에서 실행된다({@code @Async}) -
	 * 이 메서드가 오래 걸리거나 실패해도 메인 응답에는 이미 영향을 줄 방법이 없다. row는 단계별로 갱신되며
	 * (PENDING -> PROCESSING -> COMPLETED/FAILED), 각 저장은 그 자체로 즉시 커밋되는 별도 트랜잭션이라
	 * GET 조회가 중간 상태(PROCESSING)를 실시간으로 볼 수 있다.
	 *
	 * 제품 검색은 추천 성분명을 입력으로 쓰는 실제 데이터 의존성이 있어 성분 추천이 끝나야 시작할 수
	 * 있다 - 완전한 병렬/독립 실행은 이 의존성 때문에 불가능하다. 성분 추천이 실패하면 제품 검색은
	 * 시도조차 하지 않고 둘 다 FAILED로 확정한다(IngredientRecommendation.failIngredients 참고).
	 */
	@Async("recommendationExecutor")
	public void generateTodayRecommendation(Long userId, SkinAnalysis todaySkinAnalysis) {
		Long skinAnalysisId = todaySkinAnalysis.getId();
		Object lock = recommendationGenerationLocks.computeIfAbsent(skinAnalysisId, id -> new Object());
		synchronized (lock) {
			IngredientRecommendation recommendation = ingredientRecommendationRepository.findBySkinAnalysisId(skinAnalysisId)
					.orElseGet(() -> ingredientRecommendationRepository.save(
							new IngredientRecommendation(userId, skinAnalysisId, todaySkinAnalysis.getAnalyzedAt().toLocalDate())));

			if (recommendation.getIngredientStatus() != RecommendationStatus.PENDING) {
				log.info("이미 처리(중)이거나 끝난 추천이라 재실행을 건너뜀: skinAnalysisId={}, ingredientStatus={}",
						skinAnalysisId, recommendation.getIngredientStatus());
				return;
			}

			List<IngredientRecommendationDto.IngredientItem> ingredients = generateIngredients(recommendation, todaySkinAnalysis);
			if (ingredients == null) {
				return; // 성분 추천 실패 - failIngredients()에서 productStatus도 이미 FAILED로 확정됨.
			}

			generateProducts(recommendation, ingredients);
		}
	}

	// 성분 추천 성공 시 결과 목록을, 실패 시 null을 반환한다(호출부가 이후 제품 검색 진행 여부를 판단).
	private List<IngredientRecommendationDto.IngredientItem> generateIngredients(
			IngredientRecommendation recommendation, SkinAnalysis todaySkinAnalysis
	) {
		Long skinAnalysisId = recommendation.getSkinAnalysisId();
		recommendation.markIngredientProcessing();
		ingredientRecommendationRepository.save(recommendation);

		long startMs = System.currentTimeMillis();
		try {
			AiDto.IngredientRecommendationResult result = aiClient.recommendIngredients(
					todaySkinAnalysis.getRednessLevel(), todaySkinAnalysis.getTroubleLevel(), todaySkinAnalysis.getSkinLevel());
			validateNoInternalStateLeak(result);
			List<IngredientRecommendationDto.IngredientItem> ingredients =
					result.ingredients().stream().map(IngredientRecommendationDto.IngredientItem::from).toList();

			recommendation.completeIngredients(toJson(ingredients));
			ingredientRecommendationRepository.save(recommendation);
			log.info("[SkinAnalysis] ingredient recommendation completed: {}ms (skinAnalysisId={})",
					System.currentTimeMillis() - startMs, skinAnalysisId);
			return ingredients;
		} catch (RuntimeException e) {
			log.warn("[SkinAnalysis] ingredient recommendation failed after {}ms - FAILED 처리(루틴/피부 분석에는 영향 없음): skinAnalysisId={}",
					System.currentTimeMillis() - startMs, skinAnalysisId, e);
			recommendation.failIngredients();
			ingredientRecommendationRepository.save(recommendation);
			return null;
		}
	}

	private void generateProducts(IngredientRecommendation recommendation, List<IngredientRecommendationDto.IngredientItem> ingredients) {
		Long skinAnalysisId = recommendation.getSkinAnalysisId();
		recommendation.markProductProcessing();
		ingredientRecommendationRepository.save(recommendation);

		long startMs = System.currentTimeMillis();
		try {
			List<String> ingredientNames = ingredients.stream().map(IngredientRecommendationDto.IngredientItem::name).toList();
			List<IngredientRecommendationDto.ProductItem> products = productSearchClient.search(ingredientNames).stream()
					.map(IngredientRecommendationDto.ProductItem::from)
					.toList();

			recommendation.completeProducts(toJson(products));
			ingredientRecommendationRepository.save(recommendation);
			log.info("[SkinAnalysis] product search completed: {}ms (skinAnalysisId={})",
					System.currentTimeMillis() - startMs, skinAnalysisId);
		} catch (RuntimeException e) {
			// ProductSearchClient는 20초 타임아웃(AiHttpClientConfig)이 걸려 있어 여기서 오래 붙잡히지 않는다.
			// 타임아웃/네트워크/파싱 오류 전부 GlobalException(RuntimeException)으로 통일되어 던져지므로
			// 여기서 한 번에 흡수한다 - 성분 추천은 이미 COMPLETED로 저장되어 있으므로 그대로 유지된다.
			log.warn("[SkinAnalysis] product search failed after {}ms - FAILED 처리(성분 추천은 유지): skinAnalysisId={}",
					System.currentTimeMillis() - startMs, skinAnalysisId, e);
			recommendation.failProducts();
			ingredientRecommendationRepository.save(recommendation);
		}
	}

	/**
	 * GET /api/v1/routines/today 전용 순수 조회. 오늘자 추천이 없거나 아직 완료 전이면(생성 전/진행 중/
	 * 실패) 빈 결과를 반환한다 - 에러가 아니다. 상세 상태(PROCESSING/FAILED 구분)가 필요하면
	 * {@link #getIngredientStatus}/{@link #getProductStatus}를 쓴다.
	 *
	 * 오늘 같은 날 재분석하면 skinAnalysisId가 다른 row가 여러 건 있을 수 있으므로(각 분석마다 하나씩
	 * 생성됨) 그중 가장 최근 것(createdAt 기준)만 대표로 보여준다.
	 */
	public TodayRecommendation getTodayRecommendation(Long userId) {
		Optional<IngredientRecommendation> recommendation =
				ingredientRecommendationRepository.findTopByUserIdAndRecommendationDateOrderByCreatedAtDesc(userId, LocalDate.now());
		if (recommendation.isEmpty()) {
			return TodayRecommendation.EMPTY;
		}
		IngredientRecommendation found = recommendation.get();
		return new TodayRecommendation(
				parseIngredients(found.getIngredientsJson()),
				parseProducts(found.getProductsJson())
		);
	}

	/**
	 * GET /api/v1/skin-analyses/{skinAnalysisId}/ingredients 전용 조회. 새로운 AI 호출을 시작하지 않는다.
	 * row가 아예 없으면(초기화 자체가 실패했거나, 이 기능 도입 이전의 오래된 분석) PENDING으로 취급한다.
	 */
	public IngredientRecommendationDto.IngredientStatusResponse getIngredientStatus(Long skinAnalysisId) {
		return ingredientRecommendationRepository.findBySkinAnalysisId(skinAnalysisId)
				.map(r -> IngredientRecommendationDto.IngredientStatusResponse.of(
						r.getIngredientStatus(), parseIngredients(r.getIngredientsJson())))
				.orElse(IngredientRecommendationDto.IngredientStatusResponse.of(RecommendationStatus.PENDING, null));
	}

	/**
	 * GET /api/v1/skin-analyses/{skinAnalysisId}/products 전용 조회. getIngredientStatus와 동일한 규칙.
	 */
	public IngredientRecommendationDto.ProductStatusResponse getProductStatus(Long skinAnalysisId) {
		return ingredientRecommendationRepository.findBySkinAnalysisId(skinAnalysisId)
				.map(r -> IngredientRecommendationDto.ProductStatusResponse.of(
						r.getProductStatus(), parseProducts(r.getProductsJson())))
				.orElse(IngredientRecommendationDto.ProductStatusResponse.of(RecommendationStatus.PENDING, null));
	}

	// AI가 프롬프트 지침을 어기고 IMPROVED/POOR 같은 내부 상태값을 reason/name에 그대로 섞어 반환했는지
	// 검증하는 최종 방어선(ReportService.validateNoInternalStateLeak과 동일한 목적). 여기서 걸리면
	// RuntimeException으로 던져 바깥 catch(RuntimeException)이 흡수하고 오늘 추천을 FAILED 처리한다 -
	// 깨진/새어나간 문구를 그대로 저장/응답하는 것보다 이번엔 실패로 취급하는 편이 안전하다.
	private void validateNoInternalStateLeak(AiDto.IngredientRecommendationResult result) {
		for (AiDto.IngredientSuggestion suggestion : result.ingredients()) {
			if (isLeaking(suggestion.name()) || isLeaking(suggestion.reason())) {
				log.warn("AI 성분 추천 응답에 내부 상태값이 그대로 노출되어 거부함: {}", suggestion);
				throw new GlobalException(ErrorCode.AI_ANALYSIS_FAILED);
			}
		}
	}

	private boolean isLeaking(String text) {
		return text != null && INTERNAL_STATE_LEAK_PATTERN.matcher(text).find();
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JacksonException e) {
			throw new IllegalStateException("추천 성분/제품 직렬화에 실패했습니다.", e);
		}
	}

	// json이 null이면(아직 COMPLETED 전) 빈 목록을 반환한다 - PENDING/PROCESSING/FAILED 상태에서도
	// 안전하게 호출할 수 있어야 한다(예: getTodayRecommendation은 상태를 가리지 않고 항상 부른다).
	private List<IngredientRecommendationDto.IngredientItem> parseIngredients(String json) {
		if (json == null) {
			return EMPTY_INGREDIENTS;
		}
		try {
			return objectMapper.readValue(json, new TypeReference<List<IngredientRecommendationDto.IngredientItem>>() {
			});
		} catch (JacksonException e) {
			throw new IllegalStateException("추천 성분 역직렬화에 실패했습니다.", e);
		}
	}

	private List<IngredientRecommendationDto.ProductItem> parseProducts(String json) {
		if (json == null) {
			return EMPTY_PRODUCTS;
		}
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
