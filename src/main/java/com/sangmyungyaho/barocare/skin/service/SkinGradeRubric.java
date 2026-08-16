package com.sangmyungyaho.barocare.skin.service;

import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.skin.entity.RednessIntensity;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.skin.entity.TroubleDensity;
import com.sangmyungyaho.barocare.user.entity.SkinType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * GPT가 추출한 관찰값(구역/강도/밀도)으로 SAFE/CAUTION/DANGER 등급을 계산하는 고정 rubric.
 *
 * GPT는 등급을 직접 판정하지 않는다 — "범위(affectedRegions)"와 "강도(intensity/density)"라는
 * 두 축을 독립적으로 관찰해서 보고할 뿐이고, 두 축을 AND로 결합해 최종 등급을 매기는 판단은
 * 전부 이 클래스가 담당한다. rubric(기준)을 바꾸고 싶으면 이 클래스의 상수/메서드만 수정하면 된다.
 *
 * 피부타입 보정(개인화): 피부타입에 따라 같은 관찰값이라도 위험도를 다르게 해석해야 하므로,
 * "범위" 판정 임계값(구역 개수)을 피부타입별로 다르게 적용한다. skinType이 null이거나 매핑이
 * 없으면 기존 기준(threshold=2)을 그대로 사용한다(데이터 부족 fallback, 기존 동작과 100% 동일).
 */
@Component
public class SkinGradeRubric {

	private static final Logger log = LoggerFactory.getLogger(SkinGradeRubric.class);

	/**
	 * 등급 계산 규칙 버전. 이 클래스의 판정 로직을 바꾸면 함께 올린다.
	 * GPT 관찰 스키마 버전은 {@code AiClient.OBSERVATION_SCHEMA_VERSION}에서 별도로 관리한다.
	 * v4: 피부타입별 보정계수(구역 임계값) 적용 - 개인화 피부 원인 분석 이슈.
	 */
	public static final String RUBRIC_VERSION = "v4";

	// DANGER로 판정하려면 최소 이만큼의 구역에서 관찰돼야 한다("범위"). 피부타입 매핑이 없을 때의 기존 기준.
	private static final int DEFAULT_REGION_THRESHOLD_FOR_DANGER = 2;

	// 피부타입별 redness 구역 임계값 보정. 민감성/건성 피부는 더 적은 범위에서도 DANGER 후보로 본다.
	private static final Map<SkinType, Integer> REDNESS_REGION_THRESHOLD_BY_SKIN_TYPE = Map.of(
			SkinType.SENSITIVE, 1,
			SkinType.DRY, 1,
			SkinType.OILY, 2,
			SkinType.COMBINATION, 2
	);

	// 피부타입별 trouble 구역 임계값 보정. 지성/민감성 피부는 트러블 범위에 더 민감하게 반응한다고 본다.
	private static final Map<SkinType, Integer> TROUBLE_REGION_THRESHOLD_BY_SKIN_TYPE = Map.of(
			SkinType.OILY, 1,
			SkinType.SENSITIVE, 1,
			SkinType.DRY, 2,
			SkinType.COMBINATION, 2
	);

	public SkinAnalysisLevel calculateRednessLevel(AiDto.RednessObservation observation, SkinType skinType) {
		List<?> regions = observation.affectedRegions();
		if (regions == null || regions.isEmpty()) {
			return SkinAnalysisLevel.SAFE;
		}

		// Map.of()는 get(null)에서 NPE를 던지므로 skinType null(피부타입 미설정)을 먼저 걸러낸다.
		int regionThreshold = skinType == null
				? DEFAULT_REGION_THRESHOLD_FOR_DANGER
				: REDNESS_REGION_THRESHOLD_BY_SKIN_TYPE.getOrDefault(skinType, DEFAULT_REGION_THRESHOLD_FOR_DANGER);
		boolean wideRange = regions.size() >= regionThreshold;
		boolean severeIntensity = observation.maxIntensity() == RednessIntensity.SEVERE;

		// DANGER는 "범위"와 "강도"가 모두 뚜렷할 때만. 둘 중 하나만 충족하면 CAUTION에 머문다.
		SkinAnalysisLevel level = (wideRange && severeIntensity) ? SkinAnalysisLevel.DANGER : SkinAnalysisLevel.CAUTION;

		log.info("redness 등급 계산: affectedRegions={}({}개), maxIntensity={}, skinType={}, regionThreshold={} -> wideRange={}, severeIntensity={} -> level={}",
				regions, regions.size(), observation.maxIntensity(), skinType, regionThreshold, wideRange, severeIntensity, level);
		return level;
	}

	public SkinAnalysisLevel calculateTroubleLevel(AiDto.TroubleObservation observation, SkinType skinType) {
		List<?> regions = observation.affectedRegions();
		if (regions == null || regions.isEmpty()) {
			return SkinAnalysisLevel.SAFE;
		}

		// Map.of()는 get(null)에서 NPE를 던지므로 skinType null(피부타입 미설정)을 먼저 걸러낸다.
		int regionThreshold = skinType == null
				? DEFAULT_REGION_THRESHOLD_FOR_DANGER
				: TROUBLE_REGION_THRESHOLD_BY_SKIN_TYPE.getOrDefault(skinType, DEFAULT_REGION_THRESHOLD_FOR_DANGER);
		boolean wideRange = regions.size() >= regionThreshold;
		boolean highDensity = observation.density() == TroubleDensity.MANY;

		// 작은 국소 트러블이 여러 부위에 흩어져 있다는 이유만으로는(density=FEW) DANGER로 판정하지 않는다.
		SkinAnalysisLevel level = (wideRange && highDensity) ? SkinAnalysisLevel.DANGER : SkinAnalysisLevel.CAUTION;

		log.info("trouble 등급 계산: affectedRegions={}({}개), density={}, skinType={}, regionThreshold={} -> wideRange={}, highDensity={} -> level={}",
				regions, regions.size(), observation.density(), skinType, regionThreshold, wideRange, highDensity, level);
		return level;
	}

	// skinLevel은 OpenAI가 아니라 서버가 계산한다: SAFE < CAUTION < DANGER 중 더 높은 위험도를 채택.
	public SkinAnalysisLevel calculateSkinLevel(SkinAnalysisLevel rednessLevel, SkinAnalysisLevel troubleLevel) {
		return rednessLevel.ordinal() >= troubleLevel.ordinal() ? rednessLevel : troubleLevel;
	}
}
