package com.sangmyungyaho.barocare.ai.dto;

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
}
