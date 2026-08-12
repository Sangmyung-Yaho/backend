package com.sangmyungyaho.barocare.skin.service;

import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.skin.entity.RednessIntensity;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.skin.entity.TroubleDensity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * GPT가 추출한 관찰값(구역/강도/밀도)으로 SAFE/CAUTION/DANGER 등급을 계산하는 고정 rubric.
 *
 * GPT는 등급을 직접 판정하지 않는다 — "범위(affectedRegions)"와 "강도(intensity/density)"라는
 * 두 축을 독립적으로 관찰해서 보고할 뿐이고, 두 축을 AND로 결합해 최종 등급을 매기는 판단은
 * 전부 이 클래스가 담당한다. rubric(기준)을 바꾸고 싶으면 이 클래스의 상수/메서드만 수정하면 된다.
 */
@Component
public class SkinGradeRubric {

	private static final Logger log = LoggerFactory.getLogger(SkinGradeRubric.class);

	/**
	 * 등급 계산 규칙 버전. 이 클래스의 판정 로직을 바꾸면 함께 올린다.
	 * GPT 관찰 스키마 버전은 {@code AiClient.OBSERVATION_SCHEMA_VERSION}에서 별도로 관리한다.
	 */
	public static final String RUBRIC_VERSION = "v3";

	// DANGER로 판정하려면 최소 이만큼의 구역에서 관찰돼야 한다("범위").
	private static final int MIN_AFFECTED_REGIONS_FOR_DANGER = 2;

	public SkinAnalysisLevel calculateRednessLevel(AiDto.RednessObservation observation) {
		List<?> regions = observation.affectedRegions();
		if (regions == null || regions.isEmpty()) {
			return SkinAnalysisLevel.SAFE;
		}

		boolean wideRange = regions.size() >= MIN_AFFECTED_REGIONS_FOR_DANGER;
		boolean severeIntensity = observation.maxIntensity() == RednessIntensity.SEVERE;

		// DANGER는 "범위"와 "강도"가 모두 뚜렷할 때만. 둘 중 하나만 충족하면 CAUTION에 머문다.
		SkinAnalysisLevel level = (wideRange && severeIntensity) ? SkinAnalysisLevel.DANGER : SkinAnalysisLevel.CAUTION;

		log.info("redness 등급 계산: affectedRegions={}({}개), maxIntensity={} -> wideRange={}, severeIntensity={} -> level={}",
				regions, regions.size(), observation.maxIntensity(), wideRange, severeIntensity, level);
		return level;
	}

	public SkinAnalysisLevel calculateTroubleLevel(AiDto.TroubleObservation observation) {
		List<?> regions = observation.affectedRegions();
		if (regions == null || regions.isEmpty()) {
			return SkinAnalysisLevel.SAFE;
		}

		boolean wideRange = regions.size() >= MIN_AFFECTED_REGIONS_FOR_DANGER;
		boolean highDensity = observation.density() == TroubleDensity.MANY;

		// 작은 국소 트러블이 여러 부위에 흩어져 있다는 이유만으로는(density=FEW) DANGER로 판정하지 않는다.
		SkinAnalysisLevel level = (wideRange && highDensity) ? SkinAnalysisLevel.DANGER : SkinAnalysisLevel.CAUTION;

		log.info("trouble 등급 계산: affectedRegions={}({}개), density={} -> wideRange={}, highDensity={} -> level={}",
				regions, regions.size(), observation.density(), wideRange, highDensity, level);
		return level;
	}

	// skinLevel은 OpenAI가 아니라 서버가 계산한다: SAFE < CAUTION < DANGER 중 더 높은 위험도를 채택.
	public SkinAnalysisLevel calculateSkinLevel(SkinAnalysisLevel rednessLevel, SkinAnalysisLevel troubleLevel) {
		return rednessLevel.ordinal() >= troubleLevel.ordinal() ? rednessLevel : troubleLevel;
	}
}
