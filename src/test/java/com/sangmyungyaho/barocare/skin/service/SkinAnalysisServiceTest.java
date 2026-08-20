package com.sangmyungyaho.barocare.skin.service;

import com.sangmyungyaho.barocare.ai.client.AiClient;
import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.global.storage.ImageStorageService;
import com.sangmyungyaho.barocare.routine.entity.RecommendationStatus;
import com.sangmyungyaho.barocare.routine.service.IngredientRecommendationService;
import com.sangmyungyaho.barocare.skin.dto.SkinAnalysisDto;
import com.sangmyungyaho.barocare.skin.entity.FaceRegion;
import com.sangmyungyaho.barocare.skin.entity.ImageQualityRating;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.skin.entity.SkinImage;
import com.sangmyungyaho.barocare.skin.repository.SkinAnalysisRepository;
import com.sangmyungyaho.barocare.skin.repository.SkinImageRepository;
import com.sangmyungyaho.barocare.user.entity.SkinType;
import com.sangmyungyaho.barocare.user.entity.User;
import com.sangmyungyaho.barocare.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Issue #20: 피부 분석 히스토리(getHistory) 단위 테스트.
 * 숫자 점수가 아니라 등급(SAFE/CAUTION/DANGER) 기준 latest/average(최빈값)를 검증한다.
 *
 * fix: 기존 인증 및 사용자 데이터 처리 안정화 - analyzeSkin()의 SkinImage 소유권 검증 테스트 추가.
 */
@ExtendWith(MockitoExtension.class)
class SkinAnalysisServiceTest {

	private static final Long USER_ID = 1L;
	private static final Long OTHER_USER_ID = 2L;

	@Mock
	private SkinImageRepository skinImageRepository;

	@Mock
	private SkinAnalysisRepository skinAnalysisRepository;

	@Mock
	private ImageStorageService imageStorageService;

	@Mock
	private AiClient aiClient;

	@Mock
	private SkinGradeRubric skinGradeRubric;

	@Mock
	private UserRepository userRepository;

	@Mock
	private SkinAnalysisFollowUpService skinAnalysisFollowUpService;

	@Mock
	private IngredientRecommendationService ingredientRecommendationService;

	@InjectMocks
	private SkinAnalysisService skinAnalysisService;

	@Test
	void 기록이_없으면_latest와_average는_null이고_history는_빈_배열이다() {
		when(skinAnalysisRepository.findAllByUserIdAndAnalyzedAtBetweenOrderByAnalyzedAtAsc(eq(USER_ID), any(), any()))
				.thenReturn(List.of());

		SkinAnalysisDto.HistoryResponse response = skinAnalysisService.getHistory(USER_ID, 28);

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

		when(skinAnalysisRepository.findAllByUserIdAndAnalyzedAtBetweenOrderByAnalyzedAtAsc(eq(USER_ID), any(), any()))
				.thenReturn(List.of(oldest, middle, latest));

		SkinAnalysisDto.HistoryResponse response = skinAnalysisService.getHistory(USER_ID, 28);

		assertThat(response.periodDays()).isEqualTo(28);
		assertThat(response.latest().rednessLevel()).isEqualTo(SkinAnalysisLevel.DANGER);
		assertThat(response.latest().troubleLevel()).isEqualTo(SkinAnalysisLevel.SAFE);
		assertThat(response.average().rednessLevel()).isEqualTo(SkinAnalysisLevel.DANGER);
		assertThat(response.average().troubleLevel()).isEqualTo(SkinAnalysisLevel.SAFE);
		assertThat(response.history()).hasSize(3);
		assertThat(response.history().get(0).date()).isEqualTo(LocalDate.of(2026, 8, 1));
		assertThat(response.history().get(2).date()).isEqualTo(LocalDate.of(2026, 8, 10));
	}

	@Test
	void 다른_사용자의_이미지로_분석을_요청하면_FORBIDDEN_예외가_발생하고_이후_로직은_실행되지_않는다() {
		SkinImage othersImage = new SkinImage(OTHER_USER_ID, "http://example.com/others.jpg", "others.jpg");
		when(skinImageRepository.findById(55L)).thenReturn(Optional.of(othersImage));

		SkinAnalysisDto.Request request = new SkinAnalysisDto.Request(55L);

		assertThatThrownBy(() -> skinAnalysisService.analyzeSkin(USER_ID, request))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);

