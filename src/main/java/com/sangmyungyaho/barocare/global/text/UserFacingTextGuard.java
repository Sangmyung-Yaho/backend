package com.sangmyungyaho.barocare.global.text;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 사용자에게 노출되는 자연어 문장(리포트 요약/원인 설명, 추천 성분·제품 이유, 루틴 문구 등)에 내부
 * enum/상태값 리터럴(IMPROVED, POOR, SAFE 등)이 그대로 섞여 나가지 않도록 하는 공통 방어 로직.
 * Report/추천 성분/추천 제품 등 여러 기능이 이 클래스 하나를 공유한다 - 예전에는
 * {@code ReportService}와 {@code IngredientRecommendationService}가 각자 똑같은 정규식을
 * 복사해 갖고 있었고(추천 제품 쪽은 아예 이 검사 자체가 빠져 있었다), 그 결과 한쪽만 고치고 다른 쪽은
 * 놓치는 식의 매핑 누락이 반복됐다.
 *
 * 두 가지 쓰임새가 있다.
 * <ul>
 *   <li>{@link #containsLeak(String)} - "생성 직후" 방어선(1차). LLM이 프롬프트 지침을 어기고 내부
 *       상태값을 그대로 반환했는지 검사해서, 발견되면 저장하지 않고 실패로 처리하는 용도. AI 응답을
 *       그대로 저장하기 직전에 호출한다.</li>
 *   <li>{@link #sanitize(String)} - "조회 응답 직전" 방어선(2차, 최종). 이미 저장된 문자열(1차 검증이
 *       아직 없던 시절 저장된 레거시 데이터, 또는 예상치 못하게 1차 검증을 통과한 케이스 포함)을 API로
 *       내려주기 직전에 한 번 더 훑어서, 내부 상태값이 남아 있으면 자연어 한국어로 치환한다. DB
 *       마이그레이션 없이도 과거 데이터가 항상 안전하게 노출되도록 하는 게 목적이다.</li>
 * </ul>
 *
 * 주의: {@code "status": "COMPLETED"}처럼 프론트 로직이 그대로 쓰는 명시적인 상태 필드 자체는 이
 * 클래스를 거치면 안 된다 - 여기서 다루는 대상은 항상 자연어 문장(요약/설명/이유 등) 필드뿐이다.
 * 호출하는 쪽이 "이 필드는 자연어 문장"이라고 판단한 곳에만 명시적으로 적용해야 하며, DTO의 구조화된
 * enum 필드에는 절대 호출하지 않는다.
 */
public final class UserFacingTextGuard {

	// 사용자 노출 문장에 절대 그대로 나가면 안 되는 내부 enum 상수 -> 자연어 한국어 매핑.
	// 이 프로젝트에 존재하는 전체 enum(2026-08 기준, 소스 전수 검색으로 확인)을 검토해, AI 프롬프트에
	// 노출되거나 자연어 문장 조립에 관여할 수 있는 값만 포함했다. "status": "COMPLETED" 같은 구조화
	// 필드 전용 enum(RecommendationStatus/BaselineType/WarningLevel/ErrorCode/UserStatus/Provider/
	// SkinType 등)은 자연어 문장에 섞일 이유가 없어 일부러 제외했다 - 포함시키면 그 필드들의 정상적인
	// 값(예: 어떤 텍스트에 우연히 "GOOD" 같은 단어가 필요한 경우)까지 잘못 건드릴 위험만 늘어난다.
	private static final Map<String, String> TOKEN_TO_KOREAN = new LinkedHashMap<>();

	static {
		// ReportChangeStatus
		TOKEN_TO_KOREAN.put("IMPROVED", "개선됨");
		TOKEN_TO_KOREAN.put("WORSENED", "악화됨");
		TOKEN_TO_KOREAN.put("UNCHANGED", "유지됨");
		// ChangeDirection
		TOKEN_TO_KOREAN.put("INCREASED", "증가함");
		TOKEN_TO_KOREAN.put("DECREASED", "감소함");
		TOKEN_TO_KOREAN.put("STABLE", "유지됨");
		// LifestyleFactorLevel / ImageQualityRating 공용
		TOKEN_TO_KOREAN.put("GOOD", "양호");
		TOKEN_TO_KOREAN.put("MODERATE", "보통");
		TOKEN_TO_KOREAN.put("POOR", "부족");
		// SkinAnalysisLevel
		TOKEN_TO_KOREAN.put("SAFE", "안전");
		TOKEN_TO_KOREAN.put("CAUTION", "주의");
		TOKEN_TO_KOREAN.put("DANGER", "위험");
		// RednessIntensity(MODERATE는 위에서 이미 등록됨)
		TOKEN_TO_KOREAN.put("MILD", "옅음");
		TOKEN_TO_KOREAN.put("SEVERE", "심함");
		// TroubleDensity
		TOKEN_TO_KOREAN.put("FEW", "적음");
		TOKEN_TO_KOREAN.put("MANY", "많음");
		// FaceRegion
		TOKEN_TO_KOREAN.put("FOREHEAD", "이마");
		TOKEN_TO_KOREAN.put("CHEEK_LEFT", "왼쪽 뺨");
		TOKEN_TO_KOREAN.put("CHEEK_RIGHT", "오른쪽 뺨");
		TOKEN_TO_KOREAN.put("NOSE", "코");
		TOKEN_TO_KOREAN.put("CHIN", "턱");
		// ReportCauseFactor
		TOKEN_TO_KOREAN.put("SLEEP", "수면");
		TOKEN_TO_KOREAN.put("STRESS", "스트레스");
		TOKEN_TO_KOREAN.put("WATER_INTAKE", "수분 섭취");
	}

	// \b가 아니라 부정 전후방탐색 (?<![A-Za-z])...(?![A-Za-z])을 쓰는 이유: 이 프로젝트가 쓰는 JDK 17에서
	// \b는 영문 단어와 그 바로 뒤에 붙은 한글 사이를 "경계"로 인식하지 못한다(실측으로 확인된 사실 -
	// ReportService/IngredientRecommendationService의 기존 코드에도 같은 이유가 적혀 있었다).
	// "POOR이라"처럼 조사가 바로 붙는 게 흔한 한국어 문장에서 \b를 쓰면 leak을 놓친다. 토큰을 길이 내림차순으로
	// 정렬해 얹는 이유는 "WATER_INTAKE" 같은 복합 토큰이 혹시라도 더 짧은 토큰에 가려 부분 매칭되는 일이
	// 없게 하기 위한 안전장치다(현재 토큰 목록엔 실제 겹침이 없지만, 매핑이 늘어나도 안전하게 유지된다).
	private static final Pattern LEAK_PATTERN = Pattern.compile(
			"(?<![A-Za-z])(" + TOKEN_TO_KOREAN.keySet().stream()
					.sorted(Comparator.comparingInt(String::length).reversed())
					.collect(Collectors.joining("|")) + ")(?![A-Za-z])",
			Pattern.CASE_INSENSITIVE
	);

	private UserFacingTextGuard() {
	}

	/**
	 * 이 문자열에 내부 enum 상태값이 그대로 섞여 있으면 true. AI 응답을 저장하기 직전(1차 방어선)에 쓴다 -
	 * true면 호출부가 저장을 거부하고 실패로 처리해야 한다.
	 */
	public static boolean containsLeak(String text) {
		return text != null && LEAK_PATTERN.matcher(text).find();
	}

	/**
	 * 내부 enum 상태값이 남아 있으면 자연어 한국어로 치환한 문자열을 반환한다(원본은 바꾸지 않음).
	 * 이미 안전한 문자열이면 원본과 동일한 내용을 그대로 반환한다. null 입력은 null을 그대로 반환한다.
	 * 조회 응답 직전(2차/최종 방어선)에 쓴다 - 레거시 데이터를 포함해 절대 실패하지 않는 안전망이다.
	 */
	public static String sanitize(String text) {
		if (text == null) {
			return null;
		}
		Matcher matcher = LEAK_PATTERN.matcher(text);
		if (!matcher.find()) {
			return text;
		}
		matcher.reset();
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			String replacement = TOKEN_TO_KOREAN.get(matcher.group(1).toUpperCase(Locale.ROOT));
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement != null ? replacement : matcher.group(1)));
		}
		matcher.appendTail(result);
		return result.toString();
	}
}
