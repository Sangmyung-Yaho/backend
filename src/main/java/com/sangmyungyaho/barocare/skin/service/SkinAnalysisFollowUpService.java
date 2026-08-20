package com.sangmyungyaho.barocare.skin.service;

import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.report.entity.Report;
import com.sangmyungyaho.barocare.report.service.ReportService;
import com.sangmyungyaho.barocare.routine.service.RoutineService;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.user.entity.User;
import com.sangmyungyaho.barocare.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 오늘 원인 리포트 + 오늘의 루틴 생성. 원래 {@link SkinAnalysisService#analyzeSkin}의 private
 * 메서드였던 로직을 그대로 분리해둔 클래스다.
 *
 * 다시 동기(SkinAnalysisService.analyzeSkin()과 같은 스레드/요청)로 호출된다: "피부 분석 + 원인 분석 +
 * 오늘의 루틴까지 완료된 뒤에 응답한다"는 요구사항 때문에, 이 셋은 POST /skin-analyses 응답보다 먼저
 * 끝나야 한다(한때 이 클래스 전체를 @Async로 백그라운드 실행했었지만, 그러면 루틴 결과가 메인 응답에
 * 아직 반영되지 않은 채 나갈 수 있어 다시 동기로 되돌렸다 - 대신 이후 단계인 추천 성분/제품 생성만
 * IngredientRecommendationService에서 별도로 비동기 처리한다).
 *
 * 클래스를 분리해둔 채로 유지하는 이유는 순수하게 가독성/단일 책임 때문이다(SkinAnalysisService를
 * 이미지 로드·Vision 호출·검증 같은 본연의 책임에 집중시킨다).
 */
@Service
@RequiredArgsConstructor
public class SkinAnalysisFollowUpService {

	private static final Logger log = LoggerFactory.getLogger(SkinAnalysisFollowUpService.class);

	private final CheckinRepository checkinRepository;
	private final ReportService reportService;
	private final RoutineService routineService;
	private final UserRepository userRepository;

	/**
	 * 실제 사용자 플로우(Figma 기준): 체크인 저장 → 사진 업로드 → 피부 분석 → 오늘 원인 리포트 생성 →
	 * 오늘의 루틴 생성. 피부 분석이 방금 끝난 시점에만 오늘 Report/Routine을 생성한다(체크인 저장
	 * 시점에는 더 이상 생성하지 않는다 - 그때는 아직 오늘 피부 분석이 없어 직전 데이터를 쓰게 되는
	 * 문제가 있었다).
	 *
	 * 오늘 체크인이 아직 없으면(정상적인 플로우라면 체크인이 먼저 끝나 있어야 하지만, 순서를 어기고
	 * 사진부터 분석을 시도한 경우) 리포트/루틴을 만들 수 없으므로 건너뛴다 - 피부 분석 저장 자체는
	 * 이미 끝났으므로 이 응답에는 영향이 없다.
	 *
	 * 리포트/루틴 생성 중 예상치 못한 오류(OpenAI 실패 등)가 나도 피부 분석 저장 응답 자체는 항상
	 * 성공해야 하므로 예외를 흡수한다(CheckinService가 루틴 생성 실패를 흡수하던 것과 동일한 방어 철학).
	 */
	public void generateTodayReportAndRoutines(Long userId, SkinAnalysis todaySkinAnalysis) {
		Optional<Checkin> todayCheckin = checkinRepository.findByUserIdAndCheckedDate(userId, LocalDate.now());
		if (todayCheckin.isEmpty()) {
			log.info("오늘 체크인이 없어 오늘 리포트/루틴 생성을 건너뜀: userId={}, skinAnalysisId={}", userId, todaySkinAnalysis.getId());
			return;
		}

		try {
			Report report = reportService.generateTodayReport(userId, todaySkinAnalysis, todayCheckin.get());
			routineService.generateRoutines(userId, todayCheckin.get(), todaySkinAnalysis, report);

			// 하루 활동 플로우 완료(체크인 → 피부 분석 → 리포트 → 루틴) → 스트릭 갱신
			User user = userRepository.findById(userId)
					.orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));
			user.recordActivity(LocalDate.now());
		} catch (RuntimeException e) {
			log.warn("오늘 리포트/루틴 생성 실패(피부 분석 저장 응답에는 영향 없음): userId={}, skinAnalysisId={}",
					userId, todaySkinAnalysis.getId(), e);
		}
	}
}
