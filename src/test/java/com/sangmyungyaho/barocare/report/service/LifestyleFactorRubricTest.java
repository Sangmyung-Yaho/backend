package com.sangmyungyaho.barocare.report.service;

import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.report.entity.BaselineType;
import com.sangmyungyaho.barocare.report.entity.LifestyleFactorLevel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * feat: 개인화 피부 원인 분석 및 케어 연동 로직 구현 - 생활습관 요인판정(수면/스트레스/수분) 단위 테스트.
 * "7일 개인기준선" vs "고정 기준표" 두 모드가 코드 레벨에서 명확히 구분되는지, 수분 판정이 목표
 * 음수량 대비 비율로 계산되는지를 중심으로 검증한다.
 */
class LifestyleFactorRubricTest {

	private static final Long USER_ID = 1L;
	private static final int WATER_GOAL_ML = 2000;

	private final LifestyleFactorRubric rubric = new LifestyleFactorRubric();

	@Test
	void 이전_체크인이_7건_미만이면_고정_기준표를_사용한다() {
		Checkin latest = checkin(6.5, 3, 1200); // 수면 6.5h(고정기준 6~7=MODERATE), 스트레스 3(고정기준 3~3=MODERATE), 물 60%(0.5~0.8=MODERATE)
		List<Checkin> previous = List.of(checkin(7.0, 2, 1800)); // 1건뿐 -> 개인기준선 불가

		LifestyleFactorRubric.Judgment judgment = rubric.judge(latest, previous, WATER_GOAL_ML);

		assertThat(judgment.personalBaselineUsed()).isFalse();
		assertThat(judgment.sleepLevel()).isEqualTo(LifestyleFactorLevel.MODERATE);
		assertThat(judgment.stressLevel()).isEqualTo(LifestyleFactorLevel.MODERATE);
		assertThat(judgment.waterLevel()).isEqualTo(LifestyleFactorLevel.MODERATE);

		// 고정 기준표(RECOMMENDED) 모드에서는 baseline_value가 권장값(수면 7h/스트레스 3단계/목표 음수량)이다.
		assertThat(judgment.sleep().baselineType()).isEqualTo(BaselineType.RECOMMENDED);
		assertThat(judgment.sleep().baselineValue()).isEqualTo(7.0);
		assertThat(judgment.sleep().difference()).isEqualTo(-0.5); // 6.5 - 7.0
		assertThat(judgment.stress().baselineType()).isEqualTo(BaselineType.RECOMMENDED);
		assertThat(judgment.stress().baselineValue()).isEqualTo(3.0);
		assertThat(judgment.stress().difference()).isEqualTo(0.0); // 3 - 3
		assertThat(judgment.water().baselineType()).isEqualTo(BaselineType.RECOMMENDED);
		assertThat(judgment.water().baselineValue()).isEqualTo(2000.0); // waterGoalMl 자체
		assertThat(judgment.water().difference()).isEqualTo(-800.0); // 1200 - 2000
	}

	@Test
	void 이전_체크인이_7건_이상이면_개인_기준선을_사용한다() {
		Checkin latest = checkin(5.0, 4, 800); // 개인 평균(수면 7h, 스트레스 2, 물 100%) 대비 크게 나쁨
		List<Checkin> previous = sevenDaysOf(7.0, 2, WATER_GOAL_ML);

		LifestyleFactorRubric.Judgment judgment = rubric.judge(latest, previous, WATER_GOAL_ML);

		assertThat(judgment.personalBaselineUsed()).isTrue();
		assertThat(judgment.sleepLevel()).isEqualTo(LifestyleFactorLevel.POOR); // 7.0 -> 5.0, diff=-2.0 <= -1.0
		assertThat(judgment.stressLevel()).isEqualTo(LifestyleFactorLevel.POOR); // 2 -> 4, diff=2.0 >= 1.0
		assertThat(judgment.waterLevel()).isEqualTo(LifestyleFactorLevel.POOR); // 100% -> 40%, diff=-0.6 <= -0.2

		// 개인 기준선(PERSONAL_AVERAGE) 모드에서는 baseline_value가 최근 7일 개인 평균이다.
		assertThat(judgment.sleep().baselineType()).isEqualTo(BaselineType.PERSONAL_AVERAGE);
		assertThat(judgment.sleep().baselineValue()).isEqualTo(7.0);
		assertThat(judgment.sleep().difference()).isEqualTo(-2.0);
		assertThat(judgment.stress().baselineType()).isEqualTo(BaselineType.PERSONAL_AVERAGE);
		assertThat(judgment.stress().baselineValue()).isEqualTo(2.0);
		assertThat(judgment.stress().difference()).isEqualTo(2.0);
		// 수분은 판정 자체는 비율 기준이지만, baseline_value/difference는 current_value(ml)와 같은 단위로 환산해서 담는다.
		assertThat(judgment.water().baselineType()).isEqualTo(BaselineType.PERSONAL_AVERAGE);
		assertThat(judgment.water().baselineValue()).isEqualTo(2000.0); // 개인 평균 섭취량(ml)
		assertThat(judgment.water().difference()).isEqualTo(-1200.0); // 800 - 2000
	}

