package com.sangmyungyaho.barocare.skin.service;

import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.skin.dto.SkinComparisonDto;
import com.sangmyungyaho.barocare.skin.entity.ChangeDirection;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinComparison;
import com.sangmyungyaho.barocare.skin.repository.SkinAnalysisRepository;
import com.sangmyungyaho.barocare.skin.repository.SkinComparisonRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 이전/현재 피부 사진 "변화" 비교(CHK-332). 단일 이미지 분석({@link SkinAnalysisService})과
 * {@link SkinGradeRubric}은 이 기능과 무관하며 변경하지 않는다.
 *
 * 원본 이미지 보관 정책 변경(분석 완료 후 원본 즉시 삭제)에 따라, 더 이상 GPT Vision에 원본 이미지
 * 두 장을 다시 전달하는 방식으로 비교하지 않는다. 대신 각 SkinAnalysis에 이미 저장된 관찰값
 * (redness/troubleLevel 및 그 세부 강도/밀도)만으로 변화 방향(INCREASED/STABLE/DECREASED)을
 * 결정적으로 계산한다 - 원본 이미지가 이미 삭제된 상태여도 항상 동작한다.
 *
 * ReportService는 이 결과(SkinComparison.rednessChange/troubleChange)를 "있으면 쓰고 없으면 등급
 * 변화로 대체 유도"하는 방식으로만 소비하므로(ReportService.java 참고), 계산 방식이 AI 기반에서
 * 규칙 기반으로 바뀌어도 하위 소비자(Report/Routine/DTO)는 영향을 받지 않는다.
 */
@Service
@RequiredArgsConstructor
public class SkinComparisonService {

	private static final Logger log = LoggerFactory.getLogger(SkinComparisonService.class);

	private final SkinAnalysisRepository skinAnalysisRepository;
	private final SkinComparisonRepository skinComparisonRepository;

	/**
	 * @return 비교 결과와, 이번 호출로 새로 계산/저장했는지 여부(컨트롤러가 201/200 결정에 사용)
	 */
	public Outcome compareSkin(SkinComparisonDto.Request request) {
		SkinAnalysis current = skinAnalysisRepository.findById(request.currentSkinAnalysisId())
				.orElseThrow(() -> new GlobalException(ErrorCode.SKIN_ANALYSIS_NOT_FOUND));

		if (request.previousSkinAnalysisId() == null) {
			log.info("이전 분석 없음(최초 분석): currentSkinAnalysisId={} - 비교를 생략한다.", current.getId());
			return new Outcome(SkinComparisonDto.Response.withoutPrevious(current.getId()), false);
		}

		SkinAnalysis previous = skinAnalysisRepository.findById(request.previousSkinAnalysisId())
				.orElseThrow(() -> new GlobalException(ErrorCode.SKIN_ANALYSIS_NOT_FOUND));

		Optional<SkinComparison> existing = skinComparisonRepository
				.findByCurrentSkinAnalysis_IdAndPreviousSkinAnalysis_Id(current.getId(), previous.getId());
		if (existing.isPresent()) {
			log.info("기존 비교 결과 재사용: skinComparisonId={}, currentSkinAnalysisId={}, previousSkinAnalysisId={}",
					existing.get().getId(), current.getId(), previous.getId());
			return new Outcome(SkinComparisonDto.Response.from(existing.get()), false);
		}

		ChangeDirection rednessChange = determineRednessChange(current, previous);
		ChangeDirection troubleChange = determineTroubleChange(current, previous);

		log.info("비교 결과 계산 완료(저장된 관찰값 기반, 원본 이미지 미사용): "
						+ "currentSkinAnalysisId={}, previousSkinAnalysisId={}, redness={}, trouble={}",
				current.getId(), previous.getId(), rednessChange, troubleChange);

		SkinComparison saved = skinComparisonRepository.save(
				new SkinComparison(current, previous, rednessChange, troubleChange)
		);
		return new Outcome(SkinComparisonDto.Response.from(saved), true);
	}

	/**
	 * 붉은기 변화 방향. 1차로 rednessLevel(SAFE/CAUTION/DANGER) ordinal 차이로 판단하고,
	 * 두 분석의 레벨이 같으면 rednessMaxIntensity(MILD/MODERATE/SEVERE)로 세분화한다.
	 */
	private ChangeDirection determineRednessChange(SkinAnalysis current, SkinAnalysis previous) {
		int levelDiff = current.getRednessLevel().ordinal() - previous.getRednessLevel().ordinal();
		if (levelDiff != 0) {
			return levelDiff > 0 ? ChangeDirection.INCREASED : ChangeDirection.DECREASED;
		}
		return compareByOrdinal(current.getRednessMaxIntensity(), previous.getRednessMaxIntensity());
	}

	/**
	 * 트러블 변화 방향. 1차로 troubleLevel ordinal 차이로 판단하고, 레벨이 같으면
	 * troubleDensity(FEW/MANY)로 세분화한다.
	 */
	private ChangeDirection determineTroubleChange(SkinAnalysis current, SkinAnalysis previous) {
		int levelDiff = current.getTroubleLevel().ordinal() - previous.getTroubleLevel().ordinal();
		if (levelDiff != 0) {
			return levelDiff > 0 ? ChangeDirection.INCREASED : ChangeDirection.DECREASED;
		}
		return compareByOrdinal(current.getTroubleDensity(), previous.getTroubleDensity());
	}

	// 세부 강도/밀도 값은 nullable(AI가 관찰하지 못하면 null)이므로, 둘 중 하나라도 없으면
	// 판단 근거가 부족한 것으로 보고 STABLE로 처리한다(과대 해석 방지).
	private <E extends Enum<E>> ChangeDirection compareByOrdinal(E current, E previous) {
		if (current == null || previous == null) {
			return ChangeDirection.STABLE;
		}
		int diff = current.ordinal() - previous.ordinal();
		if (diff > 0) {
			return ChangeDirection.INCREASED;
		}
		if (diff < 0) {
			return ChangeDirection.DECREASED;
		}
		return ChangeDirection.STABLE;
	}

	public record Outcome(SkinComparisonDto.Response response, boolean created) {
	}
}
