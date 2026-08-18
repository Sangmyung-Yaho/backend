package com.sangmyungyaho.barocare.skin.entity;

/**
 * 이미지 품질 평가(조명/블러/각도/얼굴 비율 등)에 사용하는 등급.
 * 숫자 임계값 대신 GOOD/POOR 2단계로만 판정한다.
 */
public enum ImageQualityRating {
	GOOD,
	POOR
}
