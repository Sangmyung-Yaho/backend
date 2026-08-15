package com.sangmyungyaho.barocare.report.entity;

/**
 * 고위험 조합 경고(ISSUE-27, GET /api/v1/reports/causes/latest/warnings)의 위험도.
 *
 * 현재는 고위험 조합만 다루므로 HIGH 한 값만 존재한다. 추후 다른 등급의 경고가 필요해지면
 * 값을 추가하면 된다(ReportChangeStatus/SkinAnalysisLevel과 동일한 스타일).
 */
public enum WarningLevel {
	HIGH
}
