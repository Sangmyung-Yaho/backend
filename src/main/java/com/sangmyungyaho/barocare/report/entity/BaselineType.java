package com.sangmyungyaho.barocare.report.entity;

/**
 * 생활습관 원인 근거(ReportDto.PrimaryCause.baseline_value)의 기준 종류.
 *
 * LifestyleFactorRubric이 개인 기준선(최근 7일 평균)을 쓸 수 있으면 PERSONAL_AVERAGE,
 * 체크인 이력이 부족해 고정 기준표(권장값)를 쓰면 RECOMMENDED를 사용한다. 두 모드 중 어느 것이
 * 쓰였는지 프론트가 명확히 구분할 수 있도록 Judgment.personalBaselineUsed()와 동일한 기준으로 결정된다.
 */
public enum BaselineType {
	PERSONAL_AVERAGE,
	RECOMMENDED
}
