package com.sangmyungyaho.barocare.report.service;

import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.report.entity.BaselineType;
import com.sangmyungyaho.barocare.report.entity.LifestyleFactorLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 생활습관 요인(수면/스트레스/수분 섭취) 판정 고정 규칙(개인화 원인 분석, "생활습관 요인판정" 단계).
 *
 * GPT가 raw 체크인 수치를 보고 임의로 등급을 정하지 않도록, 이 클래스가 먼저 GOOD/MODERATE/POOR를
 * 계산해서 넘긴다({@link com.sangmyungyaho.barocare.ai.client.AiClient#analyzeSkinChangeCauses}).
 * SkinGradeRubric이 SkinAnalysis 관찰값으로 SAFE/CAUTION/DANGER를 계산하는 것과 같은 역할 분담이다.
 *
 * 판정 기준은 두 가지 모드 중 하나다(둘 중 어느 것이 쓰였는지는 {@link Judgment#personalBaselineUsed()}로
 * 코드 레벨에서 명확히 구분된다):
 * - 개인 기준선: 최근 체크인 이력이 {@link #PERSONAL_BASELINE_MIN_HISTORY_DAYS}일 이상 쌓였으면,
 *   그 기간의 개인 평균 대비 최신 체크인이 얼마나 벗어났는지로 상대 판정한다.
 * - 고정 기준표: 이력이 부족한 신규 사용자 등은 고정된 임계값으로 절대 판정한다(데이터 부족 fallback).
 *
 * 등급(level)뿐 아니라, 판정에 실제로 사용한 기준값(baselineValue)과 그 차이(difference)도
 * {@link FactorJudgment}에 그대로 담아 반환한다 - 예전에는 이 값들을 판정 로직 내부 지역변수로만
 * 계산하고 버렸지만(GOOD/MODERATE/POOR만 리턴), 원인 리포트 화면에서 "평균 수면 5.4h / 현재 7h / 차이
 * +1.6h" 같은 근거를 보여주려면 API 응답까지 전달돼야 하므로 여기서부터 값을 보존한다.
 */
@Component
public class LifestyleFactorRubric {

	private static final Logger log = LoggerFactory.getLogger(LifestyleFactorRubric.class);

	// 이만큼의 이전 체크인 이력이 쌓이면 개인 기준선을 사용한다. 미만이면 고정 기준표를 사용한다.
	private static final int PERSONAL_BASELINE_MIN_HISTORY_DAYS = 7;

	// waterGoalMl이 저장되어 있지 않은 사용자를 위한 기본 목표치. RoutineService의 기존 기본값과 동일하게 맞춘다.
	private static final int DEFAULT_WATER_GOAL_ML = 2000;

	// ---- 고정 기준표(개인 기준선을 만들 수 없을 때 사용) ----
	private static final double FIXED_SLEEP_GOOD_HOURS = 7.0;
	private static final double FIXED_SLEEP_MODERATE_HOURS = 6.0;
	private static final int FIXED_STRESS_MODERATE_LEVEL = 3; // 1~5
	private static final int FIXED_STRESS_POOR_LEVEL = 4;
	private static final double FIXED_WATER_GOOD_RATIO = 0.8; // 목표 대비 섭취 비율
	private static final double FIXED_WATER_MODERATE_RATIO = 0.5;

	// ---- 개인 기준선 상대 판정 임계값 ----
	private static final double PERSONAL_SLEEP_POOR_DIFF_HOURS = -1.0;
	private static final double PERSONAL_STRESS_POOR_DIFF_LEVEL = 1.0;
	private static final double PERSONAL_WATER_POOR_DIFF_RATIO = -0.2;

	/**
	 * @param sleepLevel           수면 판정 등급
	 * @param stressLevel          스트레스 판정 등급
	 * @param waterLevel           수분 섭취 판정 등급(목표 음수량 대비 비율 기준)
	 * @param personalBaselineUsed true면 개인 기준선(최근 {@value #PERSONAL_BASELINE_MIN_HISTORY_DAYS}일 평균) 사용,
	 *                              false면 고정 기준표 사용(신규 사용자 등 이력 부족)
	 */
	public record Judgment(
			FactorJudgment sleep,
			FactorJudgment stress,
			FactorJudgment water,
			boolean personalBaselineUsed
	) {
		// 등급만 필요한 기존 호출부(candidateFactors 판정, AiDto.CheckinInput 구성)를 위한 편의 accessor.
		// 기준값/차이까지 필요하면 sleep()/stress()/water()로 FactorJudgment를 직접 사용한다.
		public LifestyleFactorLevel sleepLevel() {
			return sleep.level();
		}

		public LifestyleFactorLevel stressLevel() {
			return stress.level();
		}

		public LifestyleFactorLevel waterLevel() {
			return water.level();
		}
	}

	/**
	 * 요인 하나(수면/스트레스/수분)의 판정 결과. level뿐 아니라 판정에 실제로 쓰인 기준값과 차이까지 담아,
	 * "평균보다 1.6시간 적음" 같은 근거를 API 응답에 그대로 실을 수 있게 한다.
	 *
	 * @param level         GOOD/MODERATE/POOR 판정 등급
	 * @param baselineValue 비교에 사용한 기준값. baselineType이 PERSONAL_AVERAGE면 최근 개인 평균,
	 *                      RECOMMENDED면 고정 권장값(수면 7시간/스트레스 3단계/목표 음수량)이다.
	 * @param difference    최신 체크인 값 - baselineValue. 수면/수분은 음수가 나쁨(부족), 스트레스는
	 *                      양수가 나쁨(평소보다 높음)을 뜻한다.
	 * @param baselineType  baselineValue의 기준 종류
	 */
	public record FactorJudgment(
			LifestyleFactorLevel level,
			Double baselineValue,
			Double difference,
			BaselineType baselineType
	) {
	}

	/**
	 * @param latestCheckin    판정 대상 체크인(가장 최근)
	 * @param previousCheckins latestCheckin을 제외한 이전 체크인들(최신순 정렬, 없으면 빈 리스트 — 데이터 부족 fallback)
	 * @param waterGoalMl      사용자의 목표 음수량(ml). 온보딩/프로필에서 계산·저장된 값. null이면 기본값 사용.
	 */
	public Judgment judge(Checkin latestCheckin, List<Checkin> previousCheckins, Integer waterGoalMl) {
		List<Checkin> baselineWindow = previousCheckins.size() >= PERSONAL_BASELINE_MIN_HISTORY_DAYS
				? previousCheckins.subList(0, PERSONAL_BASELINE_MIN_HISTORY_DAYS)
				: List.of();
		boolean personalBaselineUsed = !baselineWindow.isEmpty();
		int waterGoal = waterGoalMl != null && waterGoalMl > 0 ? waterGoalMl : DEFAULT_WATER_GOAL_ML;

		FactorJudgment sleep = judgeSleep(latestCheckin.getSleepHours(), baselineWindow);
		FactorJudgment stress = judgeStress(latestCheckin.getStressLevel(), baselineWindow);
		FactorJudgment water = judgeWater(latestCheckin.getWaterIntakeMl(), waterGoal, baselineWindow);

		log.info("생활습관 요인 판정 완료: personalBaselineUsed={}, sleep={}, stress={}, water={}, waterGoalMl={}",
				personalBaselineUsed, sleep, stress, water, waterGoal);

		return new Judgment(sleep, stress, water, personalBaselineUsed);
	}

	private FactorJudgment judgeSleep(Double latestSleepHours, List<Checkin> baselineWindow) {
		if (!baselineWindow.isEmpty()) {
			double personalAverage = baselineWindow.stream().mapToDouble(Checkin::getSleepHours).average().orElseThrow();
			double diff = latestSleepHours - personalAverage;
			LifestyleFactorLevel level = diff <= PERSONAL_SLEEP_POOR_DIFF_HOURS ? LifestyleFactorLevel.POOR
					: diff < 0 ? LifestyleFactorLevel.MODERATE : LifestyleFactorLevel.GOOD;
			return new FactorJudgment(level, round1(personalAverage), round1(diff), BaselineType.PERSONAL_AVERAGE);
		}

		LifestyleFactorLevel level = latestSleepHours >= FIXED_SLEEP_GOOD_HOURS ? LifestyleFactorLevel.GOOD
				: latestSleepHours >= FIXED_SLEEP_MODERATE_HOURS ? LifestyleFactorLevel.MODERATE : LifestyleFactorLevel.POOR;
		double diff = latestSleepHours - FIXED_SLEEP_GOOD_HOURS;
		return new FactorJudgment(level, FIXED_SLEEP_GOOD_HOURS, round1(diff), BaselineType.RECOMMENDED);
	}

	private FactorJudgment judgeStress(Integer latestStressLevel, List<Checkin> baselineWindow) {
		if (!baselineWindow.isEmpty()) {
			double personalAverage = baselineWindow.stream().mapToInt(Checkin::getStressLevel).average().orElseThrow();
			double diff = latestStressLevel - personalAverage;
			LifestyleFactorLevel level = diff >= PERSONAL_STRESS_POOR_DIFF_LEVEL ? LifestyleFactorLevel.POOR
					: diff > 0 ? LifestyleFactorLevel.MODERATE : LifestyleFactorLevel.GOOD;
			return new FactorJudgment(level, round1(personalAverage), round1(diff), BaselineType.PERSONAL_AVERAGE);
		}

		LifestyleFactorLevel level = latestStressLevel >= FIXED_STRESS_POOR_LEVEL ? LifestyleFactorLevel.POOR
				: latestStressLevel >= FIXED_STRESS_MODERATE_LEVEL ? LifestyleFactorLevel.MODERATE : LifestyleFactorLevel.GOOD;
		double diff = latestStressLevel - FIXED_STRESS_MODERATE_LEVEL;
		return new FactorJudgment(level, (double) FIXED_STRESS_MODERATE_LEVEL, round1(diff), BaselineType.RECOMMENDED);
	}

	// 절대 섭취량(ml)이 아니라 목표 음수량 대비 비율로 판정한다. baselineValue/difference는 화면 표시를
	// 위해 latest_checkin과 같은 단위(ml)로 환산해서 담는다(판정 자체는 비율 기준 그대로 유지).
	private FactorJudgment judgeWater(Integer latestWaterIntakeMl, int waterGoalMl, List<Checkin> baselineWindow) {
		double latestRatio = latestWaterIntakeMl / (double) waterGoalMl;

		if (!baselineWindow.isEmpty()) {
			double personalAverageRatio = baselineWindow.stream()
					.mapToDouble(c -> c.getWaterIntakeMl() / (double) waterGoalMl)
					.average().orElseThrow();
			double personalAverageMl = baselineWindow.stream()
					.mapToInt(Checkin::getWaterIntakeMl)
					.average().orElseThrow();
			double diffRatio = latestRatio - personalAverageRatio;
			LifestyleFactorLevel level = diffRatio <= PERSONAL_WATER_POOR_DIFF_RATIO ? LifestyleFactorLevel.POOR
					: diffRatio < 0 ? LifestyleFactorLevel.MODERATE : LifestyleFactorLevel.GOOD;
			double diffMl = latestWaterIntakeMl - personalAverageMl;
			return new FactorJudgment(level, round1(personalAverageMl), round1(diffMl), BaselineType.PERSONAL_AVERAGE);
		}

		LifestyleFactorLevel level = latestRatio >= FIXED_WATER_GOOD_RATIO ? LifestyleFactorLevel.GOOD
				: latestRatio >= FIXED_WATER_MODERATE_RATIO ? LifestyleFactorLevel.MODERATE : LifestyleFactorLevel.POOR;
		double diffMl = latestWaterIntakeMl - waterGoalMl;
		return new FactorJudgment(level, (double) waterGoalMl, round1(diffMl), BaselineType.RECOMMENDED);
	}

	private double round1(double value) {
		return Math.round(value * 10) / 10.0;
	}
}
