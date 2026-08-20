package com.sangmyungyaho.barocare.skin.service;

import com.sangmyungyaho.barocare.ai.client.AiClient;
import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.global.storage.ImageStorageService;
import com.sangmyungyaho.barocare.report.entity.ReportChangeStatus;
import com.sangmyungyaho.barocare.routine.dto.IngredientRecommendationDto;
import com.sangmyungyaho.barocare.routine.service.IngredientRecommendationService;
import com.sangmyungyaho.barocare.skin.dto.SkinAnalysisDto;
import com.sangmyungyaho.barocare.skin.entity.ImageQualityRating;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.skin.entity.SkinImage;
import com.sangmyungyaho.barocare.skin.repository.SkinAnalysisRepository;
import com.sangmyungyaho.barocare.skin.repository.SkinImageRepository;
import com.sangmyungyaho.barocare.user.entity.SkinType;
import com.sangmyungyaho.barocare.user.entity.User;
import com.sangmyungyaho.barocare.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SkinAnalysisService {

	private static final Logger log = LoggerFactory.getLogger(SkinAnalysisService.class);

	// SkinImageService와 동일한 저장 하위 경로. TODO: 공용 상수로 뽑을 만큼 커지면 분리 검토.
	private static final String STORAGE_DIRECTORY = "skin-images";

	private static final Map<String, MimeType> EXTENSION_MIME_TYPES = Map.of(
			"jpg", MimeTypeUtils.IMAGE_JPEG,
			"jpeg", MimeTypeUtils.IMAGE_JPEG,
			"png", MimeTypeUtils.IMAGE_PNG
	);

	/**
	 * 이미지 품질 검증 정책(MVP 임시 기준, PRD 미확정).
	 * lighting/blur/angle/faceRatio 4개 중 POOR가 이 개수를 "초과"하면 분석 실패로 처리한다.
	 * 정책을 바꿔야 하면 이 상수만 조정하면 된다.
	 */
	private static final int MAX_ALLOWED_POOR_QUALITY_COUNT = 1;

	private final SkinImageRepository skinImageRepository;
	private final SkinAnalysisRepository skinAnalysisRepository;
	private final ImageStorageService imageStorageService;
	private final AiClient aiClient;
	private final SkinGradeRubric skinGradeRubric;
	private final UserRepository userRepository;
	private final SkinAnalysisFollowUpService skinAnalysisFollowUpService;
	private final IngredientRecommendationService ingredientRecommendationService;

	public SkinAnalysisDto.Response analyzeSkin(Long userId, SkinAnalysisDto.Request request) {
		log.info("피부 분석 요청 시작: skinImageId={}", request.skinImageId());

		SkinImage skinImage = skinImageRepository.findById(request.skinImageId())
				.orElseThrow(() -> new GlobalException(ErrorCode.SKIN_IMAGE_NOT_FOUND));

		if (!skinImage.getUserId().equals(userId)) {
			log.warn("피부 분석 요청 거부: 다른 사용자의 이미지 - skinImageId={}, ownerUserId={}, requestUserId={}",
					request.skinImageId(), skinImage.getUserId(), userId);
			throw new GlobalException(ErrorCode.FORBIDDEN);
		}

		byte[] imageBytes = imageStorageService.load(STORAGE_DIRECTORY, skinImage.getStoredFileName())
				.orElseThrow(() -> new GlobalException(ErrorCode.SKIN_IMAGE_FILE_NOT_FOUND));
		log.info("이미지 파일 로드 완료: skinImageId={}, storedFileName={}, bytes={}",
				request.skinImageId(), skinImage.getStoredFileName(), imageBytes.length);

		MimeType mimeType = resolveMimeType(skinImage.getStoredFileName());

		log.info("OpenAI 관찰값 추출 요청 시작: skinImageId={}", request.skinImageId());
		long visionStartMs = System.currentTimeMillis();
		AiDto.SkinAnalysisResult result = aiClient.analyzeSkin(imageBytes, mimeType);
		log.info("[SkinAnalysis] vision completed: {}ms (skinImageId={})",
				System.currentTimeMillis() - visionStartMs, request.skinImageId());
		validateResult(result);

		// 피부타입 보정(개인화): 사용자의 skinType을 조회해 등급 판정에 반영한다.
		// 사용자를 찾을 수 없거나 피부타입을 아직 설정하지 않았으면(null) SkinGradeRubric이
		// 기존과 동일한 기준으로 폴백하므로 분석 자체가 실패하지 않는다.
		SkinType skinType = userRepository.findById(userId).map(User::getSkinType).orElse(null);

		// 최종 등급은 GPT가 아니라 SkinGradeRubric이 고정 규칙(+피부타입 보정)으로 계산한다.
		SkinAnalysisLevel rednessLevel = skinGradeRubric.calculateRednessLevel(result.redness(), skinType);
		SkinAnalysisLevel troubleLevel = skinGradeRubric.calculateTroubleLevel(result.trouble(), skinType);
		SkinAnalysisLevel skinLevel = skinGradeRubric.calculateSkinLevel(rednessLevel, troubleLevel);

		log.info("최종 등급 계산 완료: skinImageId={}, skinType={}, rednessLevel={}, troubleLevel={}, skinLevel={}",
				request.skinImageId(), skinType, rednessLevel, troubleLevel, skinLevel);

		SkinAnalysis skinAnalysis = new SkinAnalysis(
				userId, skinImage,
				rednessLevel, result.redness().affectedRegions(), result.redness().maxIntensity(),
				troubleLevel, result.trouble().affectedRegions(), result.trouble().density(),
				skinLevel,
				result.imageQuality().lighting(), result.imageQuality().blur(),
				result.imageQuality().angle(), result.imageQuality().faceRatio(),
				SkinGradeRubric.RUBRIC_VERSION
		);

		SkinAnalysis saved = skinAnalysisRepository.save(skinAnalysis);
		SkinAnalysisDto.Response response = SkinAnalysisDto.Response.from(saved);

		// 분석 결과가 DB에 저장된 이후에만 원본 이미지를 지운다(정책: 원본은 분석 용도로만 임시 보관).
		// 삭제 실패가 이미 끝난 분석 결과 저장을 되돌리면 안 되므로 별도로 흡수한다.
		deleteOriginalImageQuietly(skinImage);

		// 요구사항: "피부 분석 + 원인 분석 + 오늘의 루틴까지" 완료된 뒤에 응답한다 - 그래서 이 호출은
		// 동기다(SkinAnalysisFollowUpService 참고, 예전에 이 전체를 @Async로 백그라운드 실행했던 걸
		// 되돌림). 실패해도 이미 저장된 피부 분석 응답 자체는 항상 성공해야 하므로 예외는 흡수한다
		// (CheckinService가 루틴 생성 실패를 흡수하던 것과 동일한 방어 철학).
		try {
			skinAnalysisFollowUpService.generateTodayReportAndRoutines(userId, saved);
		} catch (RuntimeException e) {
			log.warn("오늘 리포트/루틴 생성 실패(피부 분석 저장에는 영향 없음): userId={}, skinAnalysisId={}",
					userId, saved.getId(), e);
		}

		// 추천 성분/제품 생성은 리포트/루틴과 달리 응답을 기다리지 않는다 - 이 응답이 나간 뒤에도 계속
		// 백그라운드에서 진행되고, 클라이언트는 별도로 GET .../ingredients, .../products를 호출해
		// 준비됐는지 확인한다("추천 버튼" 클릭 시가 아니라 분석 완료 직후 이미 생성이 시작되는 구조).
		// initializeTodayRecommendation은 동기(PENDING row를 즉시 만들어 응답 직후 상태 조회가 404가
		// 되지 않게 함)이고, generateTodayRecommendation은 @Async라 실제 OpenAI/웹검색 호출은 이
		// 메서드가 반환된 뒤에도 계속 실행된다. 체크인 유무와 무관하게 항상 트리거한다 - 이 추천은
		// 오늘 SkinAnalysis 등급만으로 계산되고 체크인/리포트/루틴 데이터에 의존하지 않기 때문이다.
		try {
			ingredientRecommendationService.initializeTodayRecommendation(userId, saved);
			ingredientRecommendationService.generateTodayRecommendation(userId, saved);
		} catch (RuntimeException e) {
			// @Async 메서드라도 스레드풀 큐가 가득 찬 경우(TaskRejectedException 등) 작업이 시작되기도
			// 전에 호출부에서 동기적으로 예외가 날 수 있다 - 이 경우에도 피부 분석 저장 응답 자체는
			// 항상 성공해야 하므로 흡수한다.
			log.warn("추천 성분/제품 생성 작업을 백그라운드로 넘기지 못함(피부 분석 저장에는 영향 없음): userId={}, skinAnalysisId={}",
					userId, saved.getId(), e);
		}

		return response;
	}

	/**
	 * GET /api/v1/skin-analyses/{skinAnalysisId}/ingredients 전용. 소유권 검증(getDetail과 동일한 규칙 -
	 * 404/403)만 여기서 하고, 실제 상태/데이터 조회는 IngredientRecommendationService에 위임한다. 이
	 * 메서드 자체는 새로운 AI 호출을 시작하지 않는다(순수 조회).
	 */
	public IngredientRecommendationDto.IngredientStatusResponse getIngredientRecommendationStatus(Long userId, Long skinAnalysisId) {
		verifyOwnership(userId, skinAnalysisId);
		return ingredientRecommendationService.getIngredientStatus(skinAnalysisId);
	}

	/**
	 * GET /api/v1/skin-analyses/{skinAnalysisId}/products 전용. getIngredientRecommendationStatus와 동일한 규칙.
	 */
	public IngredientRecommendationDto.ProductStatusResponse getProductRecommendationStatus(Long userId, Long skinAnalysisId) {
		verifyOwnership(userId, skinAnalysisId);
		return ingredientRecommendationService.getProductStatus(skinAnalysisId);
	}

	// getDetail()의 소유권 검증과 동일한 규칙(404 SKIN_ANALYSIS_NOT_FOUND / 403 FORBIDDEN)을 상태 조회
	// 두 메서드가 공유한다.
	private void verifyOwnership(Long userId, Long skinAnalysisId) {
		SkinAnalysis analysis = skinAnalysisRepository.findById(skinAnalysisId)
				.orElseThrow(() -> new GlobalException(ErrorCode.SKIN_ANALYSIS_NOT_FOUND));
		if (!analysis.getUserId().equals(userId)) {
			log.warn("추천 성분/제품 상태 조회 거부: 다른 사용자의 분석 - skinAnalysisId={}, ownerUserId={}, requestUserId={}",
					skinAnalysisId, analysis.getUserId(), userId);
			throw new GlobalException(ErrorCode.FORBIDDEN);
		}
	}

	/**
	 * 분석에 사용된 원본 이미지를 삭제한다. 이 시점은 이미 SkinAnalysis 저장이 끝난 뒤이므로,
	 * 여기서 발생하는 어떤 예외도 밖으로 던지지 않고 로그만 남긴다(요구사항: 삭제 실패가 분석
	 * 결과 저장 자체를 롤백시키면 안 된다). 파일이 원래 없었던 경우(false)도 실패가 아니라
	 * 정상 케이스로 취급한다(멱등).
	 */
	private void deleteOriginalImageQuietly(SkinImage skinImage) {
		try {
			boolean deleted = imageStorageService.delete(STORAGE_DIRECTORY, skinImage.getStoredFileName());
			log.info("원본 이미지 삭제 {}: skinImageId={}, storedFileName={}",
					deleted ? "완료" : "생략(이미 없음)", skinImage.getId(), skinImage.getStoredFileName());
		} catch (RuntimeException e) {
			log.warn("원본 이미지 삭제 실패(분석 결과 저장에는 영향 없음, 다음 정리 배치에서 재시도 대상 아님 - 수동 확인 필요): "
							+ "skinImageId={}, storedFileName={}",
					skinImage.getId(), skinImage.getStoredFileName(), e);
		}
	}

	/**
	 * 피부 분석 히스토리 조회(Issue #20).
	 * period(일) 동안의 분석 이력을, 등급(SAFE/CAUTION/DANGER) 기준으로 반환한다.
	 * average는 산술 평균이 아니라 최빈 등급이다 — {@link #calculateModeLevel} 참고.
	 */
	public SkinAnalysisDto.HistoryResponse getHistory(Long userId, int periodDays) {
		LocalDateTime from = LocalDate.now().minusDays(periodDays - 1L).atStartOfDay();
		List<SkinAnalysis> analyses = skinAnalysisRepository.findAllByUserIdAndAnalyzedAtBetweenOrderByAnalyzedAtAsc(userId, from, LocalDateTime.now());

		// baseline(최초 분석)은 조회 기간(period)과 무관하게 사용자 전체 이력 기준으로 별도 조회한다.
		SkinAnalysisDto.LevelPoint baseline = skinAnalysisRepository.findFirstByUserIdOrderByAnalyzedAtAsc(userId)
				.map(SkinAnalysisDto.LevelPoint::from)
				.orElse(null);

		if (analyses.isEmpty()) {
			log.info("피부 분석 히스토리 없음: periodDays={}, from={}", periodDays, from);
			return SkinAnalysisDto.HistoryResponse.empty(periodDays, baseline);
		}

		// 오름차순 정렬된 리스트이므로 마지막 원소가 곧 최신 분석이다.
		SkinAnalysis latest = analyses.get(analyses.size() - 1);
		SkinAnalysisLevel rednessMode = calculateModeLevel(analyses, SkinAnalysis::getRednessLevel);
		SkinAnalysisLevel troubleMode = calculateModeLevel(analyses, SkinAnalysis::getTroubleLevel);

		List<SkinAnalysisDto.HistoryItem> history = analyses.stream()
				.map(SkinAnalysisDto.HistoryItem::from)
				.toList();

		log.info("피부 분석 히스토리 조회 완료: periodDays={}, count={}, latestId={}, rednessMode={}, troubleMode={}",
				periodDays, analyses.size(), latest.getId(), rednessMode, troubleMode);

		return new SkinAnalysisDto.HistoryResponse(
				periodDays,
				SkinAnalysisDto.LevelPoint.from(latest),
				SkinAnalysisDto.LevelPoint.of(rednessMode, troubleMode),
				history,
				baseline
		);
	}

	/**
	 * 피부 분석 상세 조회(프론트 화면 연동). 소유자 검증을 거치며, baseline(최초 분석) 여부와
	 * 직전 분석 대비 변화(redness/trouble)를 함께 반환한다. history API(GET .../history)의 기간별
	 * 목록과 달리 단건 상세이며, 새로 데이터를 계산/저장하지 않고 이미 저장된 값만 조회한다.
	 */
	public SkinAnalysisDto.DetailResponse getDetail(Long userId, Long skinAnalysisId) {
		SkinAnalysis analysis = skinAnalysisRepository.findById(skinAnalysisId)
				.orElseThrow(() -> new GlobalException(ErrorCode.SKIN_ANALYSIS_NOT_FOUND));

		if (!analysis.getUserId().equals(userId)) {
			log.warn("피부 분석 상세 조회 거부: 다른 사용자의 분석 - skinAnalysisId={}, ownerUserId={}, requestUserId={}",
					skinAnalysisId, analysis.getUserId(), userId);
			throw new GlobalException(ErrorCode.FORBIDDEN);
		}

		boolean isBaseline = skinAnalysisRepository.findFirstByUserIdOrderByAnalyzedAtAsc(userId)
				.map(SkinAnalysis::getId)
				.map(baselineId -> baselineId.equals(analysis.getId()))
				.orElse(false);

		Optional<SkinAnalysis> previous = skinAnalysisRepository
				.findTopByUserIdAndAnalyzedAtLessThanOrderByAnalyzedAtDesc(userId, analysis.getAnalyzedAt());

		Long previousId = previous.map(SkinAnalysis::getId).orElse(null);
		ReportChangeStatus rednessChangeStatus = previous
				.map(p -> toChangeStatus(analysis.getRednessLevel().ordinal() - p.getRednessLevel().ordinal()))
				.orElse(null);
		ReportChangeStatus troubleChangeStatus = previous
				.map(p -> toChangeStatus(analysis.getTroubleLevel().ordinal() - p.getTroubleLevel().ordinal()))
				.orElse(null);

		return new SkinAnalysisDto.DetailResponse(
				analysis.getId(), analysis.getSkinImage().getId(),
				analysis.getRednessLevel(), analysis.getTroubleLevel(), analysis.getSkinLevel(), analysis.getAnalyzedAt(),
				isBaseline, previousId, rednessChangeStatus, troubleChangeStatus
		);
	}

	/**
	 * 홈 대시보드 통합 조회 전용: 사용자의 가장 최근 SkinAnalysis를 조회한다. getDetail()과 달리
	 * 조회할 ID를 미리 알 필요가 없고(가장 최근 것을 바로 찾는다), 소유권 검증도 필요 없다(애초에
	 * userId 기준으로만 조회하므로 다른 사용자의 데이터가 섞일 수 없다). 분석 기록이 없으면
	 * Optional.empty()를 반환하며, 새로운 분석을 실행하지 않는다(순수 DB 조회).
	 *
	 * findTop2ByUserIdOrderByAnalyzedAtDesc() 한 번의 조회로 최신(index 0)과 직전(index 1) 분석을
	 * 동시에 얻으므로, getDetail()의 findTopByUserIdAndAnalyzedAtLessThan... 조회를 추가로 반복하지 않는다.
	 */
	public Optional<SkinAnalysisDto.DetailResponse> getLatestDetailForUser(Long userId) {
		List<SkinAnalysis> recentAnalyses = skinAnalysisRepository.findTop2ByUserIdOrderByAnalyzedAtDesc(userId);
		if (recentAnalyses.isEmpty()) {
			return Optional.empty();
		}

		SkinAnalysis latest = recentAnalyses.get(0);
		SkinAnalysis previous = recentAnalyses.size() > 1 ? recentAnalyses.get(1) : null;

		boolean isBaseline = skinAnalysisRepository.findFirstByUserIdOrderByAnalyzedAtAsc(userId)
				.map(SkinAnalysis::getId)
				.map(baselineId -> baselineId.equals(latest.getId()))
				.orElse(false);

		Long previousId = previous != null ? previous.getId() : null;
		ReportChangeStatus rednessChangeStatus = previous != null
				? toChangeStatus(latest.getRednessLevel().ordinal() - previous.getRednessLevel().ordinal())
				: null;
		ReportChangeStatus troubleChangeStatus = previous != null
				? toChangeStatus(latest.getTroubleLevel().ordinal() - previous.getTroubleLevel().ordinal())
				: null;

		return Optional.of(new SkinAnalysisDto.DetailResponse(
				latest.getId(), latest.getSkinImage().getId(),
				latest.getRednessLevel(), latest.getTroubleLevel(), latest.getSkinLevel(), latest.getAnalyzedAt(),
				isBaseline, previousId, rednessChangeStatus, troubleChangeStatus
		));
	}

	// ReportService의 toReportChangeStatus()와 동일한 규칙(등급 ordinal이 낮아지면 IMPROVED)이다.
	// Phase 4(ReportService) 로직은 그대로 두고 건드리지 않기 위해, 이 화면-연동 이슈 범위 안에서
	// 독립적으로 계산한다(ReportService를 이 도메인이 의존하게 만들지 않는다).
	private ReportChangeStatus toChangeStatus(int change) {
		if (change < 0) {
			return ReportChangeStatus.IMPROVED;
		}
		if (change > 0) {
			return ReportChangeStatus.WORSENED;
		}
		return ReportChangeStatus.UNCHANGED;
	}

	/**
	 * 조회 기간 내 최빈 등급을 계산한다. 동률이면 더 위험한 등급을 우선한다
	 * (SkinAnalysisLevel 선언 순서 = 위험도 순서: SAFE < CAUTION < DANGER, ordinal 비교로 판단).
	 * 산술 평균이 존재하지 않는 등급형 데이터라 "평균" 대신 대표값으로 사용한다.
	 */
	private SkinAnalysisLevel calculateModeLevel(List<SkinAnalysis> analyses, Function<SkinAnalysis, SkinAnalysisLevel> levelExtractor) {
		Map<SkinAnalysisLevel, Long> countsByLevel = analyses.stream()
				.map(levelExtractor)
				.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

		return countsByLevel.entrySet().stream()
				.max(Comparator.<Map.Entry<SkinAnalysisLevel, Long>>comparingLong(Map.Entry::getValue)
						.thenComparing(entry -> entry.getKey().ordinal()))
				.map(Map.Entry::getKey)
				.orElseThrow(); // analyses가 비어있지 않음을 호출부에서 보장하므로 도달하지 않는다.
	}

	/**
	 * GPT 관찰값을 검증한다(등급 판단은 여기서 하지 않는다). 실패 사유에 따라 서로 다른 ErrorCode를 던져
	 * 프론트가 "재촬영 필요"와 "서버/AI 분석 실패"를 구분할 수 있게 한다.
	 * - redness/trouble/imageQuality 구조 자체가 비어 있거나 affectedRegions가 없음(응답 형식 오류)
	 *   → AI_ANALYSIS_FAILED(502): AI 응답 자체가 이상한 경우로, 사용자의 사진 품질 문제가 아니다.
	 * - 이미지 품질(조명/블러/각도/얼굴 비율) 중 POOR 개수가 {@link #MAX_ALLOWED_POOR_QUALITY_COUNT}를 초과
	 *   → SKIN_IMAGE_QUALITY_INSUFFICIENT(400): 사용자가 다시 촬영해야 하는 경우.
	 */
	private void validateResult(AiDto.SkinAnalysisResult result) {
		if (result == null || result.redness() == null || result.trouble() == null || result.imageQuality() == null) {
			log.warn("AI 분석 검증 실패: redness/trouble/imageQuality 구조 누락 - {}", result);
			throw new GlobalException(ErrorCode.AI_ANALYSIS_FAILED);
		}
		if (result.redness().affectedRegions() == null || result.trouble().affectedRegions() == null) {
			log.warn("AI 분석 검증 실패: affectedRegions 누락 - redness={}, trouble={}", result.redness(), result.trouble());
			throw new GlobalException(ErrorCode.AI_ANALYSIS_FAILED);
		}
		int poorQualityCount = countPoorQuality(result.imageQuality());
		if (poorQualityCount > MAX_ALLOWED_POOR_QUALITY_COUNT) {
			log.warn("이미지 품질 부족(재촬영 필요, POOR {}개, 허용 {}개) - {}",
					poorQualityCount, MAX_ALLOWED_POOR_QUALITY_COUNT, result.imageQuality());
			throw new GlobalException(ErrorCode.SKIN_IMAGE_QUALITY_INSUFFICIENT);
		}
	}

	private int countPoorQuality(AiDto.ImageQuality imageQuality) {
		int poorCount = 0;
		if (imageQuality.lighting() == ImageQualityRating.POOR) {
			poorCount++;
		}
		if (imageQuality.blur() == ImageQualityRating.POOR) {
			poorCount++;
		}
		if (imageQuality.angle() == ImageQualityRating.POOR) {
			poorCount++;
		}
		if (imageQuality.faceRatio() == ImageQualityRating.POOR) {
			poorCount++;
		}
		return poorCount;
	}

	private MimeType resolveMimeType(String storedFileName) {
		String extension = extractExtension(storedFileName).toLowerCase(Locale.ROOT);
		MimeType mimeType = EXTENSION_MIME_TYPES.get(extension);
		if (mimeType == null) {
			throw new GlobalException(ErrorCode.SKIN_IMAGE_FILE_NOT_FOUND);
		}
		return mimeType;
	}

	private String extractExtension(String fileName) {
		int dotIndex = fileName.lastIndexOf('.');
		return dotIndex >= 0 && dotIndex < fileName.length() - 1 ? fileName.substring(dotIndex + 1) : "";
	}
}
