package com.sangmyungyaho.barocare.report.service;

import com.sangmyungyaho.barocare.report.dto.ReportDto;
import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.report.entity.WarningLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 고위험 조합 경고(GET /api/v1/reports/causes/latest/warnings) 및
 * 요인 상호작용 설명(GET /api/v1/reports/causes/latest/interactions) 판정 규칙.
 *
 * 최신 원인 리포트(ReportService#getLatestSkinReport)가 이미 만들어둔 primaryCauses의
 * factor 조합만으로 판정한다 - 체크인 원본 수치를 다시 조회/계산하지 않는다.
 *
 * RiskTier로 "고위험(HIGH_RISK)"과 "일반 상호작용(INTERACTION)"을 완전히 분리한다: 3요인
 * (SLEEP+STRESS+WATER_INTAKE)이 모두 present면 HIGH_RISK 규칙 하나만 matchedRules()에 남고(기존
 * 부분집합 스킵 로직 덕분에 2요인 규칙들은 자동으로 걸러진다), 그 결과는 evaluate()(경고)에만 노출되고
 * interactions()에는 노출되지 않는다. 반대로 2요인 조합은 전부 INTERACTION 규칙이라 interactions()에만
 * 노출되고 경고로는 절대 뜨지 않는다 - 같은 조합이 두 응답에 중복 노출되는 일이 없도록 이 tier 분기가
 * 유일한 판단 기준이다.
 */
@Component
public class CauseCombinationRubric {

	private enum RiskTier {
		HIGH_RISK,
		INTERACTION
	}

	/**
	 * @param headline 행동을 유도하는 헤드라인 카피(예: "오늘은 몸을 쉬게 해주세요."). HIGH_RISK 규칙에만
	 *                 쓰이고(경고 카드용), INTERACTION 규칙은 null이다.
	 * @param message  카드 본문. evaluate()/interactions()가 서로 겹치지 않는 규칙 집합만 소비하므로
	 *                 (한 규칙은 항상 HIGH_RISK 아니면 INTERACTION 중 하나), 규칙당 문구를 하나만 두면 된다.
	 */
	private record CombinationRule(Set<ReportCauseFactor> factors, RiskTier tier, String title, String headline, String message) {
	}

	// 요인 개수 내림차순으로 나열한다 - matchedRules()가 이 순서 그대로 검사해야
	// 3요인 규칙이 2요인 규칙보다 먼저 매칭되고, 뒤따르는 부분집합 규칙을 스킵할 수 있다.
	private static final List<CombinationRule> RULES = List.of(
			new CombinationRule(
					EnumSet.of(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE),
					RiskTier.HIGH_RISK,
					"고위험 조합 감지",
					"오늘은 몸을 쉬게 해주세요.",
					"수면 부족, 높은 스트레스, 수분 섭취 부족이 함께 확인됐어요. 세 요인이 동시에 나타나 피부에 부담이 될 수 있어요."
			),
			new CombinationRule(
					EnumSet.of(ReportCauseFactor.SLEEP, ReportCauseFactor.STRESS),
					RiskTier.INTERACTION, null, null,
					"수면 부족과 높은 스트레스가 함께 관찰되었어요. 두 요인이 피부 컨디션 변화와 함께 나타났을 가능성이 있어요."
			),
			new CombinationRule(
					EnumSet.of(ReportCauseFactor.SLEEP, ReportCauseFactor.WATER_INTAKE),
					RiskTier.INTERACTION, null, null,
					"수면 부족과 수분 섭취 부족이 함께 관찰되었어요. 두 요인이 피부 컨디션 변화와 함께 나타났을 가능성이 있어요."
			),
			new CombinationRule(
					EnumSet.of(ReportCauseFactor.STRESS, ReportCauseFactor.WATER_INTAKE),
					RiskTier.INTERACTION, null, null,
					"높은 스트레스와 수분 섭취 부족이 함께 관찰되었어요. 두 요인이 피부 컨디션 변화와 함께 나타났을 가능성이 있어요."
			)
	);

	public List<ReportDto.Warning> evaluate(List<ReportDto.PrimaryCause> primaryCauses) {
		Map<ReportCauseFactor, ReportDto.PrimaryCause> byFactor = indexByFactor(primaryCauses);
		return matchedRules(extractFactors(primaryCauses)).stream()
				.filter(rule -> rule.tier() == RiskTier.HIGH_RISK)
				.map(rule -> new ReportDto.Warning(
						WarningLevel.HIGH,
						List.copyOf(rule.factors()),
						toFactorValues(rule.factors(), byFactor),
						rule.title(),
						rule.headline(),
						rule.message()
				))
				.toList();
	}

	/**
	 * 요인 상호작용 설명. evaluate()와 같은 조합 매칭 규칙을 재사용하되, INTERACTION tier(2요인 조합)만
	 * 반환한다 - HIGH_RISK(3요인)는 경고로만 노출되고 여기엔 절대 중복 노출되지 않는다.
	 */
	public List<ReportDto.Interaction> interactions(List<ReportDto.PrimaryCause> primaryCauses) {
		return matchedRules(extractFactors(primaryCauses)).stream()
				.filter(rule -> rule.tier() == RiskTier.INTERACTION)
				.map(rule -> new ReportDto.Interaction(List.copyOf(rule.factors()), rule.message()))
				.toList();
	}

	private Map<ReportCauseFactor, ReportDto.PrimaryCause> indexByFactor(List<ReportDto.PrimaryCause> primaryCauses) {
		return primaryCauses.stream()
				.collect(Collectors.toMap(ReportDto.PrimaryCause::factor, Function.identity(), (a, b) -> a));
	}

	// 경고에 실릴 요인별 실제 체크인 값(감지 근거)을 구성한다. primaryCauses는 이미 이 리포트가 계산해둔
	// 최신 체크인 실측값(current_value/unit)을 갖고 있으므로 새로 조회하지 않고 그대로 재사용한다.
	private List<ReportDto.WarningFactorValue> toFactorValues(
			Set<ReportCauseFactor> factors, Map<ReportCauseFactor, ReportDto.PrimaryCause> byFactor
	) {
		return factors.stream()
				.map(byFactor::get)
				.filter(Objects::nonNull)
				.map(cause -> new ReportDto.WarningFactorValue(cause.factor(), cause.currentValue(), cause.unit()))
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
