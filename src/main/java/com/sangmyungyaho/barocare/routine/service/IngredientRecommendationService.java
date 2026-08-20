package com.sangmyungyaho.barocare.routine.service;

import com.sangmyungyaho.barocare.ai.client.AiClient;
import com.sangmyungyaho.barocare.ai.client.ProductSearchClient;
import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.global.text.UserFacingTextGuard;
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
		log.info("[SkinAnalysis] recommendation background task started (skinAnalysisId={}, thread={})",
				skinAnalysisId, Thread.currentThread().getName());
		// 이 메서드 전체를 catch(RuntimeException)으로 한 번 더 감싼다 - find-or-create 조회/저장이나
		// 락 획득 단계처럼 generateIngredients/generateProducts 바깥에서 나는 예외까지 전부
		// [SkinAnalysis] 태그로 로그를 남기기 위함이다. 이게 없으면 그런 예외는 Spring의 기본
		// @Async 예외 핸들러로만 넘어가 [SkinAnalysis] 로그 없이 조용히 사라지고, 이 skinAnalysisId의
		// 추천은 어느 단계에서 멈췄는지 알 방법 없이 영구히 PENDING/PROCESSING에 남는다.
		try {
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
		} catch (RuntimeException e) {
			// generateIngredients/generateProducts 내부에서 이미 흡수되지 않은, 이 메서드 자체의 예외
			// (find-or-create 조회/저장 실패 등). 이 경우 row 상태를 여기서 더 손댈 수 없을 수도 있지만
			// (recommendation 참조 자체를 못 얻었을 수 있음), 최소한 원인을 크게 로그로 남긴다.
			log.error("[SkinAnalysis] recommendation background task 처리 중 예상치 못한 오류 - skinAnalysisId={}", skinAnalysisId, e);
		}
	}

	// 성분 추천 성공 시 결과 목록을, 실패 시 null을 반환한다(호출부가 이후 제품 검색 진행 여부를 판단).
	//
	// 버그 수정(PROCESSING에 영구적으로 멈추는 문제): 예전 구현은 "PROCESSING으로 표시하는 첫 save()"와
	// "FAILED로 되돌리는 catch 블록의 save()"가 둘 다 아무 보호 없이 호출됐다. 이 두 save() 중 하나라도
	// 예외를 던지면(DB 커넥션 풀 고갈, 일시적 접속 단절, 데드락 등 운영 환경에서 실제로 발생할 수 있는
	// 일시적 오류) 그 예외는 어디서도 잡히지 않고 @Async 메서드 밖으로 그대로 새 나갔다. void @Async
	// 메서드의 미처리 예외는 Spring이 기본 핸들러로 로그만 남기고 조용히 삼키므로, 그 시점까지 이미
	// 커밋된 마지막 상태(대개 PROCESSING)에서 다시는 갱신되지 않았다 - AI 호출 자체가 성공/실패했는지와
	// 무관하게, "그 결과를 반영하는 save()"가 실패하면 이 메서드가 뭘 하든 소용이 없었다는 뜻이다.
	//
	// 지금은 "PROCESSING 표시"와 "FAILED 표시" 두 save()만 {@link #saveRecommendationQuietly}로 감싸
	// 이 경로에서 예외가 나도 [SkinAnalysis] 태그로 크게 로그를 남기고 계속 진행되게 한다(더 이상 던질
	// 곳이 없는 마지막 보루이기 때문). 반면 "COMPLETED 표시" save()는 일부러 보호하지 않는다 - 여기서
	// 예외가 나면 catch 블록으로 흘러가 FAILED로 되돌리는 기존 폴백이 그대로 작동해야 하기 때문이다
	// (완료 저장을 조용히 삼키면 실제로는 저장 안 됐는데 호출부는 성공한 걸로 착각하게 된다).
	private List<IngredientRecommendationDto.IngredientItem> generateIngredients(
			IngredientRecommendation recommendation, SkinAnalysis todaySkinAnalysis
	) {
		Long skinAnalysisId = recommendation.getSkinAnalysisId();
		recommendation.markIngredientProcessing();
		saveRecommendationQuietly(recommendation, "ingredient processing 시작", skinAnalysisId);

		long startMs = System.currentTimeMillis();
		log.info("[SkinAnalysis] ingredient recommendation started (skinAnalysisId={})", skinAnalysisId);
		try {
			AiDto.IngredientRecommendationResult result = aiClient.recommendIngredients(
					todaySkinAnalysis.getRednessLevel(), todaySkinAnalysis.getTroubleLevel(), todaySkinAnalysis.getSkinLevel());
			validateNoInternalStateLeak(result);
			List<IngredientRecommendationDto.IngredientItem> ingredients =
					result.ingredients().stream().map(IngredientRecommendationDto.IngredientItem::from).toList();

			recommendation.completeIngredients(toJson(ingredients));
			ingredientRecommendationRepository.save(recommendation); // 실패하면 catch로 흘러가 FAILED 폴백
			log.info("[SkinAnalysis] ingredient recommendation completed: {}ms (skinAnalysisId={})",
					System.currentTimeMillis() - startMs, skinAnalysisId);
			return ingredients;
		} catch (RuntimeException e) {
			log.warn("[SkinAnalysis] ingredient recommendation failed after {}ms - FAILED 처리(루틴/피부 분석에는 영향 없음): skinAnalysisId={}",
					System.currentTimeMillis() - startMs, skinAnalysisId, e);
			recommendation.failIngredients();
			saveRecommendationQuietly(recommendation, "ingredient failed 저장", skinAnalysisId);
			return null;
		}
	}

	private void generateProducts(IngredientRecommendation recommendation, List<IngredientRecommendationDto.IngredientItem> ingredients) {
		Long skinAnalysisId = recommendation.getSkinAnalysisId();
		recommendation.markProductProcessing();
		saveRecommendationQuietly(recommendation, "product processing 시작", skinAnalysisId);

		long startMs = System.currentTimeMillis();
		log.info("[SkinAnalysis] product search started (skinAnalysisId={})", skinAnalysisId);
		try {
			List<String> ingredientNames = ingredients.stream().map(IngredientRecommendationDto.IngredientItem::name).toList();
			List<IngredientRecommendationDto.ProductItem> products = productSearchClient.search(ingredientNames).stream()
					.map(IngredientRecommendationDto.ProductItem::from)
					.toList();

			recommendation.completeProducts(toJson(products));
			ingredientRecommendationRepository.save(recommendation); // 실패하면 catch로 흘러가 FAILED 폴백
			log.info("[SkinAnalysis] product search completed: {}ms (skinAnalysisId={})",
					System.currentTimeMillis() - startMs, skinAnalysisId);
		} catch (RuntimeException e) {
			// ProductSearchClient는 20초 타임아웃(AiHttpClientConfig)이 걸려 있어 여기서 오래 붙잡히지 않는다.
			// 타임아웃/네트워크/파싱 오류 전부 GlobalException(RuntimeException)으로 통일되어 던져지므로
			// 여기서 한 번에 흡수한다 - 성분 추천은 이미 COMPLETED로 저장되어 있으므로 그대로 유지된다.
			log.warn("[SkinAnalysis] product search failed after {}ms - FAILED 처리(성분 추천은 유지): skinAnalysisId={}",
					System.currentTimeMillis() - startMs, skinAnalysisId, e);
			recommendation.failProducts();
			saveRecommendationQuietly(recommendation, "product failed 저장", skinAnalysisId);
		}
	}

	// 상태 전이 저장 전용 헬퍼. "더 이상 던질 곳이 없는" save()에만 쓴다(PROCESSING 최초 표시, FAILED 최종
	// 표시) - save() 자체가 던지는 예외(DB 커넥션 풀 고갈, 일시적 접속 단절, 데드락 등)를 여기서 마지막으로
	// 흡수한다. 이게 없으면 그 예외가 @Async 메서드 밖으로 새 나가 Spring 기본 핸들러가 조용히 로그만
	// 남기고 삼켜버리고, 이 row는 마지막으로 성공한 상태에 영구히 멈춘다. 흡수만 하고 재시도는 하지
	// 않는다(재시도까지 필요하면 별도 설계가 필요한 더 큰 변경이라 이번 범위에서는 제외) - 대신
	// [SkinAnalysis] 태그로 ERROR 로그를 남겨 운영에서 반드시 눈에 띄게 한다.
	private void saveRecommendationQuietly(IngredientRecommendation recommendation, String stage, Long skinAnalysisId) {
		try {
			ingredientRecommendationRepository.save(recommendation);
		} catch (RuntimeException e) {
			log.error("[SkinAnalysis] 추천 상태 저장 실패({}) - 이 skinAnalysisId의 추천은 마지막으로 커밋된 상태에 멈출 수 있음: "
							+ "skinAnalysisId={}, ingredientStatus={}, productStatus={}",
					stage, skinAnalysisId, recommendation.getIngredientStatus(), recommendation.getProductStatus(), e);
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
	// 검증하는 최종 방어선(ReportService.validateNoInternalStateLeak과 동일한 목적, 실제 정규식/매핑은
	// 공용 UserFacingTextGuard를 함께 쓴다). 여기서 걸리면 RuntimeException으로 던져 바깥
	// catch(RuntimeException)이 흡수하고 오늘 추천을 FAILED 처리한다 - 깨진/새어나간 문구를 그대로
	// 저장/응답하는 것보다 이번엔 실패로 취급하는 편이 안전하다.
	private void validateNoInternalStateLeak(AiDto.IngredientRecommendationResult result) {
		for (AiDto.IngredientSuggestion suggestion : result.ingredients()) {
			if (UserFacingTextGuard.containsLeak(suggestion.name()) || UserFacingTextGuard.containsLeak(suggestion.reason())) {
				log.warn("AI 성분 추천 응답에 내부 상태값이 그대로 노출되어 거부함: {}", suggestion);
				throw new GlobalException(ErrorCode.AI_ANALYSIS_FAILED);
			}
		}
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
	//
	// 조회 응답 직전 최종 방어선(2차): 저장 시점에 이미 validateNoInternalStateLeak으로 거른 데이터라도,
	// 그 검증이 도입되기 전에 저장된 레거시 행이 있을 수 있다(DB 마이그레이션 없이 처리) - 여기서 한 번 더
	// 훑어 내부 상태값이 남아 있으면 자연어 한국어로 치환한다.
	private List<IngredientRecommendationDto.IngredientItem> parseIngredients(String json) {
		if (json == null) {
			return EMPTY_INGREDIENTS;
		}
		try {
			List<IngredientRecommendationDto.IngredientItem> ingredients =
					objectMapper.readValue(json, new TypeReference<List<IngredientRecommendationDto.IngredientItem>>() {
					});
			return ingredients.stream()
					.map(item -> new IngredientRecommendationDto.IngredientItem(
							UserFacingTextGuard.sanitize(item.name()), UserFacingTextGuard.sanitize(item.reason())))
					.toList();
		} catch (JacksonException e) {
			throw new IllegalStateException("추천 성분 역직렬화에 실패했습니다.", e);
		}
	}

	// 성분과 동일한 이유로 2차 방어선을 둔다. brand/name은 실제 제품명이라 leak 가능성이 낮지만, 이
	// 레코드의 모든 자연어 필드를 일관되게 다루기 위해 함께 sanitize한다(비어있지 않은 문자열이면 비용도
	// 무시할 만큼 작다). productUrl은 URL이라 대상이 아니다.
	private List<IngredientRecommendationDto.ProductItem> parseProducts(String json) {
		if (json == null) {
			return EMPTY_PRODUCTS;
		}
		try {
			List<IngredientRecommendationDto.ProductItem> products =
					objectMapper.readValue(json, new TypeReference<List<IngredientRecommendationDto.ProductItem>>() {
					});
			return products.stream()
					.map(item -> new IngredientRecommendationDto.ProductItem(
							UserFacingTextGuard.sanitize(item.brand()),
							UserFacingTextGuard.sanitize(item.name()),
							UserFacingTextGuard.sanitize(item.matchedIngredient()),
							UserFacingTextGuard.sanitize(item.reason()),
							item.productUrl()))
					.toList();
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
