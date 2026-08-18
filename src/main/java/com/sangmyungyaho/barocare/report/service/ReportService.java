package com.sangmyungyaho.barocare.report.service;

import com.sangmyungyaho.barocare.ai.client.AiClient;
import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.report.dto.ReportDto;
import com.sangmyungyaho.barocare.report.entity.LifestyleFactorLevel;
import com.sangmyungyaho.barocare.report.entity.Report;
import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.report.entity.ReportChangeStatus;
import com.sangmyungyaho.barocare.report.repository.ReportRepository;
import com.sangmyungyaho.barocare.skin.entity.ChangeDirection;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.skin.entity.SkinComparison;
import com.sangmyungyaho.barocare.skin.repository.SkinAnalysisRepository;
import com.sangmyungyaho.barocare.skin.repository.SkinComparisonRepository;
import com.sangmyungyaho.barocare.user.entity.User;
import com.sangmyungyaho.barocare.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 피부 변화 원인 분석 리포트.
 *
 * 생성과 조회의 책임을 분리한다:
 * - 생성은 {@link #generateTodayReport}만 담당하며, 오직
 *   {@link com.sangmyungyaho.barocare.skin.service.SkinAnalysisService#analyzeSkin}(피부 분석 완료 시점)에서만
 *   호출된다. GET 계열 API는 이 메서드를 호출하지 않으므로 조회만으로는 OpenAI가 호출되거나 Report가 새로
 *   생성되지 않는다("조회와 생성의 책임 분리").
 * - 조회({@link #getLatestSkinReport}, {@link #getLatestSavedReport}, {@link #getReports}, {@link #getReport} 등)는
 *   전부 이미 저장된 Report만 읽는다(find-or-create 없음).
 *
 * redness/trouble의 score(SkinAnalysisLevel ordinal)와 status(IMPROVED/WORSENED/UNCHANGED)는
 * 둘 다 이 서비스가 change 값 하나로 계산한다 - AI(SkinComparisonService의 이미지 비교 판단)에 기대지
 * 않으므로 score와 status가 서로 다른 기준으로 어긋날 수 없다. 사용자의 첫 피부 분석처럼 비교할 이전
 * 분석이 없으면 previousScore/status는 null이다(ReportDto.Response.hasPreviousAnalysis=false로 노출).
 *
 * 생활습관 요인(수면/스트레스/수분)도 마찬가지로 LifestyleFactorRubric이 먼저 GOOD/MODERATE/POOR로
 * 판정하고, POOR인 요인만 "주요 위험 요인 후보"로 확정해 GPT에 전달한다. 이 판정은 체크인 이력만으로
 * 이루어지므로 피부 분석이 몇 회차인지와 무관하게(첫 분석이어도) 항상 계산 가능하다. GPT(AiClient)는 이
 * 후보에 대한 원인 설명/자연어 요약 생성만 담당하며, 후보 밖 요인을 causes에 넣어 반환하더라도 이
 * 서비스가 candidateFactors 기준으로 다시 걸러내 최종 응답에는 절대 남지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

	private static final Logger log = LoggerFactory.getLogger(ReportService.class);

	// 분석 메인 화면(GET /api/v1/reports) - date/startDate/endDate가 전부 없을 때 기본으로 조회하는 기간.
	private static final int DEFAULT_REPORT_LIST_DAYS = 30;

	private final SkinAnalysisRepository skinAnalysisRepository;
	private final CheckinRepository checkinRepository;
	private final ReportRepository reportRepository;
	private final AiClient aiClient;
	private final ObjectMapper objectMapper;
	private final CauseCombinationRubric causeCombinationRubric;
	private final SkinComparisonRepository skinComparisonRepository;
	private final UserRepository userRepository;
	private final LifestyleFactorRubric lifestyleFactorRubric;

	// currentSkinAnalysisId 기준 동시 생성 방지용 락(단일 인스턴스 기준). DB unique 제약이 최종 안전장치이므로
	// 여기서는 "같은 순간에 들어온 요청이 OpenAI를 중복 호출하지 않도록" 최소화하는 목적만 가진다.
	private final Map<Long, Object> reportCreationLocks = new ConcurrentHashMap<>();

	/**
	 * 오늘 원인 리포트 생성(유일한 생성 경로).
	 *
	 * {@link com.sangmyungyaho.barocare.skin.service.SkinAnalysisService#analyzeSkin}이 오늘 SkinAnalysis를
	 * 저장한 직후에만 호출한다 - "오늘 Checkin + 오늘 SkinAnalysis + 직전 SkinAnalysis(있는 경우) + 과거
	 * Checkin baseline"을 조합해 리포트를 만든다. 직전 SkinAnalysis가 없으면(사용자의 첫 분석) 피부 변화
	 * 비교값은 모두 null로 채우고, 원인 판정(체크인 기반)은 그대로 진행한다 - 오류로 취급하지 않는다.
	 *
	 * 같은 todaySkinAnalysis에 대해 이미 리포트가 있으면(currentSkinAnalysis 유니크 제약) 재사용하고
	 * OpenAI를 재호출하지 않는다.
	 *
	 * @throws GlobalException AI_ANALYSIS_FAILED - OpenAI 호출/응답 검증 실패 시
	 */
	public Report generateTodayReport(Long userId, SkinAnalysis todaySkinAnalysis, Checkin todayCheckin) {
		Object lock = reportCreationLocks.computeIfAbsent(todaySkinAnalysis.getId(), id -> new Object());
		synchronized (lock) {
			Optional<Report> existing = reportRepository.findByCurrentSkinAnalysis_Id(todaySkinAnalysis.getId());
			if (existing.isPresent()) {
				log.info("이미 생성된 오늘 리포트를 재사용(OpenAI 재호출 없음): reportId={}, skinAnalysisId={}",
						existing.get().getId(), todaySkinAnalysis.getId());
				return existing.get();
			}

			Optional<SkinAnalysis> previousOpt = skinAnalysisRepository
					.findTopByUserIdAndAnalyzedAtLessThanOrderByAnalyzedAtDesc(userId, todaySkinAnalysis.getAnalyzedAt());

			int rednessCurrentScore = todaySkinAnalysis.getRednessLevel().ordinal();
			int troubleCurrentScore = todaySkinAnalysis.getTroubleLevel().ordinal();

			Integer rednessPreviousScore = null;
			Integer troublePreviousScore = null;
			ReportChangeStatus rednessStatus = null;
			ReportChangeStatus troubleStatus = null;
			ChangeDirection rednessDirection = null;
			ChangeDirection troubleDirection = null;
			Integer rednessChange = null;
			Integer troubleChange = null;
			boolean comparedAgainstBaseline = false;
			SkinAnalysisLevel baselineRednessLevel = null;
			SkinAnalysisLevel baselineTroubleLevel = null;

			if (previousOpt.isPresent()) {
				SkinAnalysis previous = previousOpt.get();
				rednessPreviousScore = previous.getRednessLevel().ordinal();
				troublePreviousScore = previous.getTroubleLevel().ordinal();
				rednessChange = rednessCurrentScore - rednessPreviousScore;
				troubleChange = troubleCurrentScore - troublePreviousScore;
				rednessStatus = toReportChangeStatus(rednessChange);
				troubleStatus = toReportChangeStatus(troubleChange);

				log.info("리포트 점수 계산: redness {}→{}(change={}, {}), trouble {}→{}(change={}, {})",
						rednessPreviousScore, rednessCurrentScore, rednessChange, rednessStatus,
						troublePreviousScore, troubleCurrentScore, troubleChange, troubleStatus);

				// 피부 변화 비교: 직전 분석 대비 증가/유지/감소. 이미 계산된 SkinComparison(AI 이미지 비교,
				// POST /api/v1/skin-comparisons로 별도 생성됨)이 있으면 재사용하고, 없으면 위에서 계산한
				// status로부터 결정적으로 유도한다(GPT 재호출 없음).
				ReportChangeStatus finalRednessStatus = rednessStatus;
				ReportChangeStatus finalTroubleStatus = troubleStatus;
				Optional<SkinComparison> comparison = skinComparisonRepository
						.findByCurrentSkinAnalysis_IdAndPreviousSkinAnalysis_Id(todaySkinAnalysis.getId(), previous.getId());
				rednessDirection = comparison.map(SkinComparison::getRednessChange)
						.orElseGet(() -> toChangeDirection(finalRednessStatus));
				troubleDirection = comparison.map(SkinComparison::getTroubleChange)
						.orElseGet(() -> toChangeDirection(finalTroubleStatus));

				// baseline(최초 분석) 참조: previous가 곧 baseline이면(사용자의 두 번째 분석) true.
				Optional<SkinAnalysis> baseline = skinAnalysisRepository.findFirstByUserIdOrderByAnalyzedAtAsc(userId);
				comparedAgainstBaseline = baseline.map(SkinAnalysis::getId).map(id -> id.equals(previous.getId())).orElse(false);
				if (!comparedAgainstBaseline) {
					baselineRednessLevel = baseline.map(SkinAnalysis::getRednessLevel).orElse(null);
					baselineTroubleLevel = baseline.map(SkinAnalysis::getTroubleLevel).orElse(null);
				}
			} else {
				log.info("직전 SkinAnalysis가 없어(첫 피부 분석) 변화 비교 없이 리포트를 생성함: userId={}, skinAnalysisId={}",
						userId, todaySkinAnalysis.getId());
			}

			LocalDate referenceDate = todaySkinAnalysis.getAnalyzedAt().toLocalDate();
			List<Checkin> previousCheckins = checkinRepository
					.findAllByUserIdAndCheckedDateLessThanOrderByCheckedDateDesc(userId, referenceDate);

			// 목표 음수량 반영: 온보딩/프로필에서 계산·저장된 User.waterGoalMl을 조회해 수분 판정에 사용한다.
			Integer waterGoalMl = userRepository.findById(userId).map(User::getWaterGoalMl).orElse(null);
			// 생활습관 요인판정: GPT에 raw 수치만 넘기지 않고, 먼저 백엔드가 GOOD/MODERATE/POOR로 판정한다.
			// 과거 체크인이 7건 미만이면 고정 기준표, 7건 이상이면 개인 기준선을 쓴다(LifestyleFactorRubric 내부 판단).
			LifestyleFactorRubric.Judgment judgment = lifestyleFactorRubric.judge(todayCheckin, previousCheckins, waterGoalMl);
			// 주요 위험 요인 후보: POOR로 판정된 요인만 백엔드가 후보로 확정한다. GPT는 causes를
			// 이 후보에 대해서만 작성해야 하고, 아래에서 이 목록 기준으로 한 번 더 걸러 코드 레벨로 강제한다.
			List<ReportCauseFactor> candidateFactors = resolveCandidateFactors(judgment);

			AiDto.SkinChangeInput skinChangeInput = new AiDto.SkinChangeInput(
					rednessChange, rednessStatus, rednessDirection,
					troubleChange, troubleStatus, troubleDirection,
					comparedAgainstBaseline, baselineRednessLevel, baselineTroubleLevel
			);
			AiDto.CheckinInput checkinInput = buildCheckinInput(todayCheckin, previousCheckins, judgment, candidateFactors);

			log.info("OpenAI 원인 분석 요청 시작: skinAnalysisId={}, candidateFactors={}", todaySkinAnalysis.getId(), candidateFactors);
			AiDto.CauseAnalysisResult causeAnalysisResult = aiClient.analyzeSkinChangeCauses(skinChangeInput, checkinInput);
			validateCauseAnalysisResult(causeAnalysisResult);

			List<ReportDto.PrimaryCause> primaryCauses = causeAnalysisResult.causes().stream()
					.filter(cause -> {
						boolean isCandidate = candidateFactors.contains(cause.factor());
						if (!isCandidate) {
							log.warn("AI가 후보 목록 밖의 요인을 반환해 제외함: factor={}, candidateFactors={}",
									cause.factor(), candidateFactors);
						}
						return isCandidate;
					})
					.map(cause -> enrichCause(cause, todayCheckin, judgment))
					.toList();

			Report report = new Report(
					todaySkinAnalysis, previousOpt.orElse(null), referenceDate,
					rednessPreviousScore, rednessCurrentScore, rednessStatus,
					troublePreviousScore, troubleCurrentScore, troubleStatus,
					toJson(primaryCauses), causeAnalysisResult.summary()
			);

			try {
				Report saved = reportRepository.save(report);
				log.info("리포트 저장 완료: reportId={}, skinAnalysisId={}", saved.getId(), todaySkinAnalysis.getId());
				return saved;
			} catch (DataIntegrityViolationException e) {
				// 락으로 막지 못한 경합(예: 다중 인스턴스 배포)에 대한 최종 안전장치.
				log.warn("리포트 저장 중 unique 제약 충돌 - 동시 요청으로 이미 생성된 리포트를 재사용: skinAnalysisId={}", todaySkinAnalysis.getId());
				return reportRepository.findByCurrentSkinAnalysis_Id(todaySkinAnalysis.getId()).orElseThrow(() -> e);
			}
		}
	}

	/**
	 * 최신 원인 리포트 조회(순수 조회, OpenAI 호출 없음). 저장된 리포트가 없으면 REPORT_NOT_FOUND를 던진다.
	 * 리포트 생성은 오직 {@link #generateTodayReport}(피부 분석 완료 시점)에서만 일어난다.
	 */
	public ReportDto.Response getLatestSkinReport(Long userId) {
		return getLatestSavedReport(userId)
				.orElseThrow(() -> new GlobalException(ErrorCode.REPORT_NOT_FOUND));
	}

	/**
	 * 원인 리포트가 아직 없는 상황(피부 분석/체크인 이력 부족 등)을 예외로 전파하지 않고 빈 값으로
	 * 돌려준다. RoutineService 등 "있으면 참고하고, 없으면 폴백"해야 하는 호출부가 안전하게 쓸 수 있다.
	 */
	public Optional<ReportDto.Response> tryGetLatestSkinReport(Long userId) {
		try {
			return Optional.of(getLatestSkinReport(userId));
		} catch (GlobalException e) {
			log.info("원인 리포트를 아직 조회할 수 없어 폴백 처리: userId={}, errorCode={}", userId, e.getErrorCode());
			return Optional.empty();
		}
	}

	/**
	 * 홈 대시보드 통합 조회 전용. getLatestSkinReport()와 동일하게 순수 조회이며 새로 계산/저장하지 않는다.
	 */
	public Optional<ReportDto.Response> getLatestSavedReport(Long userId) {
		return reportRepository.findTopByCurrentSkinAnalysis_UserIdOrderByReportDateDescIdDesc(userId)
				.map(report -> ReportDto.Response.of(report, parsePrimaryCauses(report.getPrimaryCausesJson())));
	}

	/**
	 * 리포트 보관함 목록(분석 메인 화면, GET /api/v1/reports). 항상 로그인 사용자(userId) 범위로만
	 * 조회하므로 다른 사용자의 리포트가 섞일 수 없다.
	 *
	 * - date가 주어지면 해당 날짜의 리포트만 반환한다(startDate/endDate는 무시).
	 * - date가 없으면 startDate~endDate(둘 다 기본값 있음) 기간의 리포트를 반환한다:
	 *   endDate 생략 시 오늘, startDate 생략 시 "그 endDate 기준 최근 30일"(= endDate - 29일)을 기본값으로 쓴다.
	 *   즉 아무 파라미터도 없으면 "최근 30일(오늘 포함)"이 기본 동작이다.
	 *
	 * 최신순(reportDate desc, 동일 날짜면 id desc)으로 반환하며, 결과가 없으면 빈 배열이다. 순수 조회.
	 */
	public ReportDto.ListResponse getReports(Long userId, LocalDate date, LocalDate startDate, LocalDate endDate) {
		List<Report> reports;
		if (date != null) {
			reports = reportRepository.findAllByCurrentSkinAnalysis_UserIdAndReportDateOrderByIdDesc(userId, date);
		} else {
			LocalDate rangeEnd = endDate != null ? endDate : LocalDate.now();
			LocalDate rangeStart = startDate != null ? startDate : rangeEnd.minusDays(DEFAULT_REPORT_LIST_DAYS - 1L);
			reports = reportRepository
					.findAllByCurrentSkinAnalysis_UserIdAndReportDateBetweenOrderByReportDateDescIdDesc(userId, rangeStart, rangeEnd);
		}
		List<ReportDto.ReportListItem> items = reports.stream()
				.map(ReportDto.ReportListItem::from)
				.toList();
		return new ReportDto.ListResponse(items);
	}

	/**
	 * 리포트 상세 조회(GET /api/v1/reports/{reportId}). 요청한 사용자가 이 리포트(의 currentSkinAnalysis)의
	 * 소유자가 아니면 FORBIDDEN을 던진다 - reportId를 순차 대입해 다른 사용자의 리포트를 열람하지 못하게 한다.
	 */
	public ReportDto.Response getReport(Long userId, Long reportId) {
		Report report = reportRepository.findById(reportId)
				.orElseThrow(() -> new GlobalException(ErrorCode.REPORT_NOT_FOUND));
		if (!report.getCurrentSkinAnalysis().getUserId().equals(userId)) {
			log.warn("리포트 상세 조회 거부: 다른 사용자의 리포트 - reportId={}, ownerUserId={}, requestUserId={}",
					reportId, report.getCurrentSkinAnalysis().getUserId(), userId);
			throw new GlobalException(ErrorCode.FORBIDDEN);
		}
		return ReportDto.Response.of(report, parsePrimaryCauses(report.getPrimaryCausesJson()));
	}

	/**
	 * 고위험 조합 경고(GET /api/v1/reports/causes/latest/warnings). 최신 원인 리포트를 새로 계산하지
	 * 않고 순수 조회 결과의 primaryCauses를 재사용한다.
	 */
	public ReportDto.WarningsResponse getLatestCauseWarnings(Long userId) {
		ReportDto.Response latestReport = getLatestSkinReport(userId);
		List<ReportDto.Warning> warnings = causeCombinationRubric.evaluate(latestReport.primaryCauses());
		return new ReportDto.WarningsResponse(warnings);
	}

	/**
	 * 원인 요인 상호작용 설명(GET /api/v1/reports/causes/latest/interactions).
	 */
	public ReportDto.InteractionsResponse getLatestCauseInteractions(Long userId) {
		ReportDto.Response latestReport = getLatestSkinReport(userId);
		List<ReportDto.Interaction> interactions = causeCombinationRubric.interactions(latestReport.primaryCauses());
		return new ReportDto.InteractionsResponse(interactions);
	}

	/**
	 * 피부 컨디션 신호 카드(GET /api/v1/reports/causes/latest/skin-signal).
	 *
	 * fallback 우선순위(신규 Vision/OpenAI 호출 및 SkinComparison 신규 생성 금지):
	 * 1) 최신 리포트가 참조하는 (current, previous) SkinAnalysis 쌍에 대해 이미 계산된 SkinComparison이
	 *    있으면 그 ChangeDirection(AI 이미지 비교 판단)을 그대로 사용한다.
	 * 2) 없으면 Report에 이미 저장된 redness/trouble의 ReportChangeStatus를 ChangeDirection으로 매핑해
	 *    대체 신호로 사용한다.
	 * 3) previous가 아예 없으면(첫 피부 분석) 비교 자체가 불가능하므로 STABLE + 안내 문구를 반환한다.
	 */
	public ReportDto.SkinSignalResponse getLatestSkinSignal(Long userId) {
		Report report = reportRepository.findTopByCurrentSkinAnalysis_UserIdOrderByReportDateDescIdDesc(userId)
				.orElseThrow(() -> new GlobalException(ErrorCode.REPORT_NOT_FOUND));

		if (report.getPreviousSkinAnalysis() == null) {
			String message = "첫 피부 분석이라 아직 비교할 데이터가 없어요.";
			ReportDto.SkinSignalItem redness = new ReportDto.SkinSignalItem(ChangeDirection.STABLE, message);
			ReportDto.SkinSignalItem trouble = new ReportDto.SkinSignalItem(ChangeDirection.STABLE, message);
			return new ReportDto.SkinSignalResponse(redness, trouble);
		}

		Long currentSkinAnalysisId = report.getCurrentSkinAnalysis().getId();
		Long previousSkinAnalysisId = report.getPreviousSkinAnalysis().getId();

		Optional<SkinComparison> comparison = skinComparisonRepository
				.findByCurrentSkinAnalysis_IdAndPreviousSkinAnalysis_Id(currentSkinAnalysisId, previousSkinAnalysisId);

		if (comparison.isPresent()) {
			log.info("skin-signal: 기존 SkinComparison 재사용(AI Vision 재호출 없음): skinComparisonId={}", comparison.get().getId());
		} else {
			log.info("skin-signal: SkinComparison 없음 - Report의 등급 변화(status)를 대체 신호로 사용: reportId={}", report.getId());
		}

		ChangeDirection rednessDirection = comparison.map(SkinComparison::getRednessChange)
				.orElseGet(() -> toChangeDirection(report.getRednessStatus()));
		ChangeDirection troubleDirection = comparison.map(SkinComparison::getTroubleChange)
				.orElseGet(() -> toChangeDirection(report.getTroubleStatus()));

		ReportDto.SkinSignalItem redness = new ReportDto.SkinSignalItem(rednessDirection, rednessSignalMessage(rednessDirection));
		ReportDto.SkinSignalItem trouble = new ReportDto.SkinSignalItem(troubleDirection, troubleSignalMessage(troubleDirection));
		return new ReportDto.SkinSignalResponse(redness, trouble);
	}

	/**
	 * 루틴 생성(RoutineService)이 방금 만들어진 오늘 Report의 primaryCauses를 재조회 없이 그대로 반영할 수
	 * 있도록, 이미 저장된 Report 엔티티에서 원인 요인 목록만 뽑아준다. 새로운 조회/AI 호출을 하지 않는다.
	 */
	public List<ReportCauseFactor> getPrimaryCauseFactors(Report report) {
		return parsePrimaryCauses(report.getPrimaryCausesJson()).stream()
				.map(ReportDto.PrimaryCause::factor)
				.toList();
	}

	private AiDto.CheckinInput buildCheckinInput(
			Checkin latestCheckin, List<Checkin> previousCheckins,
			LifestyleFactorRubric.Judgment judgment, List<ReportCauseFactor> candidateFactors
	) {
		Double averageSleepHours = previousCheckins.isEmpty() ? null
				: previousCheckins.stream().mapToDouble(Checkin::getSleepHours).average().orElseThrow();
		Double averageStressLevel = previousCheckins.isEmpty() ? null
				: previousCheckins.stream().mapToInt(Checkin::getStressLevel).average().orElseThrow();
		Double averageWaterIntakeMl = previousCheckins.isEmpty() ? null
				: previousCheckins.stream().mapToInt(Checkin::getWaterIntakeMl).average().orElseThrow();

		return new AiDto.CheckinInput(
				latestCheckin.getSleepHours(), latestCheckin.getStressLevel(), latestCheckin.getWaterIntakeMl(),
				averageSleepHours, averageStressLevel, averageWaterIntakeMl,
				judgment.sleepLevel(), judgment.stressLevel(), judgment.waterLevel(), judgment.personalBaselineUsed(),
				candidateFactors
		);
	}

	// 주요 위험 요인 후보: 생활습관 요인 중 POOR로 판정된 것만 "원인 후보"로 확정한다.
	// GOOD/MODERATE는 문제로 보지 않으므로 후보에 넣지 않는다 - GPT가 정상 요인을 임의로 원인으로
	// 선정하지 못하도록 하는 첫 번째 방어선이며, 두 번째 방어선은 generateTodayReport()의 causes 필터링이다.
	private List<ReportCauseFactor> resolveCandidateFactors(LifestyleFactorRubric.Judgment judgment) {
		List<ReportCauseFactor> candidates = new ArrayList<>();
		if (judgment.sleepLevel() == LifestyleFactorLevel.POOR) {
			candidates.add(ReportCauseFactor.SLEEP);
		}
		if (judgment.stressLevel() == LifestyleFactorLevel.POOR) {
			candidates.add(ReportCauseFactor.STRESS);
		}
		if (judgment.waterLevel() == LifestyleFactorLevel.POOR) {
			candidates.add(ReportCauseFactor.WATER_INTAKE);
		}
		return candidates;
	}

	// score(SkinAnalysisLevel ordinal) 변화량만으로 status를 결정한다. ordinal이 낮을수록(SAFE에 가까울수록)
	// 좋은 상태이므로 change < 0(등급이 낮아짐)은 IMPROVED, change > 0은 WORSENED다.
	private ReportChangeStatus toReportChangeStatus(int change) {
		if (change < 0) {
			return ReportChangeStatus.IMPROVED;
		}
		if (change > 0) {
			return ReportChangeStatus.WORSENED;
		}
		return ReportChangeStatus.UNCHANGED;
	}

	// skin-signal에서 SkinComparison이 없을 때의 대체 신호 변환.
	private ChangeDirection toChangeDirection(ReportChangeStatus status) {
		return switch (status) {
			case IMPROVED -> ChangeDirection.DECREASED;
			case UNCHANGED -> ChangeDirection.STABLE;
			case WORSENED -> ChangeDirection.INCREASED;
		};
	}

	private String rednessSignalMessage(ChangeDirection direction) {
		return switch (direction) {
			case DECREASED -> "이전보다 붉은기가 감소했어요.";
			case STABLE -> "붉은기가 이전과 비슷한 상태예요.";
			case INCREASED -> "이전보다 붉은기가 증가했어요.";
		};
	}

	private String troubleSignalMessage(ChangeDirection direction) {
		return switch (direction) {
			case DECREASED -> "이전보다 트러블이 감소했어요.";
			case STABLE -> "트러블이 이전과 비슷한 상태예요.";
			case INCREASED -> "이전보다 트러블이 증가했어요.";
		};
	}

	// 기준값/차이/기준종류는 LifestyleFactorRubric이 이미 계산해둔 FactorJudgment를 그대로 옮겨 담을 뿐,
	// 여기서 새로 계산하지 않는다("평균 수면 5.4h / 현재 7h / 차이 +1.6h" 근거를 API로 노출하기 위함).
	private ReportDto.PrimaryCause enrichCause(AiDto.Cause cause, Checkin latestCheckin, LifestyleFactorRubric.Judgment judgment) {
		return switch (cause.factor()) {
			case SLEEP -> new ReportDto.PrimaryCause(
					cause.factor(), cause.name(), latestCheckin.getSleepHours(), "시간", cause.description(),
					judgment.sleep().baselineValue(), judgment.sleep().difference(), judgment.sleep().baselineType());
			case STRESS -> new ReportDto.PrimaryCause(
					cause.factor(), cause.name(), latestCheckin.getStressLevel().doubleValue(), "5단계", cause.description(),
					judgment.stress().baselineValue(), judgment.stress().difference(), judgment.stress().baselineType());
			case WATER_INTAKE -> new ReportDto.PrimaryCause(
					cause.factor(), cause.name(), latestCheckin.getWaterIntakeMl().doubleValue(), "ml", cause.description(),
					judgment.water().baselineValue(), judgment.water().difference(), judgment.water().baselineType());
		};
	}

	// causes 목록 자체뿐 아니라 원소 각각의 필수 필드까지 검증한다. factor가 null이면(구조화 출력이 스키마를
	// 어기고 필드를 비운 경우) enrichCause의 switch에서 NPE가 나므로, 여기서 먼저 걸러 AI_ANALYSIS_FAILED로
	// 응답한다. factor는 ReportCauseFactor enum이라 파싱에 성공한 이상 null이 아니면 항상 유효한 값이다.
	private void validateCauseAnalysisResult(AiDto.CauseAnalysisResult result) {
		if (result == null || result.causes() == null || result.summary() == null || result.summary().isBlank()) {
			log.warn("AI 원인 분석 결과 검증 실패: causes/summary 누락 - {}", result);
			throw new GlobalException(ErrorCode.AI_ANALYSIS_FAILED);
		}
		for (AiDto.Cause cause : result.causes()) {
			boolean invalid = cause == null
					|| cause.factor() == null
					|| cause.name() == null || cause.name().isBlank()
					|| cause.description() == null || cause.description().isBlank();
			if (invalid) {
				log.warn("AI 원인 분석 결과 검증 실패: cause 필드 누락 - {}", cause);
				throw new GlobalException(ErrorCode.AI_ANALYSIS_FAILED);
			}
		}
	}

	private String toJson(List<ReportDto.PrimaryCause> primaryCauses) {
		try {
			return objectMapper.writeValueAsString(primaryCauses);
		} catch (JacksonException e) {
			throw new IllegalStateException("primary_causes 직렬화에 실패했습니다.", e);
		}
	}

	private List<ReportDto.PrimaryCause> parsePrimaryCauses(String primaryCausesJson) {
		try {
			return objectMapper.readValue(primaryCausesJson, new TypeReference<List<ReportDto.PrimaryCause>>() {
			});
		} catch (JacksonException e) {
			throw new IllegalStateException("primary_causes 역직렬화에 실패했습니다.", e);
		}
	}
}