	@Test
	void 개인_기준선_사용시_평균과_비슷하면_GOOD으로_판정한다() {
		Checkin latest = checkin(7.2, 2, 2000); // 평균(7h/2/2000ml)과 비슷하거나 더 좋음
		List<Checkin> previous = sevenDaysOf(7.0, 2, WATER_GOAL_ML);

		LifestyleFactorRubric.Judgment judgment = rubric.judge(latest, previous, WATER_GOAL_ML);

		assertThat(judgment.personalBaselineUsed()).isTrue();
		assertThat(judgment.sleepLevel()).isEqualTo(LifestyleFactorLevel.GOOD);
		assertThat(judgment.stressLevel()).isEqualTo(LifestyleFactorLevel.GOOD);
		assertThat(judgment.waterLevel()).isEqualTo(LifestyleFactorLevel.GOOD);
	}

	@Test
	void 목표_음수량_대비_비율로_판정하며_절대_섭취량만으로_판단하지_않는다() {
		// 목표가 낮으면(1000ml) 같은 800ml도 목표 대비 80%로 GOOD, 목표가 높으면(2500ml) 32%로 POOR
		Checkin latest = checkin(7.5, 2, 800);
		List<Checkin> noHistory = List.of();

		LifestyleFactorRubric.Judgment lowGoal = rubric.judge(latest, noHistory, 1000);
		LifestyleFactorRubric.Judgment highGoal = rubric.judge(latest, noHistory, 2500);

		assertThat(lowGoal.waterLevel()).isEqualTo(LifestyleFactorLevel.GOOD);
		assertThat(highGoal.waterLevel()).isEqualTo(LifestyleFactorLevel.POOR);
	}

	@Test
	void waterGoalMl이_null이면_기본_목표치로_폴백한다() {
		Checkin latest = checkin(7.5, 2, 1700); // 기본 목표 2000ml 대비 85% -> GOOD
		List<Checkin> noHistory = List.of();

		LifestyleFactorRubric.Judgment judgment = rubric.judge(latest, noHistory, null);

		assertThat(judgment.waterLevel()).isEqualTo(LifestyleFactorLevel.GOOD);
	}

	@Test
	void 체크인_이력이_전혀_없어도_고정_기준표로_정상_판정한다() {
		Checkin latest = checkin(8.0, 1, 1900); // 데이터 부족 fallback: 신규 사용자도 실패하지 않아야 한다

		LifestyleFactorRubric.Judgment judgment = rubric.judge(latest, List.of(), WATER_GOAL_ML);

		assertThat(judgment.personalBaselineUsed()).isFalse();
		assertThat(judgment.sleepLevel()).isEqualTo(LifestyleFactorLevel.GOOD);
		assertThat(judgment.stressLevel()).isEqualTo(LifestyleFactorLevel.GOOD);
		assertThat(judgment.waterLevel()).isEqualTo(LifestyleFactorLevel.GOOD);
	}

	private List<Checkin> sevenDaysOf(double sleepHours, int stressLevel, int waterIntakeMl) {
		List<Checkin> checkins = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			checkins.add(checkin(sleepHours, stressLevel, waterIntakeMl));
		}
		return checkins;
	}

	private Checkin checkin(double sleepHours, int stressLevel, int waterIntakeMl) {
		return new Checkin(USER_ID, sleepHours, stressLevel, waterIntakeMl, LocalDate.of(2026, 8, 10));
	}
}
