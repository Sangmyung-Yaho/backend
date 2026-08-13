package com.sangmyungyaho.barocare.report.entity;

/**
 * 피부 변화 원인 리포트(REP-101)에서 이전 대비 현재 지표의 변화 상태.
 * SkinAnalysisLevel ordinal 점수의 변화량(change)만으로 결정한다: change<0 -> IMPROVED,
 * change>0 -> WORSENED, change==0 -> UNCHANGED. AI의 이미지 비교 판단({@link com.sangmyungyaho.barocare.skin.entity.ChangeDirection})은
 * 사용하지 않는다 - score와 status가 서로 다른 기준이 되어 모순되지 않도록 하기 위함이다.
 */
public enum ReportChangeStatus {
	IMPROVED,
	WORSENED,
	UNCHANGED
}
