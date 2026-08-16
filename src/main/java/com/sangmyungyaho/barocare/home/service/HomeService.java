package com.sangmyungyaho.barocare.home.service;

import com.sangmyungyaho.barocare.checkin.dto.CheckinDto;
import com.sangmyungyaho.barocare.checkin.service.CheckinService;
import com.sangmyungyaho.barocare.home.dto.HomeDto;
import com.sangmyungyaho.barocare.report.dto.ReportDto;
import com.sangmyungyaho.barocare.report.service.ReportService;
import com.sangmyungyaho.barocare.routine.dto.RoutineDto;
import com.sangmyungyaho.barocare.routine.service.RoutineService;
import com.sangmyungyaho.barocare.skin.dto.SkinAnalysisDto;
import com.sangmyungyaho.barocare.skin.service.SkinAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 홈 대시보드 통합 조회(프론트 화면 연동). 새로운 분석/계산 로직을 만들지 않고, 각 도메인(Checkin/
 * SkinAnalysis/Report/Routine)에 이미 구현된 "이미 저장된 데이터 조회" 로직만 조합한다.
 *
 * 중요: 이 서비스는 어떤 경로로도 OpenAI를 호출하지 않는다.
 * - ReportService.getLatestSavedReport()는 find-or-create를 하지 않는 전용 조회 메서드다
 *   (ReportService.getLatestSkinReport()/tryGetLatestSkinReport()는 저장된 리포트가 없으면 새로
 *   생성(OpenAI 호출)할 수 있으므로 홈 조회에서는 절대 사용하지 않는다).
 * - RoutineService.getTodayRoutines()는 이미 저장된 Routine만 읽고, 새로 생성하지 않는다.
 * - SkinAnalysisService.getLatestDetailForUser()는 새 분석을 실행하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class HomeService {

	// 주간 체크인 조회 기간(오늘 포함 7일).
	private static final int WEEKLY_CHECKIN_DAYS = 7;

	private final CheckinService checkinService;
	private final SkinAnalysisService skinAnalysisService;
	private final ReportService reportService;
	private final RoutineService routineService;

	/**
	 * 모든 하위 조회는 userId 기준으로만 수행되므로(각 도메인 Repository/Service가 이미 userId로
	 * 스코프됨), 다른 사용자의 데이터가 섞일 수 없다. 일부 또는 전부 데이터가 없는 사용자여도 예외를
	 * 던지지 않고 각 영역을 null/빈 값으로 채운 정상 응답을 반환한다.
	 */
	public HomeDto.DashboardResponse getDashboard(Long userId) {
		HomeDto.WeeklyCheckinSummary weeklyCheckins = buildWeeklyCheckinSummary(userId);
		SkinAnalysisDto.DetailResponse latestSkinAnalysis = skinAnalysisService.getLatestDetailForUser(userId).orElse(null);
		ReportDto.Response latestReport = reportService.getLatestSavedReport(userId).orElse(null);
		RoutineDto.RoutineResponseDto todayRoutine = routineService.getTodayRoutines(userId);

		return new HomeDto.DashboardResponse(weeklyCheckins, latestSkinAnalysis, latestReport, todayRoutine);
	}

	/**
	 * 최근 7일(오늘 포함) 체크인 현황. Phase 5(GET /api/v1/checkins?startDate=&endDate=)에서 구현한
	 * CheckinService.getCheckinsByDateRange()를 그대로 재사용해 조회한 뒤, 프론트가 요일별 상태를
	 * 표시할 수 있도록 체크인이 없는 날짜도 checked=false로 채워 7일 전체를 반환한다.
	 */
	private HomeDto.WeeklyCheckinSummary buildWeeklyCheckinSummary(Long userId) {
		LocalDate today = LocalDate.now();
		LocalDate startDate = today.minusDays(WEEKLY_CHECKIN_DAYS - 1L);

		List<CheckinDto.Response> checkins = checkinService.getCheckinsByDateRange(userId, startDate, today);
		Set<LocalDate> checkedDates = new HashSet<>();
		for (CheckinDto.Response checkin : checkins) {
			checkedDates.add(checkin.checkedDate());
		}

		List<HomeDto.WeeklyCheckinDay> days = new ArrayList<>();
		for (LocalDate date = startDate; !date.isAfter(today); date = date.plusDays(1)) {
			days.add(new HomeDto.WeeklyCheckinDay(date, checkedDates.contains(date)));
		}

		return new HomeDto.WeeklyCheckinSummary(days, checkedDates.size());
	}
}
