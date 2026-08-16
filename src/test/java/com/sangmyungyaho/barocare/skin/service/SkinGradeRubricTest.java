package com.sangmyungyaho.barocare.skin.service;

import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.skin.entity.FaceRegion;
import com.sangmyungyaho.barocare.skin.entity.RednessIntensity;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.skin.entity.TroubleDensity;
import com.sangmyungyaho.barocare.user.entity.SkinType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * feat: 개인화 피부 원인 분석 및 케어 연동 로직 구현 - 피부타입 보정(SkinGradeRubric) 단위 테스트.
 * skinType이 null(피부타입 미설정)이면 기존 기준(threshold=2)과 동일하게 동작해야 하고(호환성 유지),
 * SENSITIVE/OILY처럼 임계값이 낮은 피부타입은 더 적은 구역으로도 DANGER가 나와야 한다.
 */
class SkinGradeRubricTest {

	private final SkinGradeRubric rubric = new SkinGradeRubric();

	@Test
	void redness_피부타입이_없으면_기존_기준대로_구역_1개는_DANGER가_아니라_CAUTION이다() {
		AiDto.RednessObservation observation = new AiDto.RednessObservation(
				List.of(FaceRegion.CHEEK_LEFT), RednessIntensity.SEVERE);

		SkinAnalysisLevel level = rubric.calculateRednessLevel(observation, null);

		assertThat(level).isEqualTo(SkinAnalysisLevel.CAUTION);
	}

	@Test
	void redness_피부타입이_없으면_기존_기준대로_구역_2개_이상_SEVERE면_DANGER다() {
		AiDto.RednessObservation observation = new AiDto.RednessObservation(
				List.of(FaceRegion.CHEEK_LEFT, FaceRegion.CHEEK_RIGHT), RednessIntensity.SEVERE);

		SkinAnalysisLevel level = rubric.calculateRednessLevel(observation, null);

		assertThat(level).isEqualTo(SkinAnalysisLevel.DANGER);
	}

	@Test
	void redness_민감성_피부는_구역_1개_SEVERE만으로도_DANGER로_보정된다() {
		AiDto.RednessObservation observation = new AiDto.RednessObservation(
				List.of(FaceRegion.CHEEK_LEFT), RednessIntensity.SEVERE);

		SkinAnalysisLevel level = rubric.calculateRednessLevel(observation, SkinType.SENSITIVE);

		assertThat(level).isEqualTo(SkinAnalysisLevel.DANGER);
	}

	@Test
	void redness_지성_피부는_기존_기준과_동일하게_구역_1개_SEVERE는_CAUTION이다() {
		AiDto.RednessObservation observation = new AiDto.RednessObservation(
				List.of(FaceRegion.CHEEK_LEFT), RednessIntensity.SEVERE);

		SkinAnalysisLevel level = rubric.calculateRednessLevel(observation, SkinType.OILY);

		assertThat(level).isEqualTo(SkinAnalysisLevel.CAUTION);
	}

	@Test
	void redness_관찰_구역이_없으면_피부타입과_무관하게_SAFE다() {
		AiDto.RednessObservation observation = new AiDto.RednessObservation(List.of(), null);

		assertThat(rubric.calculateRednessLevel(observation, SkinType.SENSITIVE)).isEqualTo(SkinAnalysisLevel.SAFE);
		assertThat(rubric.calculateRednessLevel(observation, null)).isEqualTo(SkinAnalysisLevel.SAFE);
	}

	@Test
	void trouble_지성_피부는_구역_1개_MANY만으로도_DANGER로_보정된다() {
		AiDto.TroubleObservation observation = new AiDto.TroubleObservation(
				List.of(FaceRegion.CHIN), TroubleDensity.MANY);

		SkinAnalysisLevel level = rubric.calculateTroubleLevel(observation, SkinType.OILY);

		assertThat(level).isEqualTo(SkinAnalysisLevel.DANGER);
	}

	@Test
	void trouble_건성_피부는_기존_기준과_동일하게_구역_1개_MANY는_CAUTION이다() {
		AiDto.TroubleObservation observation = new AiDto.TroubleObservation(
				List.of(FaceRegion.CHIN), TroubleDensity.MANY);

		SkinAnalysisLevel level = rubric.calculateTroubleLevel(observation, SkinType.DRY);

		assertThat(level).isEqualTo(SkinAnalysisLevel.CAUTION);
	}

	@Test
	void trouble_피부타입이_없으면_기존_기준대로_동작한다() {
		AiDto.TroubleObservation observation = new AiDto.TroubleObservation(
				List.of(FaceRegion.CHIN, FaceRegion.NOSE), TroubleDensity.MANY);

		assertThat(rubric.calculateTroubleLevel(observation, null)).isEqualTo(SkinAnalysisLevel.DANGER);
	}

	@Test
	void skinLevel은_redness와_trouble_중_더_위험한_등급을_그대로_채택한다() {
		SkinAnalysisLevel level = rubric.calculateSkinLevel(SkinAnalysisLevel.CAUTION, SkinAnalysisLevel.DANGER);

		assertThat(level).isEqualTo(SkinAnalysisLevel.DANGER);
	}
}
