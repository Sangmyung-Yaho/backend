package com.sangmyungyaho.barocare.ai.dto;

import com.sangmyungyaho.barocare.report.entity.LifestyleFactorLevel;
import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.report.entity.ReportChangeStatus;
import com.sangmyungyaho.barocare.skin.entity.ChangeDirection;
import com.sangmyungyaho.barocare.skin.entity.FaceRegion;
import com.sangmyungyaho.barocare.skin.entity.ImageQualityRating;
import com.sangmyungyaho.barocare.skin.entity.RednessIntensity;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
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
	 *
	 * rednessStatus/troubleStatus(IMPROVED/WORSENED/UNCHANGED)가 점수 기준의 "공식" 변화 판정이다.
	 * rednessDirection/troubleDirection(INCREASED/STABLE/DECREASED)은 피부 변화 비교(baseline/직전 분석)
	 * 단계의 보조 신호로, 이미 계산된 SkinComparison(AI 이미지 비교)이 있으면 그 값을, 없으면 status로부터
	 * 결정적으로 유도한 값을 담는다(GPT 재호출 없음) - skin-signal(getLatestSkinSignal)과 동일한 규칙이다.
	 * baseline*Level/comparedAgainstBaseline은 "최초 분석(baseline)"과의 관계를 보조 컨텍스트로 제공한다:
	 * comparedAgainstBaseline=true면 previous가 곧 baseline(사용자의 두 번째 분석)이라는 뜻이고,
	 * false면 previous보다 더 이전에 baseline이 따로 있다는 뜻이다(baseline*Level은 참고용 등급).
	 */
	public record SkinChangeInput(
			Integer rednessChange,
			ReportChangeStatus rednessStatus,
			ChangeDirection rednessDirection,
			Integer troubleChange,
			ReportChangeStatus troubleStatus,
			ChangeDirection troubleDirection,
			boolean comparedAgainstBaseline,
			SkinAnalysisLevel baselineRednessLevel,
			SkinAnalysisLevel baselineTroubleLevel
	) {
	}

	/**
	 * 원인 분석에 참고하는 체크인 데이터. average* 필드는 최신 체크인을 제외한 이전 체크인들의 평균이며,
	 * 비교할 이전 체크인이 없으면 null이다(둘 다 Java가 계산해서 전달하는 값 - AI는 계산하지 않는다).
	 *
	 * sleepLevel/stressLevel/waterLevel은 {@code LifestyleFactorRubric}이 이미 판정해서 전달하는 값이다 -
	 * GPT가 raw 수치를 보고 임의로 등급을 정하지 않도록, "부족/보통/충분"에 해당하는 판정을 먼저 계산해서 넘긴다.
	 * personalBaselineUsed는 그 판정이 개인 기준선(최근 7일 평균) 기준인지, 고정 기준표 기준인지를 나타낸다
	 * (체크인 이력이 부족한 신규 사용자는 고정 기준표가 쓰인다).
	 *
	 * candidateFactors는 백엔드가 이미 "주요 위험 요인 후보"로 확정한 목록이다(sleepLevel/stressLevel/waterLevel
	 * 중 POOR로 판정된 요인만). GPT는 이 목록 밖의 요인을 causes에 추가하거나, 이 목록에 있는 요인을 임의로
	 * 빼지 않는다 - ReportService가 최종적으로 이 목록 기준으로 causes를 한 번 더 걸러내므로, GPT가 이 지침을
	 * 어겨도 목록 밖 요인이 최종 응답에 남지 않는다(코드 레벨 강제).
	 */
	public record CheckinInput(
			Double latestSleepHours,
			Integer latestStressLevel,
			Integer latestWaterIntakeMl,
			Double averageSleepHours,
			Double averageStressLevel,
			Double averageWaterIntakeMl,
			LifestyleFactorLevel sleepLevel,
			LifestyleFactorLevel stressLevel,
			LifestyleFactorLevel waterLevel,
			boolean personalBaselineUsed,
			List<ReportCauseFactor> candidateFactors
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

	/**
	 * 추천 성분(ISSUE-30). SkinAnalysis의 redness/trouble/skin 등급만 보고 화장품 성분을 추천한다 -
	 * 제품명은 여기서 다루지 않는다(제품 매칭은 별도로 ProductSearchClient가 실시간 웹 검색으로 담당).
	 */
	public record IngredientRecommendationResult(
			List<IngredientSuggestion> ingredients
	) {
	}

	public record IngredientSuggestion(
			String name,
			String reason
	) {
	}
}
