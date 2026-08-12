package com.sangmyungyaho.barocare.skin.entity;

/**
 * 이전 사진 대비 현재 사진의 변화 방향. SkinAnalysisLevel(SAFE/CAUTION/DANGER)과는 별개 개념이다.
 */
public enum ChangeDirection {
	INCREASED,
	STABLE,
	DECREASED
}
