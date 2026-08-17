package com.sangmyungyaho.barocare.report.service;

import com.sangmyungyaho.barocare.report.dto.ReportDto;
import com.sangmyungyaho.barocare.report.entity.BaselineType;
import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.report.entity.WarningLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * CauseCombinationRubric 단위 테스트.
 *
 * RiskTier(HIGH_RISK/INTERACTION)로 "고위험 경고"와 "일반 상호작용"을 완전히 분리한다: 2요인 조합은
 * 항상 상호작용으로만, 3요인(수면+스트레스+수분)이 모두 있을 때만 고위험 경고로 노출되고, 같은 조합이
 * 두 응답에 동시에 뜨는 일이 없다.
 */
class CauseCombinationRubricTest {

	private final CauseCombinationRubric rubric = new CauseCombinationRubric();

	@Test
	void 매칭되는_조합이_없으면_경고와_상호작용_모두_빈_리스트를_반환한다() {
		List<ReportDto.PrimaryCause> causes = causesOf(ReportCauseFactor.WATER_INTAKE);

		assertThat(rubric.evaluate(causes)).isEmpty();
		assertThat(rubric.interactions(causes)).isEmpty();
	}

	@Test
	void 원인_후보가_하나도_없으면_경고와_상호작용_모두_빈_리스트를_반환한다() {
		assertThat(rubric.evaluate(List.of())).isEmpty();
		assertThat(rubric.interactions(List.of())).isEmpty();
	}

	@Test
	void SLEEP과_STRESS_두_요인만_있으면_경고는_비고_상호작용만_반환한다() {
		List<ReportDto.PrimaryCause> causes = causesOf(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS);

		assertThat(rubric.evaluate(causes)).isEmpty();

		List<ReportDto.Interaction> interactions = rubric.interactions(causes);
		assertThat(interactions).hasSize(1);
		assertThat(interactions.get(0).factors()).containsExactly(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS);
		assertThat(interactions.get(0).message())
				.contains("함께 관찰되었어요")
				.contains("가능성이 있어요")
				.doesNotContain("때문에");
	}

	@Test
	void SLEEP과_WATER_INTAKE_두_요인만_있으면_경고는_비고_상호작용만_반환한다() {
		List<ReportDto.PrimaryCause> causes = causesOf(ReportCauseFactor.SLEEP, ReportCauseFactor.WATER_INTAKE);

		assertThat(rubric.evaluate(causes)).isEmpty();
		assertThat(rubric.interactions(causes).get(0).factors())
				.containsExactly(ReportCauseFactor.SLEEP, ReportCauseFactor.WATER_INTAKE);
	}

	@Test
	void STRESS와_WATER_INTAKE_두_요인만_있으면_경고는_비고_상호작용만_반환한다() {
		List<ReportDto.PrimaryCause> causes = causesOf(ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE);

		assertThat(rubric.evaluate(causes)).isEmpty();
		assertThat(rubric.interactions(causes).get(0).factors())
				.containsExactly(ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE);
	}

	@Test
	void 세_요인이_모두_있으면_고위험_경고_하나만_반환하고_상호작용에는_노출되지_않는다() {
		// 3요인 규칙이 매칭되면 그 부분집합(2요인 규칙들)은 matchedRules()에서 이미 스킵되므로,
		// 같은 조합이 경고와 상호작용에 동시에 뜨는 일이 없다.
		List<ReportDto.PrimaryCause> causes = causesOf(
				ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE);

		List<ReportDto.Warning> warnings = rubric.evaluate(causes);
		assertThat(warnings).hasSize(1);
		ReportDto.Warning warning = warnings.get(0);
		assertThat(warning.level()).isEqualTo(WarningLevel.HIGH);
		assertThat(warning.factors())
				.containsExactly(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE);
		assertThat(warning.title()).isEqualTo("고위험 조합 감지");
		assertThat(warning.headline()).isEqualTo("오늘은 몸을 쉬게 해주세요.");

		assertThat(rubric.interactions(causes)).isEmpty();
	}

	@Test
	void 고위험_경고에는_요인별_실제_체크인_값이_구조화되어_포함된다() {
		// factor_values는 primaryCauses의 current_value/unit을 새로 계산하지 않고 그대로 재사용한다.
		List<ReportDto.PrimaryCause> causes = List.of(
				new ReportDto.PrimaryCause(ReportCauseFactor.SLEEP, "수면 부족", 2.8, "시간", "설명",
						7.0, -4.2, BaselineType.RECOMMENDED),
				new ReportDto.PrimaryCause(ReportCauseFactor.STRESS, "높은 스트레스", 5.0, "5단계", "설명",
						3.0, 2.0, BaselineType.RECOMMENDED),
				new ReportDto.PrimaryCause(ReportCauseFactor.WATER_INTAKE, "수분 부족", 400.0, "ml", "설명",
						2000.0, -1600.0, BaselineType.RECOMMENDED)
		);

		ReportDto.Warning warning = rubric.evaluate(causes).get(0);

		assertThat(warning.factorValues())
				.extracting(ReportDto.WarningFactorValue::factor, ReportDto.WarningFactorValue::currentValue, ReportDto.WarningFactorValue::unit)
				.containsExactlyInAnyOrder(
						tuple(ReportCauseFactor.SLEEP, 2.8, "시간"),
						tuple(ReportCauseFactor.STRESS, 5.0, "5단계"),
						tuple(ReportCauseFactor.WATER_INTAKE, 400.0, "ml")
				);
	}

	private List<ReportDto.PrimaryCause> causesOf(ReportCauseFactor... factors) {
		return List.of(factors).stream()
				.map(factor -> new ReportDto.PrimaryCause(factor, factor.name(), 1.0, "unit", "설명", null, null, null))
				.toList();
	}
}
