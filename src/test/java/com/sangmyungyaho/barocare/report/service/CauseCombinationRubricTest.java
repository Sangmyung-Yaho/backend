package com.sangmyungyaho.barocare.report.service;

import com.sangmyungyaho.barocare.report.dto.ReportDto;
import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.report.entity.WarningLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #27: CauseCombinationRubric 단위 테스트.
 * primaryCauses의 factor 조합에 따라 올바른 경고가 매칭되는지, 3요인이 모두 있을 때
 * 2요인 경고와 중복되지 않는지 검증한다.
 */
class CauseCombinationRubricTest {

	private final CauseCombinationRubric rubric = new CauseCombinationRubric();

	@Test
	void 매칭되는_조합이_없으면_빈_리스트를_반환한다() {
		List<ReportDto.Warning> warnings = rubric.evaluate(causesOf(ReportCauseFactor.WATER_INTAKE));

		assertThat(warnings).isEmpty();
	}

	@Test
	void 원인_후보가_하나도_없으면_빈_리스트를_반환한다() {
		List<ReportDto.Warning> warnings = rubric.evaluate(List.of());

		assertThat(warnings).isEmpty();
	}

	@Test
	void SLEEP과_STRESS가_있으면_해당_조합_경고를_반환한다() {
		List<ReportDto.Warning> warnings = rubric.evaluate(causesOf(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS));

		assertThat(warnings).hasSize(1);
		ReportDto.Warning warning = warnings.get(0);
		assertThat(warning.level()).isEqualTo(WarningLevel.HIGH);
		assertThat(warning.factors()).containsExactly(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS);
		assertThat(warning.title()).isEqualTo("고위험 조합 감지");
		assertThat(warning.message()).isEqualTo("수면 부족과 높은 스트레스가 함께 확인됐어요.");
	}

	@Test
	void SLEEP과_WATER_INTAKE가_있으면_해당_조합_경고를_반환한다() {
		List<ReportDto.Warning> warnings = rubric.evaluate(causesOf(ReportCauseFactor.SLEEP, ReportCauseFactor.WATER_INTAKE));

		assertThat(warnings).hasSize(1);
		assertThat(warnings.get(0).factors()).containsExactly(ReportCauseFactor.SLEEP, ReportCauseFactor.WATER_INTAKE);
	}

	@Test
	void STRESS와_WATER_INTAKE가_있으면_해당_조합_경고를_반환한다() {
		List<ReportDto.Warning> warnings = rubric.evaluate(causesOf(ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE));

		assertThat(warnings).hasSize(1);
		assertThat(warnings.get(0).factors()).containsExactly(ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE);
	}

	@Test
	void 세_요인이_모두_있으면_3요인_경고_하나만_반환하고_2요인_경고와_중복되지_않는다() {
		List<ReportDto.Warning> warnings = rubric.evaluate(
				causesOf(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE));

		assertThat(warnings).hasSize(1);
		ReportDto.Warning warning = warnings.get(0);
		assertThat(warning.factors())
				.containsExactly(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE);
	}

	@Test
	void 매칭되는_조합이_없으면_상호작용_설명도_빈_리스트를_반환한다() {
		List<ReportDto.Interaction> interactions = rubric.interactions(causesOf(ReportCauseFactor.WATER_INTAKE));

		assertThat(interactions).isEmpty();
	}

	@Test
	void SLEEP과_STRESS가_있으면_의료적_인과관계_표현_없이_상호작용_설명을_반환한다() {
		List<ReportDto.Interaction> interactions = rubric.interactions(causesOf(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS));

		assertThat(interactions).hasSize(1);
		ReportDto.Interaction interaction = interactions.get(0);
		assertThat(interaction.factors()).containsExactly(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS);
		assertThat(interaction.message()).isEqualTo(
				"수면 부족과 높은 스트레스가 함께 관찰되었어요. 두 요인이 피부 컨디션 변화와 함께 나타났을 가능성이 있어요.");
	}

	@Test
	void 세_요인이_모두_있으면_상호작용_설명도_3요인_설명_하나만_반환하고_2요인_설명과_중복되지_않는다() {
		List<ReportDto.Interaction> interactions = rubric.interactions(
				causesOf(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE));

		assertThat(interactions).hasSize(1);
		assertThat(interactions.get(0).factors())
				.containsExactly(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE);
	}

	private List<ReportDto.PrimaryCause> causesOf(ReportCauseFactor... factors) {
		return List.of(factors).stream()
				.map(factor -> new ReportDto.PrimaryCause(factor, factor.name(), 1.0, "unit", "설명"))
				.toList();
	}
}