		verifyNoInteractions(imageStorageService, aiClient, skinGradeRubric);
		verify(skinAnalysisRepository, never()).save(any());
	}

	@Test
	void 본인_소유_이미지면_정상적으로_분석하고_저장한다() {
		SkinImage myImage = new SkinImage(USER_ID, "http://example.com/mine.jpg", "mine.jpg");
		ReflectionTestUtils.setField(myImage, "id", 55L);
		when(skinImageRepository.findById(55L)).thenReturn(Optional.of(myImage));
		when(imageStorageService.load("skin-images", "mine.jpg")).thenReturn(Optional.of(new byte[]{1, 2, 3}));

		AiDto.SkinAnalysisResult aiResult = new AiDto.SkinAnalysisResult(
				new AiDto.RednessObservation(List.of(), null),
				new AiDto.TroubleObservation(List.of(FaceRegion.CHEEK_LEFT), com.sangmyungyaho.barocare.skin.entity.TroubleDensity.FEW),
				new AiDto.ImageQuality(ImageQualityRating.GOOD, ImageQualityRating.GOOD, ImageQualityRating.GOOD, ImageQualityRating.GOOD)
		);
		when(aiClient.analyzeSkin(any(), any())).thenReturn(aiResult);
		// userRepository는 스텁하지 않는다 - Optional.empty()가 기본 반환값이므로 skinType=null(피부타입
		// 미설정)로 rubric이 호출되고, 이는 기존(피부타입 보정 도입 전)과 동일한 기준으로 폴백된다는 뜻이다.
		when(skinGradeRubric.calculateRednessLevel(eq(aiResult.redness()), any())).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinGradeRubric.calculateTroubleLevel(eq(aiResult.trouble()), any())).thenReturn(SkinAnalysisLevel.CAUTION);
		when(skinGradeRubric.calculateSkinLevel(SkinAnalysisLevel.SAFE, SkinAnalysisLevel.CAUTION)).thenReturn(SkinAnalysisLevel.CAUTION);
		when(skinAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		SkinAnalysisDto.Request request = new SkinAnalysisDto.Request(55L);
		SkinAnalysisDto.Response response = skinAnalysisService.analyzeSkin(USER_ID, request);

		assertThat(response.skinImageId()).isEqualTo(myImage.getId());
		assertThat(response.redness()).isEqualTo(SkinAnalysisLevel.SAFE);
		assertThat(response.trouble()).isEqualTo(SkinAnalysisLevel.CAUTION);
		assertThat(response.skinLevel()).isEqualTo(SkinAnalysisLevel.CAUTION);
		verify(skinAnalysisRepository).save(any());
		// 정책: 분석이 성공적으로 저장된 후에는 원본 이미지 파일을 삭제한다.
		verify(imageStorageService).delete("skin-images", "mine.jpg");
	}

	@Test
	void 분석_저장_후_오늘_리포트_루틴_생성을_동기로_위임하고_추천_생성을_백그라운드로_트리거한다() {
		// 요구사항: "피부 분석 + 원인 분석 + 오늘의 루틴까지" 완료된 뒤에 응답해야 하므로
		// SkinAnalysisFollowUpService 호출은 동기다(응답 전에 끝나야 함). 반면 추천 성분/제품 생성은
		// 응답을 기다리지 않는 별도 백그라운드 작업이라 initializeTodayRecommendation(동기, PENDING
		// row 선점)과 generateTodayRecommendation(@Async)을 함께 트리거한다. 실제 체크인 조회/리포트/
		// 루틴 생성, 성분/제품 생성 로직 자체는 각각 SkinAnalysisFollowUpServiceTest/
		// IngredientRecommendationServiceTest에서 검증한다 - 여기서는 "저장된 SkinAnalysis와 함께
		// 두 위임 호출이 모두 일어난다"는 경계만 검증한다.
		SkinImage myImage = new SkinImage(USER_ID, "http://example.com/mine.jpg", "mine.jpg");
		ReflectionTestUtils.setField(myImage, "id", 55L);
		when(skinImageRepository.findById(55L)).thenReturn(Optional.of(myImage));
		when(imageStorageService.load("skin-images", "mine.jpg")).thenReturn(Optional.of(new byte[]{1, 2, 3}));

		AiDto.SkinAnalysisResult aiResult = new AiDto.SkinAnalysisResult(
				new AiDto.RednessObservation(List.of(), null),
				new AiDto.TroubleObservation(List.of(), null),
				new AiDto.ImageQuality(ImageQualityRating.GOOD, ImageQualityRating.GOOD, ImageQualityRating.GOOD, ImageQualityRating.GOOD)
		);
		when(aiClient.analyzeSkin(any(), any())).thenReturn(aiResult);
		when(skinGradeRubric.calculateRednessLevel(any(), any())).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinGradeRubric.calculateTroubleLevel(any(), any())).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinGradeRubric.calculateSkinLevel(SkinAnalysisLevel.SAFE, SkinAnalysisLevel.SAFE)).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		SkinAnalysisDto.Request request = new SkinAnalysisDto.Request(55L);
		SkinAnalysisDto.Response response = skinAnalysisService.analyzeSkin(USER_ID, request);

		assertThat(response.skinImageId()).isEqualTo(myImage.getId());
		verify(skinAnalysisFollowUpService).generateTodayReportAndRoutines(eq(USER_ID), any());
		verify(ingredientRecommendationService).initializeTodayRecommendation(eq(USER_ID), any());
		verify(ingredientRecommendationService).generateTodayRecommendation(eq(USER_ID), any());
		verify(imageStorageService).delete("skin-images", "mine.jpg");
	}

	@Test
	void 리포트_루틴_위임_호출이_예외를_던져도_피부_분석_저장_응답은_영향받지_않는다() {
		SkinImage myImage = new SkinImage(USER_ID, "http://example.com/mine.jpg", "mine.jpg");
		ReflectionTestUtils.setField(myImage, "id", 55L);
		when(skinImageRepository.findById(55L)).thenReturn(Optional.of(myImage));
		when(imageStorageService.load("skin-images", "mine.jpg")).thenReturn(Optional.of(new byte[]{1, 2, 3}));

		AiDto.SkinAnalysisResult aiResult = new AiDto.SkinAnalysisResult(
				new AiDto.RednessObservation(List.of(), null),
				new AiDto.TroubleObservation(List.of(), null),
				new AiDto.ImageQuality(ImageQualityRating.GOOD, ImageQualityRating.GOOD, ImageQualityRating.GOOD, ImageQualityRating.GOOD)
		);
		when(aiClient.analyzeSkin(any(), any())).thenReturn(aiResult);
		when(skinGradeRubric.calculateRednessLevel(any(), any())).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinGradeRubric.calculateTroubleLevel(any(), any())).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinGradeRubric.calculateSkinLevel(SkinAnalysisLevel.SAFE, SkinAnalysisLevel.SAFE)).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		org.mockito.Mockito.doThrow(new RuntimeException("체크인 조회 중 오류"))
				.when(skinAnalysisFollowUpService).generateTodayReportAndRoutines(eq(USER_ID), any());

		SkinAnalysisDto.Request request = new SkinAnalysisDto.Request(55L);
		SkinAnalysisDto.Response response = skinAnalysisService.analyzeSkin(USER_ID, request);

		assertThat(response).isNotNull();
		assertThat(response.skinImageId()).isEqualTo(myImage.getId());
		// 리포트/루틴 위임이 실패해도 추천 생성 트리거는 별개로 계속 시도돼야 한다.
		verify(ingredientRecommendationService).generateTodayRecommendation(eq(USER_ID), any());
		verify(imageStorageService).delete("skin-images", "mine.jpg");
	}

	@Test
	void 추천_생성_트리거_자체가_동기적으로_실패해도_피부_분석_저장_응답은_영향받지_않는다() {
		// 스레드풀 큐가 가득 찬 경우(TaskRejectedException 등) @Async 메서드가 시작되기도 전에
		// 호출부에서 동기적으로 예외가 날 수 있다 - 이 경우에도 피부 분석 저장 응답 자체는 항상
		// 성공해야 한다(SkinAnalysisService의 방어적 try/catch 검증).
		SkinImage myImage = new SkinImage(USER_ID, "http://example.com/mine.jpg", "mine.jpg");
		ReflectionTestUtils.setField(myImage, "id", 55L);
		when(skinImageRepository.findById(55L)).thenReturn(Optional.of(myImage));
		when(imageStorageService.load("skin-images", "mine.jpg")).thenReturn(Optional.of(new byte[]{1, 2, 3}));

		AiDto.SkinAnalysisResult aiResult = new AiDto.SkinAnalysisResult(
				new AiDto.RednessObservation(List.of(), null),
				new AiDto.TroubleObservation(List.of(), null),
				new AiDto.ImageQuality(ImageQualityRating.GOOD, ImageQualityRating.GOOD, ImageQualityRating.GOOD, ImageQualityRating.GOOD)
		);
		when(aiClient.analyzeSkin(any(), any())).thenReturn(aiResult);
		when(skinGradeRubric.calculateRednessLevel(any(), any())).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinGradeRubric.calculateTroubleLevel(any(), any())).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinGradeRubric.calculateSkinLevel(SkinAnalysisLevel.SAFE, SkinAnalysisLevel.SAFE)).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		org.mockito.Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("풀 포화"))
				.when(ingredientRecommendationService).generateTodayRecommendation(eq(USER_ID), any());

		SkinAnalysisDto.Request request = new SkinAnalysisDto.Request(55L);
		SkinAnalysisDto.Response response = skinAnalysisService.analyzeSkin(USER_ID, request);

		assertThat(response).isNotNull();
		assertThat(response.skinImageId()).isEqualTo(myImage.getId());
		verify(imageStorageService).delete("skin-images", "mine.jpg");
	}

	@Test
	void 본인_소유_분석의_추천_성분_상태를_조회하면_소유권_검증_후_그대로_위임한다() {
		SkinAnalysis analysis = analysisAt(LocalDate.of(2026, 8, 10), SkinAnalysisLevel.SAFE, SkinAnalysisLevel.SAFE);
		ReflectionTestUtils.setField(analysis, "id", 12L);
		when(skinAnalysisRepository.findById(12L)).thenReturn(Optional.of(analysis));
		var expected = com.sangmyungyaho.barocare.routine.dto.IngredientRecommendationDto.IngredientStatusResponse.of(
				RecommendationStatus.PROCESSING, List.of());
		when(ingredientRecommendationService.getIngredientStatus(12L)).thenReturn(expected);

		var response = skinAnalysisService.getIngredientRecommendationStatus(USER_ID, 12L);

		assertThat(response).isEqualTo(expected);
	}

	@Test
	void 다른_사용자의_분석에_대한_추천_성분_상태_조회는_FORBIDDEN을_던진다() {
		SkinAnalysis othersAnalysis = analysisAt(LocalDate.of(2026, 8, 10), SkinAnalysisLevel.SAFE, SkinAnalysisLevel.SAFE);
		ReflectionTestUtils.setField(othersAnalysis, "id", 12L);
		ReflectionTestUtils.setField(othersAnalysis, "userId", OTHER_USER_ID);
		when(skinAnalysisRepository.findById(12L)).thenReturn(Optional.of(othersAnalysis));

		assertThatThrownBy(() -> skinAnalysisService.getIngredientRecommendationStatus(USER_ID, 12L))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);
		verifyNoInteractions(ingredientRecommendationService);
	}

	@Test
	void 존재하지_않는_분석의_추천_제품_상태_조회는_SKIN_ANALYSIS_NOT_FOUND를_던진다() {
		when(skinAnalysisRepository.findById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> skinAnalysisService.getProductRecommendationStatus(USER_ID, 999L))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.SKIN_ANALYSIS_NOT_FOUND);
		verifyNoInteractions(ingredientRecommendationService);
	}

	@Test
	void 원본_이미지_삭제가_실패해도_분석_결과_저장_응답은_영향받지_않는다() {
		// 요구사항: 원본 파일 삭제 실패가 이미 끝난 SkinAnalysis 저장을 롤백시키면 안 된다.
		SkinImage myImage = new SkinImage(USER_ID, "http://example.com/mine.jpg", "mine.jpg");
		ReflectionTestUtils.setField(myImage, "id", 55L);
		when(skinImageRepository.findById(55L)).thenReturn(Optional.of(myImage));
		when(imageStorageService.load("skin-images", "mine.jpg")).thenReturn(Optional.of(new byte[]{1, 2, 3}));

		AiDto.SkinAnalysisResult aiResult = new AiDto.SkinAnalysisResult(
				new AiDto.RednessObservation(List.of(), null),
				new AiDto.TroubleObservation(List.of(), null),
				new AiDto.ImageQuality(ImageQualityRating.GOOD, ImageQualityRating.GOOD, ImageQualityRating.GOOD, ImageQualityRating.GOOD)
		);
		when(aiClient.analyzeSkin(any(), any())).thenReturn(aiResult);
		when(skinGradeRubric.calculateRednessLevel(any(), any())).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinGradeRubric.calculateTroubleLevel(any(), any())).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinGradeRubric.calculateSkinLevel(SkinAnalysisLevel.SAFE, SkinAnalysisLevel.SAFE)).thenReturn(SkinAnalysisLevel.SAFE);
		when(skinAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(imageStorageService.delete("skin-images", "mine.jpg"))
				.thenThrow(new RuntimeException("디스크 IO 오류"));

		SkinAnalysisDto.Request request = new SkinAnalysisDto.Request(55L);
		SkinAnalysisDto.Response response = skinAnalysisService.analyzeSkin(USER_ID, request);

		assertThat(response).isNotNull();
		assertThat(response.skinAnalysisId()).isNull(); // save()가 mock이라 id는 채워지지 않지만 응답 자체는 정상 반환됨을 확인
		verify(skinAnalysisRepository).save(any());
	}

	@Test
	void 이미지_품질이_부족해_분석이_실패하면_원본_이미지를_삭제하지_않는다() {
		SkinImage myImage = new SkinImage(USER_ID, "http://example.com/mine.jpg", "mine.jpg");
		when(skinImageRepository.findById(55L)).thenReturn(Optional.of(myImage));
		when(imageStorageService.load("skin-images", "mine.jpg")).thenReturn(Optional.of(new byte[]{1, 2, 3}));

		AiDto.SkinAnalysisResult aiResult = new AiDto.SkinAnalysisResult(
				new AiDto.RednessObservation(List.of(), null),
				new AiDto.TroubleObservation(List.of(), null),
				new AiDto.ImageQuality(ImageQualityRating.POOR, ImageQualityRating.POOR, ImageQualityRating.GOOD, ImageQualityRating.GOOD)
		);
		when(aiClient.analyzeSkin(any(), any())).thenReturn(aiResult);

		SkinAnalysisDto.Request request = new SkinAnalysisDto.Request(55L);

		assertThatThrownBy(() -> skinAnalysisService.analyzeSkin(USER_ID, request))
				.isInstanceOf(GlobalException.class);

		verify(imageStorageService, never()).delete(any(), any());
	}

	@Test
	void 이미지_품질이_부족하면_SKIN_IMAGE_QUALITY_INSUFFICIENT를_던지고_저장하지_않는다() {
		// fix: 재촬영이 필요한 경우(이미지 품질/신뢰도 부족)와 AI API 호출 자체 실패(AI_ANALYSIS_FAILED)를
		// 서로 다른 ErrorCode로 구분한다. POOR 2개는 허용치(1개)를 초과한다.
		SkinImage myImage = new SkinImage(USER_ID, "http://example.com/mine.jpg", "mine.jpg");
		when(skinImageRepository.findById(55L)).thenReturn(Optional.of(myImage));
		when(imageStorageService.load("skin-images", "mine.jpg")).thenReturn(Optional.of(new byte[]{1, 2, 3}));

		AiDto.SkinAnalysisResult aiResult = new AiDto.SkinAnalysisResult(
				new AiDto.RednessObservation(List.of(), null),
				new AiDto.TroubleObservation(List.of(), null),
				new AiDto.ImageQuality(ImageQualityRating.POOR, ImageQualityRating.POOR, ImageQualityRating.GOOD, ImageQualityRating.GOOD)
		);
		when(aiClient.analyzeSkin(any(), any())).thenReturn(aiResult);

		SkinAnalysisDto.Request request = new SkinAnalysisDto.Request(55L);

		assertThatThrownBy(() -> skinAnalysisService.analyzeSkin(USER_ID, request))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.SKIN_IMAGE_QUALITY_INSUFFICIENT);

		verify(skinAnalysisRepository, never()).save(any());
	}

	@Test
	void AI_응답_구조가_손상되면_AI_ANALYSIS_FAILED를_던진다() {
		// 이미지 품질 문제(재촬영 필요)가 아니라 AI 응답 자체의 파싱/형식 문제이므로 502로 유지된다.
		SkinImage myImage = new SkinImage(USER_ID, "http://example.com/mine.jpg", "mine.jpg");
		when(skinImageRepository.findById(55L)).thenReturn(Optional.of(myImage));
		when(imageStorageService.load("skin-images", "mine.jpg")).thenReturn(Optional.of(new byte[]{1, 2, 3}));

		AiDto.SkinAnalysisResult malformedResult = new AiDto.SkinAnalysisResult(null, null, null);
		when(aiClient.analyzeSkin(any(), any())).thenReturn(malformedResult);

		SkinAnalysisDto.Request request = new SkinAnalysisDto.Request(55L);

		assertThatThrownBy(() -> skinAnalysisService.analyzeSkin(USER_ID, request))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.AI_ANALYSIS_FAILED);

		verify(skinAnalysisRepository, never()).save(any());
		verify(imageStorageService, never()).delete(any(), any());
	}

	@Test
	void 상세조회시_직전_분석이_있으면_baseline이_아니고_변화_상태를_계산한다() {
		// feat: 프론트 화면 연동을 위한 조회 API - 피부 분석 상세 조회.
		SkinAnalysis previous = analysisAt(LocalDate.of(2026, 8, 1), SkinAnalysisLevel.DANGER, SkinAnalysisLevel.CAUTION);
		ReflectionTestUtils.setField(previous, "id", 8L);
		SkinAnalysis current = analysisAt(LocalDate.of(2026, 8, 10), SkinAnalysisLevel.CAUTION, SkinAnalysisLevel.CAUTION);
		ReflectionTestUtils.setField(current, "id", 12L);

		when(skinAnalysisRepository.findById(12L)).thenReturn(Optional.of(current));
		when(skinAnalysisRepository.findFirstByUserIdOrderByAnalyzedAtAsc(USER_ID)).thenReturn(Optional.of(previous)); // baseline != current
		when(skinAnalysisRepository.findTopByUserIdAndAnalyzedAtLessThanOrderByAnalyzedAtDesc(USER_ID, current.getAnalyzedAt()))
				.thenReturn(Optional.of(previous));

		SkinAnalysisDto.DetailResponse response = skinAnalysisService.getDetail(USER_ID, 12L);

		assertThat(response.skinAnalysisId()).isEqualTo(12L);
		assertThat(response.isBaseline()).isFalse();
		assertThat(response.previousSkinAnalysisId()).isEqualTo(8L);
		// redness: DANGER(2) -> CAUTION(1), change=-1 -> IMPROVED
		assertThat(response.rednessChangeStatus()).isEqualTo(com.sangmyungyaho.barocare.report.entity.ReportChangeStatus.IMPROVED);
		// trouble: CAUTION(1) -> CAUTION(1), change=0 -> UNCHANGED
		assertThat(response.troubleChangeStatus()).isEqualTo(com.sangmyungyaho.barocare.report.entity.ReportChangeStatus.UNCHANGED);
	}

	@Test
	void 상세조회시_직전_분석이_없으면_baseline이고_변화_상태는_null이다() {
		SkinAnalysis onlyAnalysis = analysisAt(LocalDate.of(2026, 8, 1), SkinAnalysisLevel.SAFE, SkinAnalysisLevel.SAFE);
		ReflectionTestUtils.setField(onlyAnalysis, "id", 5L);

		when(skinAnalysisRepository.findById(5L)).thenReturn(Optional.of(onlyAnalysis));
		when(skinAnalysisRepository.findFirstByUserIdOrderByAnalyzedAtAsc(USER_ID)).thenReturn(Optional.of(onlyAnalysis));
		when(skinAnalysisRepository.findTopByUserIdAndAnalyzedAtLessThanOrderByAnalyzedAtDesc(USER_ID, onlyAnalysis.getAnalyzedAt()))
				.thenReturn(Optional.empty());

		SkinAnalysisDto.DetailResponse response = skinAnalysisService.getDetail(USER_ID, 5L);

		assertThat(response.isBaseline()).isTrue();
		assertThat(response.previousSkinAnalysisId()).isNull();
		assertThat(response.rednessChangeStatus()).isNull();
		assertThat(response.troubleChangeStatus()).isNull();
	}

	@Test
	void 상세조회시_다른_사용자의_분석이면_FORBIDDEN을_던진다() {
		SkinAnalysis othersAnalysis = analysisAt(LocalDate.of(2026, 8, 1), SkinAnalysisLevel.SAFE, SkinAnalysisLevel.SAFE);
		ReflectionTestUtils.setField(othersAnalysis, "id", 9L);
		ReflectionTestUtils.setField(othersAnalysis, "userId", OTHER_USER_ID);
		when(skinAnalysisRepository.findById(9L)).thenReturn(Optional.of(othersAnalysis));

		assertThatThrownBy(() -> skinAnalysisService.getDetail(USER_ID, 9L))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);
	}

	@Test
	void 상세조회시_존재하지_않으면_SKIN_ANALYSIS_NOT_FOUND를_던진다() {
		when(skinAnalysisRepository.findById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> skinAnalysisService.getDetail(USER_ID, 999L))
				.isInstanceOf(GlobalException.class)
				.extracting(e -> ((GlobalException) e).getErrorCode())
				.isEqualTo(ErrorCode.SKIN_ANALYSIS_NOT_FOUND);
	}

	@Test
	void 최신_상세조회는_분석이_2건_이상이면_baseline이_아니고_변화_상태를_계산한다() {
		// feat: 홈 대시보드 통합 조회 - findTop2 한 번으로 최신+직전을 동시에 구하는지 검증한다(추가 조회 없음).
		SkinAnalysis previous = analysisAt(LocalDate.of(2026, 8, 1), SkinAnalysisLevel.DANGER, SkinAnalysisLevel.CAUTION);
		ReflectionTestUtils.setField(previous, "id", 8L);
		SkinAnalysis latest = analysisAt(LocalDate.of(2026, 8, 10), SkinAnalysisLevel.CAUTION, SkinAnalysisLevel.CAUTION);
		ReflectionTestUtils.setField(latest, "id", 12L);

		when(skinAnalysisRepository.findTop2ByUserIdOrderByAnalyzedAtDesc(USER_ID)).thenReturn(List.of(latest, previous));
		when(skinAnalysisRepository.findFirstByUserIdOrderByAnalyzedAtAsc(USER_ID)).thenReturn(Optional.of(previous));

		Optional<SkinAnalysisDto.DetailResponse> result = skinAnalysisService.getLatestDetailForUser(USER_ID);

		assertThat(result).isPresent();
		assertThat(result.get().skinAnalysisId()).isEqualTo(12L);
		assertThat(result.get().isBaseline()).isFalse();
		assertThat(result.get().previousSkinAnalysisId()).isEqualTo(8L);
		assertThat(result.get().rednessChangeStatus())
				.isEqualTo(com.sangmyungyaho.barocare.report.entity.ReportChangeStatus.IMPROVED);
		verify(skinAnalysisRepository, never())
				.findTopByUserIdAndAnalyzedAtLessThanOrderByAnalyzedAtDesc(any(), any());
	}

	@Test
	void 최신_상세조회는_분석이_1건뿐이면_baseline이고_변화_상태는_null이다() {
		SkinAnalysis onlyAnalysis = analysisAt(LocalDate.of(2026, 8, 1), SkinAnalysisLevel.SAFE, SkinAnalysisLevel.SAFE);
		ReflectionTestUtils.setField(onlyAnalysis, "id", 5L);

		when(skinAnalysisRepository.findTop2ByUserIdOrderByAnalyzedAtDesc(USER_ID)).thenReturn(List.of(onlyAnalysis));
		when(skinAnalysisRepository.findFirstByUserIdOrderByAnalyzedAtAsc(USER_ID)).thenReturn(Optional.of(onlyAnalysis));

		Optional<SkinAnalysisDto.DetailResponse> result = skinAnalysisService.getLatestDetailForUser(USER_ID);

		assertThat(result).isPresent();
		assertThat(result.get().isBaseline()).isTrue();
		assertThat(result.get().previousSkinAnalysisId()).isNull();
		assertThat(result.get().rednessChangeStatus()).isNull();
	}

	@Test
	void 최신_상세조회는_분석이_전혀_없으면_빈_Optional을_반환하고_새로_분석하지_않는다() {
		when(skinAnalysisRepository.findTop2ByUserIdOrderByAnalyzedAtDesc(USER_ID)).thenReturn(List.of());

		Optional<SkinAnalysisDto.DetailResponse> result = skinAnalysisService.getLatestDetailForUser(USER_ID);

		assertThat(result).isEmpty();
		verifyNoInteractions(aiClient);
	}

	private SkinAnalysis analysisAt(LocalDate date, SkinAnalysisLevel rednessLevel, SkinAnalysisLevel troubleLevel) {
		SkinImage skinImage = new SkinImage(USER_ID, "http://example.com/image.jpg", "stored.jpg");
		SkinAnalysis skinAnalysis = new SkinAnalysis(
				USER_ID, skinImage,
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
