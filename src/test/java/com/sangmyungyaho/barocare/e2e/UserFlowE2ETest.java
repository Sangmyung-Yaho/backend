package com.sangmyungyaho.barocare.e2e;

import com.sangmyungyaho.barocare.ai.client.AiClient;
import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.global.security.jwt.JwtProvider;
import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.skin.entity.FaceRegion;
import com.sangmyungyaho.barocare.skin.entity.ImageQualityRating;
import com.sangmyungyaho.barocare.skin.entity.RednessIntensity;
import com.sangmyungyaho.barocare.skin.entity.TroubleDensity;
import com.sangmyungyaho.barocare.user.entity.Provider;
import com.sangmyungyaho.barocare.user.entity.User;
import com.sangmyungyaho.barocare.user.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * test: 전체 사용자 플로우 E2E 검증 및 API 연동 안정화.
 *
 * 실제 스프링 컨텍스트(+실행 중인 MySQL/Redis)를 띄우고, 소셜 로그인만 제외한 채 Figma 기준 실제 플로우
 * "온보딩 → 오늘 체크인 → 피부 이미지 업로드/분석(baseline, 이 시점에 오늘 리포트+루틴이 자동 생성됨) →
 * 두 번째 분석(변화 비교, 이 시점에 또 다른 오늘 리포트가 생성되지만 루틴은 이미 있어 재생성되지 않음) →
 * 원인 리포트/루틴 조회 → 홈 대시보드"를 실제 HTTP 요청으로 순서대로 검증한다.
 * OpenAI 호출만 {@link AiClient}를 목으로 대체해 결정론적으로 검증하고(비용/네트워크 의존 제거),
 * 그 외 모든 계층(Security, Controller, Service, Repository, DB)은 실제로 동작시킨다.
 *
 * 개인기준선(과거 체크인 7건) 확보용 과거 날짜 체크인은 운영 API(POST /api/v1/checkins, 오늘 전용으로
 * 변경됨)를 거치지 않고 {@link CheckinRepository}에 직접 저장한다(테스트 fixture) - 운영 API에
 * checked_date를 다시 노출하지 않기 위함이다.
 *
 * 소셜 로그인 자체(카카오/구글 실제 API 호출)는 외부 의존이라 이 테스트에서 실행하지 않는다 -
 * 로그인 이후 상태(발급된 JWT로 인증된 사용자)부터 검증한다. 로그인 콜백의 리다이렉트/토큰 발급
 * 로직 자체는 AuthServiceTest(단위)와 AuthControllerTest(슬라이스)가 별도로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // 테스트마다 별도 트랜잭션으로 감싸 종료 시 롤백 - 실행 중인 개발 DB를 오염시키지 않는다.
