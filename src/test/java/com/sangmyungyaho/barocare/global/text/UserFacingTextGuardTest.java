package com.sangmyungyaho.barocare.global.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UserFacingTextGuardTest {

	// 실제 서비스에서 발견된 문제 문장 - 이 케이스 하나라도 회귀하면 테스트가 실패해야 한다.
	private static final String LEAKED_SENTENCE =
			"붉은기와 트러블은 모두 1 감소해 IMPROVED 및 DECREASED 상태이며, 주요 위험 요인 후보로 POOR 판정을 받은 스트레스와 수분 섭취가 확인됩니다.";

	@Test
	void containsLeak_실제_발견된_문장에서_true를_반환한다() {
		assertThat(UserFacingTextGuard.containsLeak(LEAKED_SENTENCE)).isTrue();
	}

	@Test
	void sanitize_실제_발견된_문장의_내부_enum을_모두_자연어로_치환한다() {
		String sanitized = UserFacingTextGuard.sanitize(LEAKED_SENTENCE);

		assertThat(sanitized)
				.isEqualTo("붉은기와 트러블은 모두 1 감소해 개선됨 및 감소함 상태이며, 주요 위험 요인 후보로 부족 판정을 받은 스트레스와 수분 섭취가 확인됩니다.");
		assertThat(UserFacingTextGuard.containsLeak(sanitized)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"IMPROVED", "WORSENED", "UNCHANGED",
			"INCREASED", "DECREASED", "STABLE",
			"GOOD", "MODERATE", "POOR",
			"SAFE", "CAUTION", "DANGER",
			"MILD", "SEVERE",
			"FEW", "MANY",
			"FOREHEAD", "CHEEK_LEFT", "CHEEK_RIGHT", "NOSE", "CHIN",
			"SLEEP", "STRESS", "WATER_INTAKE"
	})
	void 모든_매핑_토큰이_문장에_섞이면_감지되고_한국어로_치환된다(String token) {
		String text = "테스트 문장입니다 " + token + " 그리고 이어지는 조사가 붙습니다.";

		assertThat(UserFacingTextGuard.containsLeak(text)).isTrue();

		String sanitized = UserFacingTextGuard.sanitize(text);
		assertThat(UserFacingTextGuard.containsLeak(sanitized)).isFalse();
		assertThat(sanitized).doesNotContain(token);
	}

	@Test
	void 조사가_바로_붙어도_감지된다() {
		// JDK17에서 \b는 영문-한글 경계를 인식하지 못하므로, 조사가 바로 붙는 케이스가 회귀의 핵심 시나리오다.
		assertThat(UserFacingTextGuard.containsLeak("POOR이라 판단됩니다")).isTrue();
		assertThat(UserFacingTextGuard.sanitize("POOR이라 판단됩니다")).isEqualTo("부족이라 판단됩니다");
	}

	@Test
	void 안전한_문장은_변경되지_않는다() {
		String clean = "최근 트러블 증가는 수면 부족과 높은 스트레스의 영향을 받았을 가능성이 있어요.";

		assertThat(UserFacingTextGuard.containsLeak(clean)).isFalse();
		assertThat(UserFacingTextGuard.sanitize(clean)).isEqualTo(clean);
	}

	@Test
	void null_입력은_그대로_null을_반환하고_leak_아님으로_처리한다() {
		assertThat(UserFacingTextGuard.containsLeak(null)).isFalse();
		assertThat(UserFacingTextGuard.sanitize(null)).isNull();
	}

	@Test
	void 구조화된_상태값_필드에_쓰이는_단어라도_일반_영단어_일부로만_존재하면_오탐하지_않는다() {
		// "GOOD"이 "GOODNESS" 처럼 더 긴 영단어의 일부일 때는 leak으로 취급하면 안 된다.
		assertThat(UserFacingTextGuard.containsLeak("GOODNESS는 매칭되면 안 됩니다")).isFalse();
	}
}
