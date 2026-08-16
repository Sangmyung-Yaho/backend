package com.sangmyungyaho.barocare.report.service;

import com.sangmyungyaho.barocare.ai.client.AiClient;
import com.sangmyungyaho.barocare.ai.dto.AiDto;
import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import com.sangmyungyaho.barocare.report.dto.ReportDto;
import com.sangmyungyaho.barocare.report.entity.Report;
import com.sangmyungyaho.barocare.report.entity.ReportChangeStatus;
import com.sangmyungyaho.barocare.report.repository.ReportRepository;
import com.sangmyungyaho.barocare.skin.entity.ChangeDirection;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinComparison;
import com.sangmyungyaho.barocare.skin.repository.SkinAnalysisRepository;
import com.sangmyungyaho.barocare.skin.repository.SkinComparisonRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 피부 변화 원인 분석 리포트(REP-101, GET /api/v1/reports/skin/latest 및 ISSUE-28 파생 API들).
 *
 * 최신 SkinAnalysis를 기준으로 find-or-create 방식으로 동작한다: 이미 해당 SkinAnalysis로 생성된
 * Report가 있으면 재사용하고, 없을 때만 AiClient(원인 해석)를 호출해 새로 계산/저장한다. 따라서 같은
 * 최신 SkinAnalysis에 대한 반복 GET은 OpenAI를 다시 호출하지 않는다.
 *
 * redness/trouble의 score(SkinAnalysisLevel ordinal)와 status(IMPROVED/WORSENED/UNCHANGED)는
 * 둘 다 이 서비스가 change 값 하나로 계산한다 - AI(SkinComparisonService의 이미지 비교 판단)에 기대지
 * 않으므로 score와 status가 서로 다른 기준으로 어긋날 수 없다. AI(AiClient)는 원인 후보 해석과
 * 자연어 설명/요약 생성에만 사용한다.
 *
 * ISSUE-28(복합 원인 분석 및 피부 변화 설명): getLatestSkinReport()가 내부적으로 의존하는
 * find-or-create 로직(getOrCreateLatestReport)을 공유해, 여러 API가 같은 최신 Report를 새로
 * 계산하지 않고 재사용하도록 한다. 이번 이슈에서는 userId 기반 사용자별 데이터 분리를 다루지
 * 않는다(기존 Checkin/SkinAnalysis와 동일하게 전역 최신값 기준으로 조회) - 관련 TODO는
 * Checkin/SkinAnalysis 엔티티에 그대로 남겨둔다.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

	private static final Logger log = LoggerFactory.getLogger(ReportService.class);

	private final SkinAnalysisRepository skinAnalysisRepository;
	private final CheckinRepository checkinRepository;
	private final ReportRepository reportRepository;
	private final AiClient aiClient;
	private final ObjectMapper objectMapper;
	private final CauseCombinationRubric causeCombinationRubric;
	private final SkinComparisonRepository skinComparisonRepository;

	// currentSkinAnalysisId 기준 동시 생성 방지용 락(단일 인스턴스 기준). DB unique 제약이 최종 안전장치이므로
	// 여기서는 "같은 순간에 들어온 요청이 OpenAI를 중복 호출하지 않도록" 최소화하는 목적만 가진다.
	private final Map<Long, Object> reportCreationLocks = new ConcurrentHashMap<>();

	public ReportDto.Response getLatestSkinReport(Long userId) {
		Report report = getOrCreateLatestReport(userId);
		return ReportDto.Response.of(report, parsePrimaryCauses(report.getPrimaryCausesJson()));
	}

	/**
	 * 리포트 보관함 목록(ISSUE-29, GET /api/v1/reports).
	 *
	 * 이미 DB에 생성되어 있는 Report만 조회한다 - find-or-create를 수행하는 getLatestSkinReport()와
	 * 달리 새로운 분석/저장을 유발하지 않으므로 OpenAI를 호출하지 않는다. date가 주어지면 해당 날짜의
	 * 리포트만, 없으면 전체 리포트를 최신순(reportDate desc, 동일 날짜면 id desc)으로 반환한다.
	 * 결과가 없으면 에러 없이 빈 목록을 반환한다.
	 *
	 * 이번 이슈 범위에서는 userId 기반 사용자별 분리를 다루지 않는다(기존 Report/Checkin/SkinAnalysis와
	 * 동일하게 전역 데이터 기준) - 관련 TODO는 ReportRepository에 남겨둔다.
	 */
	public ReportDto.ListResponse getReports(Long userId, LocalDate date) {
		List<Report> reports = date != null
				? reportRepository.findByReportDateOrderByIdDesc(date) // userId 조건 필요시 추가 가능
				: reportRepository.findAllByOrderByReportDateDescIdDesc();
		List<ReportDto.ReportListItem> items = reports.stream()
				.map(ReportDto.ReportListItem::from)
				.toList();
		return new ReportDto.ListResponse(items);
	}

	/**
	 * 리포트 상세 조회(ISSUE-29, GET /api/v1/reports/{reportId}).
	 *
	 * 기존 getLatestSkinReport()가 쓰는 것과 동일한 ReportDto.Response/parsePrimaryCauses를 그대로
	 * 재사용한다 - id로 조회한다는 점만 다르고 새로운 분석/저장은 일으키지 않는다.
	 */
	public ReportDto.Response getReport(Long reportId) {
		Report report = reportRepository.findById(reportId)
				.orElseThrow(() -> new GlobalException(ErrorCode.REPORT_NOT_FOUND));
		return ReportDto.Response.of(report, parsePrimaryCauses(report.getPrimaryCausesJson()));
	}

	/**
	 * 고위험 조합 경고(ISSUE-27, GET /api/v1/reports/causes/latest/warnings).
	 *
	 * 최신 원인 리포트를 새로 계산하지 않고 getLatestSkinReport()를 그대로 호출해 primaryCauses를
	 * 재사용한다(체크인 원본을 다시 조회/계산하지 않음). 데이터가 없거나 부족하면 getLatestSkinReport()가
	 * 던지는 예외(SKIN_ANALYSIS_NOT_FOUND 등)가 그대로 전파된다 - 고위험 조합이 "없는" 것과
	 * 원인 리포트 자체가 "없는" 것은 다른 상황이므로 구분한다. 매칭되는 고위험 조합이 없을 때만
	 * 빈 warnings 배열을 반환한다.
	 */
	public ReportDto.WarningsResponse getLatestCauseWarnings(Long userId) {
		ReportDto.Response latestReport = getLatestSkinReport(userId);
		List<ReportDto.Warning> warnings = causeCombinationRubric.evaluate(latestReport.primaryCauses());
		return new ReportDto.WarningsResponse(warnings);
	}

	/**
	 * 원인 요인 상호작용 설명(ISSUE-28, GET /api/v1/reports/causes/latest/interactions).
	 *
	 * getLatestCauseWarnings()와 같은 방식으로 getLatestSkinReport()의 primaryCauses를 재사용하고,
	 * CauseCombinationRubric의 조합 매칭 규칙(#27과 동일한 매칭 로직)을 그대로 재사용하되, 의료적
	 * 인과관계로 단정하지 않는 상호작용 전용 문구로 응답을 구성한다. 함께 관찰된 조합이 없으면
	 * 에러 없이 빈 interactions 배열을 반환한다.
	 */
	public ReportDto.InteractionsResponse getLatestCauseInteractions(Long userId) {
		ReportDto.Response latestReport = getLatestSkinReport(userId);
		List<ReportDto.Interaction> interactions = causeCombinationRubric.interactions(latestReport.primaryCauses());
		return new ReportDto.InteractionsResponse(interactions);
	}

	/**
	 * 피부 컨디션 신호 카드(ISSUE-28, GET /api/v1/reports/causes/latest/skin-signal).
	 *
	 * fallback 우선순위(신규 Vision/OpenAI 호출 및 SkinComparison 신규 생성 금지):
	 * 1) 최신 리포트가 참조하는 (current, previous) SkinAnalysis 쌍에 대해 이미 계산된 SkinComparison이
	 *    있으면 그 ChangeDirection(AI 이미지 비교 판단)을 그대로 사용한다.
	 * 2) 없으면 Report에 이미 저장된 redness/trouble의 ReportChangeStatus(SkinAnalysisLevel 서수
	 *    변화로 이 서비스가 계산한 값)를 ChangeDirection으로 매핑해 대체 신호로 사용한다.
	 */
	public ReportDto.SkinSignalResponse getLatestSkinSignal(Long userId) {
		Report report = getOrCreateLatestReport(userId);
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
	 * 최신 SkinAnalysis 기준 Report를 find-or-create로 반환한다(REP-101 핵심 로직).
	 * getLatestSkinReport() DTO 변환뿐 아니라, Report 엔티티가 가진 연관 SkinAnalysis(id)나
	 * status가 그대로 필요한 skin-signal(ISSUE-28) 같은 기능에서도 재사용한다 - 새로운 분석/저장을
	 * 유발하지 않고 항상 같은 find-or-create 결과를 공유하기 위함이다.
	 */
	private Report getOrCreateLatestReport(Long userId) {
		List<SkinAnalysis> latestAnalyses = skinAnalysisRepository.findTop2ByUserIdOrderByAnalyzedAtDesc(userId);
		if (latestAnalyses.isEmpty()) {
			throw new GlobalException(ErrorCode.SKIN_ANALYSIS_NOT_FOUND);
		}
		if (latestAnalyses.size() < 2) {
			throw new GlobalException(ErrorCode.INSUFFICIENT_ANALYSIS_DATA);
		}
		SkinAnalysis current = latestAnalyses.get(0);
		SkinAnalysis previous = latestAnalyses.get(1);

		Optional<Report> existing = reportRepository.findByCurrentSkinAnalysis_Id(current.getId());
		if (existing.isPresent()) {
			log.info("기존 리포트 재사용: reportId={}, currentSkinAnalysisId={}", existing.get().getId(), current.getId());
			return existing.get();
		}

		return createReport(userId, current, previous);
	}

	private Report createReport(Long userId, SkinAnalysis current, SkinAnalysis previous) {
		Object lock = reportCreationLocks.computeIfAbsent(current.getId(), id -> new Object());
		synchronized (lock) {
			// 락을 기다리는 동안 다른 요청이 먼저 생성/저장을 끝냈을 수 있으므로 다시 확인한다(double-checked).
			Optional<Report> existing = reportRepository.findByCurrentSkinAnalysis_Id(current.getId());
			if (existing.isPresent()) {
				log.info("동시 요청으로 먼저 생성된 리포트를 재사용(OpenAI 재호출 없음): reportId={}, currentSkinAnalysisId={}",
						existing.get().getId(), current.getId());
				return existing.get();
			}

			LocalDate referenceDate = current.getAnalyzedAt().toLocalDate();
			List<Checkin> checkins = checkinRepository.findAllByUserIdAndCheckedDateLessThanEqualOrderByCheckedDateDesc(userId, referenceDate);
			if (checkins.isEmpty()) {
				throw new GlobalException(ErrorCode.CHECKIN_NOT_FOUND);
			}
			Checkin latestCheckin = checkins.get(0);
			List<Checkin> previousCheckins = checkins.subList(1, checkins.size());

			int rednessPreviousScore = previous.getRednessLevel().ordinal();
			int rednessCurrentScore = current.getRednessLevel().ordinal();
			int troublePreviousScore = previous.getTroubleLevel().ordinal();
			int troubleCurrentScore = current.getTroubleLevel().ordinal();
			int rednessChange = rednessCurrentScore - rednessPreviousScore;
			int troubleChange = troubleCurrentScore - troublePreviousScore;
			ReportChangeStatus rednessStatus = toReportChangeStatus(rednessChange);
			ReportChangeStatus troubleStatus = toReportChangeStatus(troubleChange);

			log.info("리포트 점수 계산: redness {}→{}(change={}, {}), trouble {}→{}(change={}, {})",
					rednessPreviousScore, rednessCurrentScore, rednessChange, rednessStatus,
					troublePreviousScore, troubleCurrentScore, troubleChange, troubleStatus);

			AiDto.SkinChangeInput skinChangeInput = new AiDto.SkinChangeInput(
					rednessChange, rednessStatus, troubleChange, troubleStatus
			);
			AiDto.CheckinInput checkinInput = buildCheckinInput(latestCheckin, previousCheckins);

			log.info("OpenAI 원인 분석 요청 시작: currentSkinAnalysisId={}", current.getId());
			AiDto.CauseAnalysisResult causeAnalysisResult = aiClient.analyzeSkinChangeCauses(skinChangeInput, checkinInput);
			validateCauseAnalysisResult(causeAnalysisResult);

			List<ReportDto.PrimaryCause> primaryCauses = causeAnalysisResult.causes().stream()
					.map(cause -> enrichCause(cause, latestCheckin))
					.toList();

			Report report = new Report(
					current, previous, referenceDate,
					rednessPreviousScore, rednessCurrentScore, rednessStatus,
					troublePreviousScore, troubleCurrentScore, troubleStatus,
					toJson(primaryCauses), causeAnalysisResult.summary()
			);

			try {
				Report saved = reportRepository.save(report);
				log.info("리포트 저장 완료: reportId={}, currentSkinAnalysisId={}", saved.getId(), current.getId());
				return saved;
			} catch (DataIntegrityViolationException e) {
				// 락으로 막지 못한 경합(예: 다중 인스턴스 배포)에 대한 최종 안전장치.
				// unique 제약 위반은 곧 다른 요청이 먼저 저장에 성공했다는 뜻이므로, 그 결과를 재조회해서 반환한다.
				log.warn("리포트 저장 중 unique 제약 충돌 - 동시 요청으로 이미 생성된 리포트를 재사용: currentSkinAnalysisId={}", current.getId());
				return reportRepository.findByCurrentSkinAnalysis_Id(current.getId()).orElseThrow(() -> e);
			}
		}
	}

	private AiDto.CheckinInput buildCheckinInput(Checkin latestCheckin, List<Checkin> previousCheckins) {
		Double averageSleepHours = previousCheckins.isEmpty() ? null
				: previousCheckins.stream().mapToDouble(Checkin::getSleepHours).average().orElseThrow();
		Double averageStressLevel = previousCheckins.isEmpty() ? null
				: previousCheckins.stream().mapToInt(Checkin::getStressLevel).average().orElseThrow();
		Double averageWaterIntakeMl = previousCheckins.isEmpty() ? null
				: previousCheckins.stream().mapToInt(Checkin::getWaterIntakeMl).average().orElseThrow();

		return new AiDto.CheckinInput(
				latestCheckin.getSleepHours(), latestCheckin.getStressLevel(), latestCheckin.getWaterIntakeMl(),
				averageSleepHours, averageStressLevel, averageWaterIntakeMl
		);
	}

	// score(SkinAnalysisLevel ordinal) 변화량만으로 status를 결정한다. ordinal이 낮을수록(SAFE에 가까울수록)
	// 좋은 상태이므로 change < 0(등급이 낮아짐)은 IMPROVED, change > 0은 WORSENED다.
	// AI의 판단(SkinComparisonService의 ChangeDirection)은 여기서 쓰지 않는다 - score와 status가
	// 서로 다른 기준이 되어 모순된 응답(예: change=0인데 status=WORSENED)이 나오지 않도록 하기 위함이다.
	private ReportChangeStatus toReportChangeStatus(int change) {
		if (change < 0) {
			return ReportChangeStatus.IMPROVED;
		}
		if (change > 0) {
			return ReportChangeStatus.WORSENED;
		}
		return ReportChangeStatus.UNCHANGED;
	}

	// skin-signal(ISSUE-28)에서 SkinComparison이 없을 때의 대체 신호 변환.
	// Report.status는 SkinAnalysisLevel ordinal 변화(등급이 좋아짐/유지/나빠짐)로 계산되므로,
	// 그 의미를 그대로 ChangeDirection(감소/유지/증가)에 대응시킨다: 등급이 좋아짐(IMPROVED) = 감소(DECREASED).
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

	private ReportDto.PrimaryCause enrichCause(AiDto.Cause cause, Checkin latestCheckin) {
		return switch (cause.factor()) {
			case SLEEP -> new ReportDto.PrimaryCause(
					cause.factor(), cause.name(), latestCheckin.getSleepHours(), "시간", cause.description());
			case STRESS -> new ReportDto.PrimaryCause(
					cause.factor(), cause.name(), latestCheckin.getStressLevel().doubleValue(), "5단계", cause.description());
			case WATER_INTAKE -> new ReportDto.PrimaryCause(
					cause.factor(), cause.name(), latestCheckin.getWaterIntakeMl().doubleValue(), "ml", cause.description());
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
