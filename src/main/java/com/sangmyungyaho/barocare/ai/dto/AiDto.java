package com.sangmyungyaho.barocare.ai.dto;

import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.report.entity.ReportChangeStatus;
import com.sangmyungyaho.barocare.skin.entity.ChangeDirection;
import com.sangmyungyaho.barocare.skin.entity.FaceRegion;
import com.sangmyungyaho.barocare.skin.entity.ImageQualityRating;
import com.sangmyungyaho.barocare.skin.entity.RednessIntensity;
import com.sangmyungyaho.barocare.skin.entity.TroubleDensity;

import java.util.List;

/**
 * OpenAI 응답을 매핑하는 내부 전용 모델.
 * ChatClient의 entity() 구조화 출력으로 채워지며, API 외부 응답으로 그대로 노출하지 않는다.
 *
 * 중요: GPT는 SAFE/CAUTION/DANGER 같은 최종 등급을 직접 반환하지 않는다.
 * 사진에서 관찰 가능한 사실(구역/강도/밀도)만 반환하고, 최종 등급은
 * {@code SkinGradeRubric}이 고정 규칙으로 계산한다.
 */
public class AiDto {

	public record SkinAnalysisResult(
			RednessObservation redness,
			TroubleObservation trouble,
			ImageQuality imageQuality
	) {
	}

	/**
	 * @param affectedRegions 붉은기가 관찰된 구역(없으면 빈 목록)
	 * @param maxIntensity    관찰된 구역 중 가장 강한 붉은기 정도. affectedRegions가 비어 있으면 무시된다.
	 */
	public record RednessObservation(
			List<FaceRegion> affectedRegions,
			RednessIntensity maxIntensity
	) {
	}

	/**
	 * @param affectedRegions 트러블이 관찰된 구역(없으면 빈 목록)
	 * @param density         관찰된 트러블의 전반적인 개수 구간. affectedRegions가 비어 있으면 무시된다.
	 */
	public record TroubleObservation(
			List<FaceRegion> affectedRegions,
			TroubleDensity density
	) {
	}

	/**
	 * 조명/블러/각도/얼굴 비율 각각을 GOOD 또는 POOR로만 판정한다(숫자 임계값 사용 안 함).
	 */
	public record ImageQuality(
			ImageQualityRating lighting,
			ImageQualityRating blur,
			ImageQualityRating angle,
			ImageQualityRating faceRatio
	) {
	}

	/**
	 * 이전/현재 사진 두 장을 비교한 결과. SAFE/CAUTION/DANGER 등급은 여기 없다 —
	 * 오직 이전 대비 현재의 상대적 변화 방향만 담는다.
	 */
	public record SkinComparisonResult(
			ChangeDirection redness,
			ChangeDirection trouble
	) {
	}

	/**
	 * 피부 변화 원인 분석(REP-101)에 전달하는 입력값. 점수/변화량/상태는 이미 Java에서 확정적으로
	 * 계산된 값이며, AI는 이 값을 그대로 참고할 뿐 다시 계산하거나 새로운 점수를 만들지 않는다.
	 */
	public record SkinChangeInput(
			Integer rednessChange,
			ReportChangeStatus rednessStatus,
			Integer troubleChange,
			ReportChangeStatus troubleStatus
	) {
	}

	/**
	 * 원인 분석에 참고하는 체크인 데이터. average* 필드는 최신 체크인을 제외한 이전 체크인들의 평균이며,
	 * 비교할 이전 체크인이 없으면 null이다(둘 다 Java가 계산해서 전달하는 값 - AI는 계산하지 않는다).
	 */
	public record CheckinInput(
			Double latestSleepHours,
			Integer latestStressLevel,
			Integer latestWaterIntakeMl,
			Double averageSleepHours,
			Double averageStressLevel,
			Double averageWaterIntakeMl
	) {
	}

	/**
	 * AI가 반환하는 원인 후보 해석. factor는 {@link ReportCauseFactor}로 제한되어 있어
	 * Checkin에 실제로 존재하는 요인 밖의 값을 반환할 수 없다. currentValue/unit은 여기 없다 -
	 * Checkin 실측값으로 Java가 채운다(REP-101 원칙: AI는 해석/설명만 담당).
	 */
	public record CauseAnalysisResult(
			List<Cause> causes,
			String summary
	) {
	}

	public record Cause(
			ReportCauseFactor factor,
			String name,
			String description
	) {
	}
}
