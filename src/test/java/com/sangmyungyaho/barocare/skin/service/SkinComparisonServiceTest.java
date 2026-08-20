package com.sangmyungyaho.barocare.skin.service;

import com.sangmyungyaho.barocare.skin.dto.SkinComparisonDto;
import com.sangmyungyaho.barocare.skin.entity.ChangeDirection;
import com.sangmyungyaho.barocare.skin.entity.FaceRegion;
import com.sangmyungyaho.barocare.skin.entity.ImageQualityRating;
import com.sangmyungyaho.barocare.skin.entity.RednessIntensity;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.skin.entity.SkinComparison;
import com.sangmyungyaho.barocare.skin.entity.SkinImage;
import com.sangmyungyaho.barocare.skin.entity.TroubleDensity;
import com.sangmyungyaho.barocare.skin.repository.SkinAnalysisRepository;
import com.sangmyungyaho.barocare.skin.repository.SkinComparisonRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 원본 이미지 보관 정책 변경에 따라 SkinComparisonService가 더 이상 원본 이미지/AI(GPT Vision)에
 * 의존하지 않고, 이미 저장된 SkinAnalysis 관찰값만으로 변화 방향을 계산하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SkinComparisonServiceTest {

	private static final Long USER_ID = 1L;

	@Mock
	private SkinAnalysisRepository skinAnalysisRepository;

	@Mock
	private SkinComparisonRepository skinComparisonRepository;

	@InjectMocks
	private SkinComparisonService skinComparisonService;

	@Test
	void 이전_분석이_없으면_비교_없이_즉시_반환하고_아무것도_저장하지_않는다() {
		SkinAnalysis current = analysis(SkinAnalysisLevel.SAFE, null, SkinAnalysisLevel.SAFE, null);
		ReflectionTestUtils.setField(current, "id", 12L);
		when(skinAnalysisRepository.findById(12L)).thenReturn(Optional.of(current));

		SkinComparisonService.Outcome outcome = skinComparisonService
				.compareSkin(new SkinComparisonDto.Request(12L, null));

		assertThat(outcome.created()).isFalse();
		assertThat(outcome.response().skinComparisonId()).isNull();
		assertThat(outcome.response().rednessChange()).isNull();
		verify(skinComparisonRepository, never()).save(any());
	}

	@Test
	void 이미_계산된_조합이면_재계산하지_않고_기존_결과를_재사용한다() {
		SkinAnalysis current = analysis(SkinAnalysisLevel.SAFE, null, SkinAnalysisLevel.SAFE, null);
		ReflectionTestUtils.setField(current, "id", 12L);
		SkinAnalysis previous = analysis(SkinAnalysisLevel.CAUTION, null, SkinAnalysisLevel.SAFE, null);
		ReflectionTestUtils.setField(previous, "id", 8L);
		SkinComparison existing = new SkinComparison(current, previous, ChangeDirection.DECREASED, ChangeDirection.STABLE);

		when(skinAnalysisRepository.findById(12L)).thenReturn(Optional.of(current));
		when(skinAnalysisRepository.findById(8L)).thenReturn(Optional.of(previous));
		when(skinComparisonRepository.findByCurrentSkinAnalysis_IdAndPreviousSkinAnalysis_Id(12L, 8L))
				.thenReturn(Optional.of(existing));

		SkinComparisonService.Outcome outcome = skinComparisonService
				.compareSkin(new SkinComparisonDto.Request(12L, 8L));

		assertThat(outcome.created()).isFalse();
		assertThat(outcome.response().rednessChange()).isEqualTo(ChangeDirection.DECREASED);
		verify(skinComparisonRepository, never()).save(any());
	}

	@Test
	void 등급이_같으면_세부_강도로_변화_방향을_판단한다() {
		// redness: 둘 다 CAUTION(레벨 동일) -> 세부 강도 SEVERE(현재) vs MILD(이전) -> INCREASED
		// trouble: 둘 다 SAFE(레벨 동일) -> 세부 밀도 FEW(현재) vs MANY(이전) -> DECREASED
		SkinAnalysis current = analysis(SkinAnalysisLevel.CAUTION, RednessIntensity.SEVERE, SkinAnalysisLevel.SAFE, TroubleDensity.FEW);
		ReflectionTestUtils.setField(current, "id", 12L);
		SkinAnalysis previous = analysis(SkinAnalysisLevel.CAUTION, RednessIntensity.MILD, SkinAnalysisLevel.SAFE, TroubleDensity.MANY);
		ReflectionTestUtils.setField(previous, "id", 8L);

		when(skinAnalysisRepository.findById(12L)).thenReturn(Optional.of(current));
		when(skinAnalysisRepository.findById(8L)).thenReturn(Optional.of(previous));
		when(skinComparisonRepository.findByCurrentSkinAnalysis_IdAndPreviousSkinAnalysis_Id(12L, 8L))
				.thenReturn(Optional.empty());
		when(skinComparisonRepository.save(any()))
				.thenAnswer(invocation -> invocation.getArgument(0));

		SkinComparisonService.Outcome outcome = skinComparisonService
				.compareSkin(new SkinComparisonDto.Request(12L, 8L));

		assertThat(outcome.created()).isTrue();
		ArgumentCaptor<SkinComparison> captor = ArgumentCaptor.forClass(SkinComparison.class);
		verify(skinComparisonRepository).save(captor.capture());
		assertThat(captor.getValue().getRednessChange()).isEqualTo(ChangeDirection.INCREASED);
		assertThat(captor.getValue().getTroubleChange()).isEqualTo(ChangeDirection.DECREASED);
	}

	@Test
	void 등급_자체가_다르면_세부값과_무관하게_등급_차이로_판단한다() {
		// redness: 현재 DANGER, 이전 CAUTION -> 세부 강도가 어떻든 레벨 차이가 우선 -> INCREASED
		SkinAnalysis current = analysis(SkinAnalysisLevel.DANGER, RednessIntensity.MILD, SkinAnalysisLevel.SAFE, null);
		ReflectionTestUtils.setField(current, "id", 12L);
		SkinAnalysis previous = analysis(SkinAnalysisLevel.CAUTION, RednessIntensity.SEVERE, SkinAnalysisLevel.SAFE, null);
		ReflectionTestUtils.setField(previous, "id", 8L);

		when(skinAnalysisRepository.findById(12L)).thenReturn(Optional.of(current));
		when(skinAnalysisRepository.findById(8L)).thenReturn(Optional.of(previous));
		when(skinComparisonRepository.findByCurrentSkinAnalysis_IdAndPreviousSkinAnalysis_Id(12L, 8L))
				.thenReturn(Optional.empty());
		when(skinComparisonRepository.save(any()))
				.thenAnswer(invocation -> invocation.getArgument(0));

		SkinComparisonService.Outcome outcome = skinComparisonService
				.compareSkin(new SkinComparisonDto.Request(12L, 8L));

		ArgumentCaptor<SkinComparison> captor = ArgumentCaptor.forClass(SkinComparison.class);
		verify(skinComparisonRepository).save(captor.capture());
		assertThat(captor.getValue().getRednessChange()).isEqualTo(ChangeDirection.INCREASED);
	}

	@Test
	void 등급도_같고_세부값도_없으면_STABLE로_판단한다() {
		SkinAnalysis current = analysis(SkinAnalysisLevel.SAFE, null, SkinAnalysisLevel.SAFE, null);
		ReflectionTestUtils.setField(current, "id", 12L);
		SkinAnalysis previous = analysis(SkinAnalysisLevel.SAFE, null, SkinAnalysisLevel.SAFE, null);
		ReflectionTestUtils.setField(previous, "id", 8L);

		when(skinAnalysisRepository.findById(12L)).thenReturn(Optional.of(current));
		when(skinAnalysisRepository.findById(8L)).thenReturn(Optional.of(previous));
		when(skinComparisonRepository.findByCurrentSkinAnalysis_IdAndPreviousSkinAnalysis_Id(12L, 8L))
				.thenReturn(Optional.empty());
		when(skinComparisonRepository.save(any()))
				.thenAnswer(invocation -> invocation.getArgument(0));

		SkinComparisonService.Outcome outcome = skinComparisonService
				.compareSkin(new SkinComparisonDto.Request(12L, 8L));

		ArgumentCaptor<SkinComparison> captor = ArgumentCaptor.forClass(SkinComparison.class);
		verify(skinComparisonRepository).save(captor.capture());
		assertThat(captor.getValue().getRednessChange()).isEqualTo(ChangeDirection.STABLE);
		assertThat(captor.getValue().getTroubleChange()).isEqualTo(ChangeDirection.STABLE);
	}

	private SkinAnalysis analysis(
			SkinAnalysisLevel rednessLevel, RednessIntensity rednessMaxIntensity,
			SkinAnalysisLevel troubleLevel, TroubleDensity troubleDensity
	) {
		SkinImage skinImage = new SkinImage(USER_ID, "http://example.com/image.jpg", "stored.jpg");
		return new SkinAnalysis(
				USER_ID, skinImage,
				rednessLevel, List.of(FaceRegion.CHEEK_LEFT), rednessMaxIntensity,
				troubleLevel, List.of(), troubleDensity,
				rednessLevel.ordinal() >= troubleLevel.ordinal() ? rednessLevel : troubleLevel,
				ImageQualityRating.GOOD, ImageQualityRating.GOOD,
				ImageQualityRating.GOOD, ImageQualityRating.GOOD,
				"v3"
		);
	}
}