// 추천 성분/제품 생성(IngredientRecommendationService.generateTodayRecommendation)이 @Async로
// 백그라운드 스레드풀에서 실행된다(POST /skin-analyses 응답 지연 개선 - 원인 리포트/오늘의 루틴은
// 다시 동기로 되돌아갔으므로 이제 이 부분만 해당). 그런데 이 테스트는 위 @Transactional로 테스트당
// 하나의 트랜잭션/커넥션을 공유하며 끝나면 롤백한다 - 만약 실제 스레드풀 실행기를 그대로 쓰면,
// 백그라운드 스레드는 별도 커넥션에서 실행되어 이 테스트가 아직 커밋하지 않은 체크인/피부분석 데이터를
// 볼 수 없다(트랜잭션 격리). 그래서 아래 SyncAsyncTestConfig로 이 테스트 컨텍스트에서만
// "recommendationExecutor" 빈을 동기 실행기로 교체해, 운영 코드(@Async 애노테이션)는 그대로 두고
// 테스트에서는 같은 스레드/트랜잭션 안에서 즉시 실행되게 한다.
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class UserFlowE2ETest {

	@TestConfiguration
	static class SyncAsyncTestConfig {

		@Bean(name = "recommendationExecutor")
		Executor recommendationExecutor() {
			return new SyncTaskExecutor();
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtProvider jwtProvider;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private CheckinRepository checkinRepository;

	@MockitoBean
	private AiClient aiClient;

	@Test
	void 온보딩부터_홈_대시보드까지_전체_플로우가_정상적으로_연결된다() throws Exception {
		String token = createUserAndToken("건성");

		// ===== 0. 인증 =====
		// JWT 없이 보호된 API 호출 시 401(UNAUTHORIZED), 프로젝트 표준 에러 포맷을 따른다.
		mockMvc.perform(get("/api/v1/users/profile"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		// ===== 1. 온보딩 =====
		mockMvc.perform(patch("/api/v1/users/profile")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"nickname\":\"테스터\",\"height\":170,\"weight\":60,\"skin_type\":\"DRY\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.water_goal_ml").isNumber());

		// 프로필 수정만으로는 온보딩이 완료되면 안 된다(별도 이슈에서 명시적으로 분리한 동작).
		mockMvc.perform(get("/api/v1/onboarding/status").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user.onboarded").value(false));

		mockMvc.perform(post("/api/v1/onboarding/agreements")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"terms_agreed\":true,\"privacy_agreed\":true}"))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/onboarding/complete").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user.onboarded").value(true));

		mockMvc.perform(get("/api/v1/onboarding/status").header("Authorization", "Bearer " + token))
				.andExpect(jsonPath("$.user.onboarded").value(true));

		// 개인화 데이터(피부 타입/목표 음수량)가 실제로 저장됐는지 프로필 조회로 재확인.
		mockMvc.perform(get("/api/v1/users/profile").header("Authorization", "Bearer " + token))
				.andExpect(jsonPath("$.data.skin_type").value("건성"))
				.andExpect(jsonPath("$.data.water_goal_ml").isNumber());

		// ===== 2. 체크인: 개인기준선(과거 체크인 7건)은 운영 API가 아니라 Repository에 직접 시드하고,
		// 오늘 체크인만 실제 운영 API(POST /api/v1/checkins, 오늘 전용)로 나쁜 값으로 저장한다 =====
		LocalDate today = LocalDate.now();
		Long userId = Long.parseLong(jwtProvider.getAuthentication(token).getName());
		for (int i = 7; i >= 1; i--) {
			checkinRepository.save(new Checkin(userId, 7.5, 2, 2000, today.minusDays(i)));
		}
		mockMvc.perform(post("/api/v1/checkins")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sleep_hours\":5.0,\"stress_level\":4,\"water_intake_ml\":800}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.checked_date").value(today.toString()));

		// 같은 날 재저장 시도 -> 409(체크인 저장 자체가 AI/루틴 실패로 롤백된 것이 아니라, 이미 있어서 막힌 것임을 구분 확인)
		mockMvc.perform(post("/api/v1/checkins")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sleep_hours\":5.0,\"stress_level\":4,\"water_intake_ml\":800}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("CHECKIN_ALREADY_EXISTS"));

		mockMvc.perform(get("/api/v1/checkins/today").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.sleep_hours").value(5.0));

		mockMvc.perform(get("/api/v1/checkins")
						.header("Authorization", "Bearer " + token)
						.param("startDate", today.minusDays(7).toString())
						.param("endDate", today.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(8));

		// AI 원인 분석 응답을 미리 스텁해둔다 - 이제 원인 리포트는 GET 시점이 아니라 피부 분석(analyzeSkin)
		// 완료 시점에 자동으로 생성되므로, 첫 분석을 호출하기 전에 스텁이 준비되어 있어야 한다.
		when(aiClient.analyzeSkinChangeCauses(any(), any())).thenReturn(new AiDto.CauseAnalysisResult(
				List.of(
						new AiDto.Cause(ReportCauseFactor.SLEEP, "수면 부족", "최근 수면이 개인 평균보다 부족했어요."),
						new AiDto.Cause(ReportCauseFactor.STRESS, "스트레스 증가", "최근 스트레스가 개인 평균보다 높았어요."),
						new AiDto.Cause(ReportCauseFactor.WATER_INTAKE, "수분 섭취 부족", "최근 수분 섭취가 목표 대비 부족했어요.")
				),
				"최근 수면 부족, 스트레스 증가, 수분 섭취 부족이 함께 관찰됐어요."
		));

		// ===== 3. 피부 이미지 업로드 + AI 분석(최초 -> baseline). 오늘 체크인이 이미 있으므로 이 시점에
		// 오늘 Report(1회차, 비교 대상 없음)와 오늘 Routine이 자동으로 생성된다("Checkin -> SkinImage ->
		// SkinAnalysis -> Report -> Routine" 순서) =====
		when(aiClient.analyzeSkin(any(), any())).thenReturn(observation(List.of(), null, List.of(), null, goodQuality()));
		Long skinImageId1 = uploadSkinImage(token);
		Long skinAnalysisId1 = analyzeSkin(token, skinImageId1);

		mockMvc.perform(get("/api/v1/skin-analyses/" + skinAnalysisId1).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.is_baseline").value(true))
				.andExpect(jsonPath("$.data.previous_skin_analysis_id").doesNotExist())
				.andExpect(jsonPath("$.data.redness_change_status").doesNotExist());

		// 피부 분석 응답 속도 개선(추천 성분/제품 분리): 이 테스트는 aiClient.recommendIngredients()를
		// 스텁하지 않으므로(다른 목적의 테스트라 의도적으로 비워둠) 백그라운드 추천 생성은 실패로
		// 끝난다 - 그래도 그 실패가 이미 위에서 확인한 피부 분석/리포트/루틴 생성 자체에 전혀 영향을
		// 주지 않는다는 것과, 상태 조회 API가 최종 FAILED 상태를 정상적으로 반환한다는 것을 함께
		// 검증한다(SyncAsyncTestConfig 덕분에 이 시점에는 이미 백그라운드 처리가 끝나 있다).
		mockMvc.perform(get("/api/v1/skin-analyses/" + skinAnalysisId1 + "/ingredients").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("FAILED"))
				.andExpect(jsonPath("$.data.ingredients").isArray())
				.andExpect(jsonPath("$.data.ingredients").isEmpty());
		mockMvc.perform(get("/api/v1/skin-analyses/" + skinAnalysisId1 + "/products").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("FAILED")) // 성분 추천이 실패해 제품 검색은 시도조차 되지 않음
				.andExpect(jsonPath("$.data.products").isArray())
				.andExpect(jsonPath("$.data.products").isEmpty());

		// 1회차 리포트: 비교할 이전 분석이 없어도 원인 리포트가 생성돼야 한다(전제 #1) - 개인기준선(7건)은
		// 이미 확보돼 있으므로 원인 판정은 개인 기준선 기준이지만, 피부 변화는 has_previous_analysis=false.
		MvcResult firstReportResult = mockMvc.perform(get("/api/v1/reports/skin/latest").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.has_previous_analysis").value(false))
				.andExpect(jsonPath("$.primary_causes", org.hamcrest.Matchers.hasSize(3)))
				.andReturn();
		Long firstReportId = ((Number) JsonPath.read(firstReportResult.getResponse().getContentAsString(), "$.report_id")).longValue();
		verify(aiClient, times(1)).analyzeSkinChangeCauses(any(), any());

		// 1회차 루틴: 체크인 저장 시점이 아니라 방금 끝난 피부 분석(오늘 Report 생성) 직후 이미 만들어져 있어야 한다.
		MvcResult firstRoutineResult = mockMvc.perform(get("/api/v1/routines/today").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.total_count").value(org.hamcrest.Matchers.greaterThan(0)))
				.andReturn();
		Long routineId = ((Number) JsonPath.read(
				firstRoutineResult.getResponse().getContentAsString(), "$.data.routines[0].routine_id")).longValue();

		mockMvc.perform(patch("/api/v1/routines/" + routineId + "/check")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"is_completed\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.is_completed").value(true))
				.andExpect(jsonPath("$.data.completed_count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

		// ===== 4. 두 번째 분석(2회차): 피부타입 보정(DRY -> redness 구역 임계값 1) + 직전 분석 대비 변화 계산.
		// 이 시점에 두 번째 오늘 Report가 새로 생성되지만(SkinAnalysis마다 1건), 오늘 Routine은 이미 1회차에서
		// 만들어져 있으므로 재생성되지 않는다(멱등성) - 방금 체크한 완료 상태가 그대로 유지되는지로 검증한다 =====
		when(aiClient.analyzeSkin(any(), any())).thenReturn(observation(
				List.of(FaceRegion.CHEEK_LEFT), RednessIntensity.SEVERE, List.of(), null, goodQuality()));
		Long skinImageId2 = uploadSkinImage(token);
		Long skinAnalysisId2 = analyzeSkin(token, skinImageId2);

		mockMvc.perform(get("/api/v1/skin-analyses/" + skinAnalysisId2).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				// 건성(DRY) 피부는 구역 1개 + SEVERE만으로도 DANGER (기본 기준이면 CAUTION에 머물렀을 상황).
				.andExpect(jsonPath("$.data.redness").value("DANGER"))
				.andExpect(jsonPath("$.data.is_baseline").value(false))
				.andExpect(jsonPath("$.data.previous_skin_analysis_id").value(skinAnalysisId1))
				.andExpect(jsonPath("$.data.redness_change_status").value("WORSENED"))
				.andExpect(jsonPath("$.data.trouble_change_status").value("UNCHANGED"));

		// ===== 4-1. 이미지 품질 부족(재촬영 필요) vs AI 호출 자체 실패가 다른 ErrorCode로 구분되는지 확인.
		// 둘 다 SkinAnalysis 저장 이전에 막히므로 오늘 Report/Routine 생성에는 관여하지 않는다 =====
		when(aiClient.analyzeSkin(any(), any())).thenReturn(
				observation(List.of(), null, List.of(), null, poorQuality()));
		Long skinImageId3 = uploadSkinImage(token);
		mockMvc.perform(post("/api/v1/skin-analyses")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"skin_image_id\":" + skinImageId3 + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("SKIN_IMAGE_QUALITY_INSUFFICIENT"));

		when(aiClient.analyzeSkin(any(), any())).thenThrow(
				new com.sangmyungyaho.barocare.global.exception.GlobalException(
						com.sangmyungyaho.barocare.global.exception.ErrorCode.AI_ANALYSIS_FAILED));
		Long skinImageId4 = uploadSkinImage(token);
		mockMvc.perform(post("/api/v1/skin-analyses")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"skin_image_id\":" + skinImageId4 + "}"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("AI_ANALYSIS_FAILED"));

		// ===== 5. 원인 리포트 재조회: 2회차 분석으로 새 Report가 생겼으므로 "최신 저장된 리포트"는 이제
		// 2회차 것이다(분석 메인/상세 화면이 참조할 API들이 모두 순수 조회이고 추가 AI 호출이 없는지 확인) =====
		MvcResult reportResult = mockMvc.perform(get("/api/v1/reports/skin/latest").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.has_previous_analysis").value(true))
				.andExpect(jsonPath("$.primary_causes", org.hamcrest.Matchers.hasSize(3)))
				.andReturn();
		Long reportId = ((Number) JsonPath.read(reportResult.getResponse().getContentAsString(), "$.report_id")).longValue();
		assertThat(reportId).isNotEqualTo(firstReportId);
		// 2회차 분석 시점에 1번, 방금 GET에서는 0번 -> 총 2번(1회차 1 + 2회차 1). GET 자체는 추가 호출을 만들지 않는다.
		verify(aiClient, times(2)).analyzeSkinChangeCauses(any(), any());

		// 재조회해도 저장된 리포트를 그대로 재사용한다(추가 AI 호출 없음, 순수 조회).
		mockMvc.perform(get("/api/v1/reports/skin/latest").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.report_id").value(reportId));
		verify(aiClient, times(2)).analyzeSkinChangeCauses(any(), any());

		// 별칭 엔드포인트도 동일 리포트를 재사용(추가 AI 호출 없음).
		mockMvc.perform(get("/api/v1/reports/causes/latest").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.report_id").value(reportId));
		verify(aiClient, times(2)).analyzeSkinChangeCauses(any(), any());

		// 리포트 상세(분석 메인 -> 상세 진입 시 쓰이는 API)도 같은 값을 순수 조회로 돌려준다.
		mockMvc.perform(get("/api/v1/reports/" + reportId).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.report_id").value(reportId))
				.andExpect(jsonPath("$.primary_causes", org.hamcrest.Matchers.hasSize(3)));

		// 분석 메인(리포트 목록) - 날짜로 오늘자 리포트만 검색 가능해야 한다(2건: 1회차+2회차).
		mockMvc.perform(get("/api/v1/reports").header("Authorization", "Bearer " + token).param("date", today.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.reports.length()").value(2));

		mockMvc.perform(get("/api/v1/reports/causes/latest/warnings").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.warnings").isArray());

		mockMvc.perform(get("/api/v1/reports/causes/latest/skin-signal").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.redness.direction").value("INCREASED"));

		// ===== 6. 오늘의 루틴 재확인: 2회차 분석으로도 재생성되지 않아(멱등성) 1회차에서 체크한 완료 상태가
		// 그대로 유지되어야 한다(추가 AI 호출 없음) =====
		mockMvc.perform(get("/api/v1/routines/today").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.routines[?(@.routine_id == " + routineId + ")].is_completed").value(true));
		verify(aiClient, times(2)).analyzeSkinChangeCauses(any(), any());

		// ===== 7. 홈 대시보드: 위 데이터가 한 응답으로 조립되고, AI는 전혀 추가 호출되지 않는다 =====
		mockMvc.perform(get("/api/v1/home").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				// 홈의 "최근 7일"은 오늘 포함 7일(today-6~today) 창이다 - 위에서 미리 채운 8일치 체크인 중
				// today-7은 이 창 밖이므로 7건만 집계돼야 한다(HomeService.WEEKLY_CHECKIN_DAYS=7 기준 정상 동작).
				.andExpect(jsonPath("$.data.weekly_checkins.checked_count").value(7))
				.andExpect(jsonPath("$.data.latest_skin_analysis.skin_analysis_id").value(skinAnalysisId2))
				.andExpect(jsonPath("$.data.latest_report.report_id").value(reportId))
				.andExpect(jsonPath("$.data.today_routine.completed_count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
		// analyzeSkin 호출 총 4회: baseline 1 + 두 번째 분석 1 + 4-1의 품질부족/AI실패 시뮬레이션 2건
		// (실패한 2건도 AI 호출 자체는 발생했다가 이후 단계에서 막힌 것이므로 호출 횟수에는 포함된다).
		verify(aiClient, times(4)).analyzeSkin(any(), any());
		// analyzeSkinChangeCauses는 이제 GET이 아니라 피부 분석 성공 시점(1회차/2회차)마다 자동 호출되므로 2회다.
		verify(aiClient, times(2)).analyzeSkinChangeCauses(any(), any());

		// ===== 8. 다른 사용자는 이 데이터를 볼 수 없다 =====
		String otherToken = createUserAndToken(null);
		mockMvc.perform(get("/api/v1/skin-analyses/" + skinAnalysisId1).header("Authorization", "Bearer " + otherToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		mockMvc.perform(get("/api/v1/skin-analyses/" + skinAnalysisId1 + "/ingredients").header("Authorization", "Bearer " + otherToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		mockMvc.perform(get("/api/v1/skin-analyses/" + skinAnalysisId1 + "/products").header("Authorization", "Bearer " + otherToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		mockMvc.perform(get("/api/v1/checkins/today").header("Authorization", "Bearer " + otherToken))
				.andExpect(status().isNotFound()); // 본인 체크인이 없으므로 404(다른 사용자 체크인이 보이면 안 됨)
		mockMvc.perform(get("/api/v1/home").header("Authorization", "Bearer " + otherToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.latest_skin_analysis").isEmpty())
				.andExpect(jsonPath("$.data.latest_report").isEmpty());
	}

	@Test
	void 데이터가_전혀_없는_신규_사용자도_모든_조회_API가_500_없이_정상_응답한다() throws Exception {
		String token = createUserAndToken(null);

		mockMvc.perform(get("/api/v1/checkins/today").header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("TODAY_CHECKIN_NOT_FOUND"));

		mockMvc.perform(get("/api/v1/checkins")
						.header("Authorization", "Bearer " + token)
						.param("startDate", LocalDate.now().minusDays(7).toString())
						.param("endDate", LocalDate.now().toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").isArray())
				.andExpect(jsonPath("$.data").isEmpty());

		mockMvc.perform(get("/api/v1/skin-analyses/history").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.latest").doesNotExist())
				.andExpect(jsonPath("$.data.history").isArray())
				.andExpect(jsonPath("$.data.history").isEmpty());

		// 리포트 생성은 이제 GET에서 일어나지 않으므로(순수 조회), 저장된 리포트가 아예 없으면 REPORT_NOT_FOUND다.
		mockMvc.perform(get("/api/v1/reports/skin/latest").header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("REPORT_NOT_FOUND"));

		mockMvc.perform(get("/api/v1/routines/today").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.total_count").value(0))
				.andExpect(jsonPath("$.data.routines").isEmpty());

		mockMvc.perform(get("/api/v1/home").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.weekly_checkins.checked_count").value(0))
				.andExpect(jsonPath("$.data.latest_skin_analysis").isEmpty())
				.andExpect(jsonPath("$.data.latest_report").isEmpty())
				.andExpect(jsonPath("$.data.today_routine.routines").isEmpty());

		verify(aiClient, times(0)).analyzeSkin(any(), any());
		verify(aiClient, times(0)).analyzeSkinChangeCauses(any(), any());
	}

	@Test
	void 과거_체크인이_전혀_없어도_오늘_체크인과_첫_분석만으로_리포트와_루틴이_생성된다() throws Exception {
		// 요구사항 #1: 개인기준선(과거 체크인 7건)은 리포트 생성 조건이 아니라 판정 방식(고정 vs 개인)만
		// 바꾸는 조건이다. 과거 체크인 0건(고정 기준표) + SkinAnalysis 1건(비교 대상 없음)이어도 리포트/루틴이
		// 정상 생성돼야 한다("첫 분석부터 원인 리포트가 생성되어야 함").
		String token = createUserAndToken("건성");

		mockMvc.perform(post("/api/v1/checkins")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sleep_hours\":5.0,\"stress_level\":4,\"water_intake_ml\":700}"))
				.andExpect(status().isCreated());

		when(aiClient.analyzeSkin(any(), any())).thenReturn(observation(List.of(), null, List.of(), null, goodQuality()));
		when(aiClient.analyzeSkinChangeCauses(any(), any())).thenReturn(new AiDto.CauseAnalysisResult(
				List.of(new AiDto.Cause(ReportCauseFactor.SLEEP, "수면 부족", "수면 시간이 고정 기준보다 부족했어요.")),
				"수면 부족이 관찰됐어요."
		));

		Long skinImageId = uploadSkinImage(token);
		analyzeSkin(token, skinImageId);

		mockMvc.perform(get("/api/v1/reports/skin/latest").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.has_previous_analysis").value(false))
				.andExpect(jsonPath("$.skin_change.redness.previous_score").doesNotExist())
				.andExpect(jsonPath("$.skin_change.redness.status").doesNotExist())
				.andExpect(jsonPath("$.primary_causes", org.hamcrest.Matchers.hasSize(1)))
				.andExpect(jsonPath("$.primary_causes[0].factor").value("SLEEP"));

		mockMvc.perform(get("/api/v1/routines/today").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.total_count").value(org.hamcrest.Matchers.greaterThan(0)));

		verify(aiClient, times(1)).analyzeSkinChangeCauses(any(), any());
	}

	// ---------- 헬퍼 ----------

	private String createUserAndToken(String skinType) throws Exception {
		User user = User.builder().provider(Provider.KAKAO).socialId("e2e-" + UUID.randomUUID()).nickname("E2E유저").build();
		userRepository.save(user);
		String token = jwtProvider.createAccessToken(user.getId());
		if (skinType != null) {
			mockMvc.perform(patch("/api/v1/users/profile")
					.header("Authorization", "Bearer " + token)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"skin_type\":\"" + skinType + "\"}"));
		}
		return token;
	}

	private Long uploadSkinImage(String token) throws Exception {
		MockMultipartFile file = new MockMultipartFile("image", "face.jpg", "image/jpeg", "fake-image-bytes".getBytes());
		MvcResult result = mockMvc.perform(multipart("/api/v1/skin-images")
						.file(file)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isCreated())
				.andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.skin_image_id")).longValue();
	}

	private Long analyzeSkin(String token, Long skinImageId) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/skin-analyses")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"skin_image_id\":" + skinImageId + "}"))
				.andExpect(status().isCreated())
				.andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.skin_analysis_id")).longValue();
	}

	private AiDto.SkinAnalysisResult observation(
			List<FaceRegion> rednessRegions, RednessIntensity intensity,
			List<FaceRegion> troubleRegions, TroubleDensity density,
			AiDto.ImageQuality imageQuality
	) {
		return new AiDto.SkinAnalysisResult(
				new AiDto.RednessObservation(rednessRegions, intensity),
				new AiDto.TroubleObservation(troubleRegions, density),
				imageQuality
		);
	}

	private AiDto.ImageQuality goodQuality() {
		return new AiDto.ImageQuality(ImageQualityRating.GOOD, ImageQualityRating.GOOD, ImageQualityRating.GOOD, ImageQualityRating.GOOD);
	}

	private AiDto.ImageQuality poorQuality() {
		return new AiDto.ImageQuality(ImageQualityRating.POOR, ImageQualityRating.POOR, ImageQualityRating.GOOD, ImageQualityRating.GOOD);
	}
}
