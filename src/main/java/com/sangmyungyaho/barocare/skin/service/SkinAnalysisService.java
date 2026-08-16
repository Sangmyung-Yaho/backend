package com.sangmyungyaho.barocare.skin.service;

import com.sangmyungyaho.barocare.ai.client.AiClient;
import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.global.storage.ImageStorageService;
import com.sangmyungyaho.barocare.skin.dto.SkinAnalysisDto;
import com.sangmyungyaho.barocare.skin.entity.ImageQualityRating;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.skin.entity.SkinImage;
import com.sangmyungyaho.barocare.skin.repository.SkinAnalysisRepository;
import com.sangmyungyaho.barocare.skin.repository.SkinImageRepository;
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

	public SkinAnalysisDto.Response analyzeSkin(Long userId, SkinAnalysisDto.Request request) {
		log.info("피부 분석 요청 시작: skinImageId={}", request.skinImageId());

		SkinImage skinImage = skinImageRepository.findById(request.skinImageId())
				.orElseThrow(() -> new GlobalException(ErrorCode.SKIN_IMAGE_NOT_FOUND));

		byte[] imageBytes = imageStorageService.load(STORAGE_DIRECTORY, skinImage.getStoredFileName())
				.orElseThrow(() -> new GlobalException(ErrorCode.SKIN_IMAGE_FILE_NOT_FOUND));
		log.info("이미지 파일 로드 완료: skinImageId={}, storedFileName={}, bytes={}",
				request.skinImageId(), skinImage.getStoredFileName(), imageBytes.length);

		MimeType mimeType = resolveMimeType(skinImage.getStoredFileName());

		log.info("OpenAI 관찰값 추출 요청 시작: skinImageId={}", request.skinImageId());
		AiDto.SkinAnalysisResult result = aiClient.analyzeSkin(imageBytes, mimeType);
		validateResult(result);

		// 최종 등급은 GPT가 아니라 SkinGradeRubric이 고정 규칙으로 계산한다.
		SkinAnalysisLevel rednessLevel = skinGradeRubric.calculateRednessLevel(result.redness());
		SkinAnalysisLevel troubleLevel = skinGradeRubric.calculateTroubleLevel(result.trouble());
		SkinAnalysisLevel skinLevel = skinGradeRubric.calculateSkinLevel(rednessLevel, troubleLevel);

		log.info("최종 등급 계산 완료: skinImageId={}, rednessLevel={}, troubleLevel={}, skinLevel={}",
				request.skinImageId(), rednessLevel, troubleLevel, skinLevel);

		SkinAnalysis skinAnalysis = new SkinAnalysis(
				userId, skinImage,
				rednessLevel, result.redness().affectedRegions(), result.redness().maxIntensity(),
				troubleLevel, result.trouble().affectedRegions(), result.trouble().density(),
				skinLevel,
				result.imageQuality().lighting(), result.imageQuality().blur(),
				result.imageQuality().angle(), result.imageQuality().faceRatio(),
				SkinGradeRubric.RUBRIC_VERSION
		);

		return SkinAnalysisDto.Response.from(skinAnalysisRepository.save(skinAnalysis));
	}

	/**
	 * 피부 분석 히스토리 조회(Issue #20).
	 * period(일) 동안의 분석 이력을, 등급(SAFE/CAUTION/DANGER) 기준으로 반환한다.
	 * average는 산술 평균이 아니라 최빈 등급이다 — {@link #calculateModeLevel} 참고.
	 */
	public SkinAnalysisDto.HistoryResponse getHistory(Long userId, int periodDays) {
		LocalDateTime from = LocalDate.now().minusDays(periodDays - 1L).atStartOfDay();
		List<SkinAnalysis> analyses = skinAnalysisRepository.findAllByUserIdAndAnalyzedAtBetweenOrderByAnalyzedAtAsc(userId, from, LocalDateTime.now());

		if (analyses.isEmpty()) {
			log.info("피부 분석 히스토리 없음: periodDays={}, from={}", periodDays, from);
			return SkinAnalysisDto.HistoryResponse.empty(periodDays);
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
				history
		);
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
	 * GPT 관찰값의 구조적 완전성만 검증한다(등급 판단은 여기서 하지 않는다).
	 * 다음 중 하나라도 해당하면 저장하지 않고 AI_ANALYSIS_FAILED로 처리한다.
	 * - redness/trouble/imageQuality 구조 자체가 비어 있음(파싱 불완전)
	 * - affectedRegions가 아예 없음(null, 응답 형식 오류 — 빈 목록은 정상)
	 * - 이미지 품질(조명/블러/각도/얼굴 비율) 중 POOR 개수가 {@link #MAX_ALLOWED_POOR_QUALITY_COUNT}를 초과
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
			log.warn("AI 분석 검증 실패: 이미지 품질 부족(POOR {}개, 허용 {}개) - {}",
					poorQualityCount, MAX_ALLOWED_POOR_QUALITY_COUNT, result.imageQuality());
			throw new GlobalException(ErrorCode.AI_ANALYSIS_FAILED);
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
