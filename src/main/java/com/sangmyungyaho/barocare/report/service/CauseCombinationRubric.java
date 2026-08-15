package com.sangmyungyaho.barocare.report.service;

import com.sangmyungyaho.barocare.report.dto.ReportDto;
import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.report.entity.WarningLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 고위험 조합 경고(ISSUE-27, GET /api/v1/reports/causes/latest/warnings) 판정 규칙.
 *
 * 최신 원인 리포트(ReportService#getLatestSkinReport)가 이미 만들어둔 primaryCauses의
 * factor 조합만으로 판정한다 - 체크인 원본 수치를 다시 조회/계산하지 않는다. SkinGradeRubric이
 * SkinAnalysis 관찰값을 기준으로 등급을 매기는 것과 같은 방식으로, 이미 계산된 입력을 규칙에
 * 대입할 뿐이다.
 *
 * 규칙은 요인 개수가 많은 것부터 검사한다: 3요인(SLEEP+STRESS+WATER_INTAKE)이 모두 present면
 * 그 규칙 하나만 매칭시키고, 이미 매칭된 규칙의 부분집합인 더 작은 규칙(2요인 조합들)은 건너뛰어
 * 중복 경고가 나오지 않게 한다.
 */
@Component
public class CauseCombinationRubric {

	private record CombinationRule(Set<ReportCauseFactor> factors, String title, String message) {
	}

	// 요인 개수 내림차순으로 나열한다 - evaluate()가 이 순서 그대로 검사해야
	// 3요인 규칙이 2요인 규칙보다 먼저 매칭되고, 뒤따르는 부분집합 규칙을 스킵할 수 있다.
	private static final List<CombinationRule> RULES = List.of(
			new CombinationRule(
					EnumSet.of(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE),
					"고위험 조합 감지",
					"수면 부족, 높은 스트레스, 수분 섭취 부족이 함께 확인됐어요."
			),
			new CombinationRule(
					EnumSet.of(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS),
					"고위험 조합 감지",
					"수면 부족과 높은 스트레스가 함께 확인됐어요."
			),
			new CombinationRule(
					EnumSet.of(ReportCauseFactor.SLEEP, ReportCauseFactor.WATER_INTAKE),
					"고위험 조합 감지",
					"수면 부족과 수분 섭취 부족이 함께 확인됐어요."
			),
			new CombinationRule(
					EnumSet.of(ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE),
					"고위험 조합 감지",
					"높은 스트레스와 수분 섭취 부족이 함께 확인됐어요."
			)
	);

	public List<ReportDto.Warning> evaluate(List<ReportDto.PrimaryCause> primaryCauses) {
		Set<ReportCauseFactor> presentFactors = primaryCauses.stream()
				.map(ReportDto.PrimaryCause::factor)
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(ReportCauseFactor.class)));

		List<ReportDto.Warning> warnings = new ArrayList<>();
		List<Set<ReportCauseFactor>> coveredFactorSets = new ArrayList<>();

		for (CombinationRule rule : RULES) {
			if (!presentFactors.containsAll(rule.factors())) {
				continue;
			}
			// 이미 매칭된(더 큰) 규칙의 부분집합이면 중복 경고이므로 건너뛴다.
			boolean alreadyCovered = coveredFactorSets.stream().anyMatch(covered -> covered.containsAll(rule.factors()));
			if (alreadyCovered) {
				continue;
			}
			warnings.add(new ReportDto.Warning(WarningLevel.HIGH, List.copyOf(rule.factors()), rule.title(), rule.message()));
			coveredFactorSets.add(rule.factors());
		}
		return warnings;
	}
}
