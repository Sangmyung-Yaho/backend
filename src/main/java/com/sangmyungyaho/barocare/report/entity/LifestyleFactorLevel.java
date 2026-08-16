package com.sangmyungyaho.barocare.report.entity;

/**
 * 생활습관 요인(수면/스트레스/수분 섭취) 판정 등급.
 * {@link com.sangmyungyaho.barocare.report.service.LifestyleFactorRubric}이 계산하며,
 * GPT는 이 값을 다시 계산하지 않고 그대로 받아 원인 해석에만 사용한다.
 */
public enum LifestyleFactorLevel {
	GOOD,
	MODERATE,
	POOR
}
