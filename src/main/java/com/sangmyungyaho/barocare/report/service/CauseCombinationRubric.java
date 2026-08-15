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
 * 고위험 조합 경고(ISSUE-27, GET /api/v1/reports/causes/latest/warnings) 및
 * 요인 상호작용 설명(ISSUE-28, GET /api/v1/reports/causes/latest/interactions) 판정 규칙.
 *
 * 최신 원인 리포트(ReportService#getLatestSkinReport)가 이미 만들어둔 primaryCauses의
 * factor 조합만으로 판정한다 - 체크인 원본 수치를 다시 조회/계산하지 않는다. SkinGradeRubric이
 * SkinAnalysis 관찰값을 기준으로 등급을 매기는 것과 같은 방식으로, 이미 계산된 입력을 규칙에
 * 대입할 뿐이다.
 *
 * 규칙은 요인 개수가 많은 것부터 검사한다: 3요인(SLEEP+STRESS+WATER_INTAKE)이 모두 present면
 * 그 규칙 하나만 매칭시키고, 이미 매칭된 규칙의 부분집합인 더 작은 규칙(2요인 조합들)은 건너뛰어
 * 중복 경고가 나오지 않게 한다. 이 매칭 로직(matchedRules)은 경고(evaluate)와 상호작용 설명
 * (interactions) 양쪽에서 공유한다 - 요인이 SLEEP/STRESS/WATER_INTAKE 3개뿐이라 "2개 이상 요인이
 * 함께 관찰된 모든 경우의 수"가 RULES 4개와 정확히 일치하기 때문이다.
 */
@Component
public class CauseCombinationRubric {

	/**
	 * @param message            경고 카드 문구(#27). "함께 확인됐어요" 수준의 사실 서술 톤.
	 * @param interactionMessage 상호작용 설명 카드 문구(#28). 의료적 인과관계로 단정하지 않고
	 *                           "함께 관찰되었다"/"영향을 주었을 가능성이 있다" 톤을 사용한다.
	 */
	private record CombinationRule(Set<ReportCauseFactor> factors, String title, String message, String interactionMessage) {
	}

	// 요인 개수 내림차순으로 나열한다 - matchedRules()가 이 순서 그대로 검사해야
	// 3요인 규칙이 2요인 규칙보다 먼저 매칭되고, 뒤따르는 부분집합 규칙을 스킵할 수 있다.
	private static final List<CombinationRule> RULES = List.of(
			new CombinationRule(
					EnumSet.of(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE),
					"고위험 조합 감지",
					"수면 부족, 높은 스트레스, 수분 섭취 부족이 함께 확인됐어요.",
					"수면 부족, 높은 스트레스, 수분 섭취 부족이 함께 관찰되었어요. "
							+ "세 요인이 피부 컨디션 변화와 함께 나타났을 가능성이 있어요."
			),
			new CombinationRule(
					EnumSet.of(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS),
					"고위험 조합 감지",
					"수면 부족과 높은 스트레스가 함께 확인됐어요.",
					"수면 부족과 높은 스트레스가 함께 관찰되었어요. "
							+ "두 요인이 피부 컨디션 변화와 함께 나타났을 가능성이 있어요."
			),
			new CombinationRule(
					EnumSet.of(ReportCauseFactor.SLEEP, ReportCauseFactor.WATER_INTAKE),
					"고위험 조합 감지",
					"수면 부족과 수분 섭취 부족이 함께 확인됐어요.",
					"수면 부족과 수분 섭취 부족이 함께 관찰되었어요. "
							+ "두 요인이 피부 컨디션 변화와 함께 나타났을 가능성이 있어요."
			),
			new CombinationRule(
					EnumSet.of(ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE),
					"고위험 조합 감지",
					"높은 스트레스와 수분 섭취 부족이 함께 확인됐어요.",
					"높은 스트레스와 수분 섭취 부족이 함께 관찰되었어요. "
							+ "두 요인이 피부 컨디션 변화와 함께 나타났을 가능성이 있어요."
			)
	);

	public List<ReportDto.Warning> evaluate(List<ReportDto.PrimaryCause> primaryCauses) {
		return matchedRules(extractFactors(primaryCauses)).stream()
				.map(rule -> new ReportDto.Warning(WarningLevel.HIGH, List.copyOf(rule.factors()), rule.title(), rule.message()))
				.toList();
	}

	/**
	 * 요인 상호작용 설명(ISSUE-28). evaluate()와 동일한 조합 매칭 규칙을 재사용하되,
	 * WarningLevel/경고 문구 대신 상호작용 전용 문구(interactionMessage)로 응답을 구성한다.
	 */
	public List<ReportDto.Interaction> interactions(List<ReportDto.PrimaryCause> primaryCauses) {
		return matchedRules(extractFactors(primaryCauses)).stream()
				.map(rule -> new ReportDto.Interaction(List.copyOf(rule.factors()), rule.interactionMessage()))
				.toList();
	}

	private Set<ReportCauseFactor> extractFactors(List<ReportDto.PrimaryCause> primaryCauses) {
		return primaryCauses.stream()
				.map(ReportDto.PrimaryCause::factor)
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(ReportCauseFactor.class)));
	}

	private List<CombinationRule> matchedRules(Set<ReportCauseFactor> presentFactors) {
		List<CombinationRule> matched = new ArrayList<>();
		List<Set<ReportCauseFactor>> coveredFactorSets = new ArrayList<>();

		for (CombinationRule rule : RULES) {
			if (!presentFactors.containsAll(rule.factors())) {
				continue;
			}
			// 이미 매칭된(더 큰) 규칙의 부분집합이면 중복이므로 건너뛴다.
			boolean alreadyCovered = coveredFactorSets.stream().anyMatch(covered -> covered.containsAll(rule.factors()));
			if (alreadyCovered) {
				continue;
			}
			matched.add(rule);
			coveredFactorSets.add(rule.factors());
		}
		return matched;
	}
}
