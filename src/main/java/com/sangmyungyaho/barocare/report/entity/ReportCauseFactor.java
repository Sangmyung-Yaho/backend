package com.sangmyungyaho.barocare.report.entity;

/**
 * 피부 변화 원인 리포트(REP-101)에서 원인 후보로 지목될 수 있는 생활습관 요인.
 * {@link com.sangmyungyaho.barocare.checkin.entity.Checkin}에 실제로 존재하는 필드(수면/스트레스/수분 섭취)로만
 * 제한한다 - AI가 이 목록 밖의 임의 요인을 만들어내지 못하도록 구조화 출력 스키마 자체를 이 enum으로 제약한다.
 */
public enum ReportCauseFactor {
	SLEEP,
	STRESS,
	WATER_INTAKE
}
