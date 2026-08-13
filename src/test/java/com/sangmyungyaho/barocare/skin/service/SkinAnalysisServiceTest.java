package com.sangmyungyaho.barocare.skin.service;

import com.sangmyungyaho.barocare.skin.dto.SkinAnalysisDto;
import com.sangmyungyaho.barocare.skin.entity.ImageQualityRating;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.skin.entity.SkinImage;
import com.sangmyungyaho.barocare.skin.repository.SkinAnalysisRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Issue #20: 피부 분석 히스토리(getHistory) 단위 테스트.
 * 숫자 점수가 아니라 등급(SAFE/CAUTION/DANGER) 기준 latest/average(최빈값)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SkinAnalysisServiceTest {

	@Mock
	private SkinAnalysisRepository skinAnalysisRepository;

	@InjectMocks
	private SkinAnalysisService skinAnalysisService;

	@Test
	void 기록이_없으면_latest와_average는_null이고_history는_빈_배열이다() {
		when(skinAnalysisRepository.findByAnalyzedAtGreaterThanEqualOrderByAnalyzedAtAsc(any()))
				.thenReturn(List.of());

		SkinAnalysisDto.HistoryResponse response = skinAnalysisService.getHistory(28);

		assertThat(response.periodDays()).isEqualTo(28);
		assertThat(response.latest()).isNull();
		assertThat(response.average()).isNull();
		assertThat(response.history()).isEmpty();
	}

	@Test
	void latest는_가장_최근_분석이고_average는_최빈_등급이며_동률이면_더_위험한_등급을_우선한다() {
		// redness: CAUTION 1건, DANGER 1건 -> 동률이므로 더 위험한 DANGER가 대표값
		// trouble: SAFE 2건, CAUTION 1건 -> SAFE가 최빈값
		SkinAnalysis oldest = analysisAt(LocalDate.of(2026, 8, 1), SkinAnalysisLevel.CAUTION, SkinAnalysisLevel.SAFE);
		SkinAnalysis middle = analysisAt(LocalDate.of(2026, 8, 3), SkinAnalysisLevel.DANGER, SkinAnalysisLevel.CAUTION);
		SkinAnalysis latest = analysisAt(LocalDate.of(2026, 8, 10), SkinAnalysisLevel.DANGER, SkinAnalysisLevel.SAFE);

		when(skinAnalysisRepository.findByAnalyzedAtGreaterThanEqualOrderByAnalyzedAtAsc(any()))
				.thenReturn(List.of(oldest, middle, latest));

		SkinAnalysisDto.HistoryResponse response = skinAnalysisService.getHistory(28);

		assertThat(response.periodDays()).isEqualTo(28);
		assertThat(response.latest().rednessLevel()).isEqualTo(SkinAnalysisLevel.DANGER);
		assertThat(response.latest().troubleLevel()).isEqualTo(SkinAnalysisLevel.SAFE);
		assertThat(response.average().rednessLevel()).isEqualTo(SkinAnalysisLevel.DANGER);
		assertThat(response.average().troubleLevel()).isEqualTo(SkinAnalysisLevel.SAFE);
		assertThat(response.history()).hasSize(3);
		assertThat(response.history().get(0).date()).isEqualTo(LocalDate.of(2026, 8, 1));
		assertThat(response.history().get(2).date()).isEqualTo(LocalDate.of(2026, 8, 10));
	}

	private SkinAnalysis analysisAt(LocalDate date, SkinAnalysisLevel rednessLevel, SkinAnalysisLevel troubleLevel) {
		SkinImage skinImage = new SkinImage("http://example.com/image.jpg", "stored.jpg");
		SkinAnalysis skinAnalysis = new SkinAnalysis(
				skinImage,
				rednessLevel, List.of(), null,
				troubleLevel, List.of(), null,
				rednessLevel.ordinal() >= troubleLevel.ordinal() ? rednessLevel : troubleLevel,
				ImageQualityRating.GOOD, ImageQualityRating.GOOD,
				ImageQualityRating.GOOD, ImageQualityRating.GOOD,
				"v3"
		);
		// analyzedAt은 @CreationTimestamp라 실제 영속화 시점에만 채워지므로 테스트에서 직접 주입한다.
		ReflectionTestUtils.setField(skinAnalysis, "analyzedAt", date.atStartOfDay());
		return skinAnalysis;
	}
}
