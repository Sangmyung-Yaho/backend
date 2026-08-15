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
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.repository.SkinAnalysisRepository;
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
 * 피부 변화 원인 분석 리포트(REP-101, GET /api/v1/reports/skin/latest).
 *
 * 최신 SkinAnalysis를 기준으로 find-or-create 방식으로 동작한다: 이미 해당 SkinAnalysis로 생성된
 * Report가 있으면 재사용하고, 없을 때만 AiClient(원인 해석)를 호출해 새로 계산/저장한다. 따라서 같은
 * 최신 SkinAnalysis에 대한 반복 GET은 OpenAI를 다시 호출하지 않는다.
 *
 * redness/trouble의 score(SkinAnalysisLevel ordinal)와 status(IMPROVED/WORSENED/UNCHANGED)는
 * 둘 다 이 서비스가 change 값 하나로 계산한다 - AI(SkinComparisonService의 이미지 비교 판단)에 기대지
 * 않으므로 score와 status가 서로 다른 기준으로 어긋날 수 없다. AI(AiClient)는 원인 후보 해석과
 * 자연어 설명/요약 생성에만 사용한다.
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

	// currentSkinAnalysisId 기준 동시 생성 방지용 락(단일 인스턴스 기준). DB unique 제약이 최종 안전장치이므로
	// 여기서는 "같은 순간에 들어온 요청이 OpenAI를 중복 호출하지 않도록" 최소화하는 목적만 가진다.
	private final Map<Long, Object> reportCreationLocks = new ConcurrentHashMap<>();

	public ReportDto.Response getLatestSkinReport() {
		List<SkinAnalysis> latestAnalyses = skinAnalysisRepository.findTop2ByOrderByAnalyzedAtDesc();
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
			return ReportDto.Response.of(existing.get(), parsePrimaryCauses(existing.get().getPrimaryCausesJson()));
		}

		Report saved = createReport(current, previous);
		return ReportDto.Response.of(saved, parsePrimaryCauses(saved.getPrimaryCausesJson()));
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
	public ReportDto.WarningsResponse getLatestCauseWarnings() {
		ReportDto.Response latestReport = getLatestSkinReport();
		List<ReportDto.Warning> warnings = causeCombinationRubric.evaluate(latestReport.primaryCauses());
		return new ReportDto.WarningsResponse(warnings);
	}

	private Report createReport(SkinAnalysis current, SkinAnalysis previous) {
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
			List<Checkin> checkins = checkinRepository.findAllByCheckedDateLessThanEqualOrderByCheckedDateDesc(referenceDate);
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
