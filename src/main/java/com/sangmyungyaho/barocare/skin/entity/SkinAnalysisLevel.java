package com.sangmyungyaho.barocare.skin.entity;

/**
 * 피부 지표 등급.
 * 선언 순서가 곧 위험도 순서다(SAFE < CAUTION < DANGER). skinLevel 계산 시 ordinal 비교로 사용된다.
 */
public enum SkinAnalysisLevel {
	SAFE,
	CAUTION,
	DANGER
}
