package com.sangmyungyaho.barocare.routine.service;

import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.report.entity.Report;
import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.report.service.ReportService;
import com.sangmyungyaho.barocare.routine.dto.RoutineDto;
import com.sangmyungyaho.barocare.routine.dto.RoutineDto.RoutineResponseDto;
import com.sangmyungyaho.barocare.routine.entity.Routine;
import com.sangmyungyaho.barocare.routine.repository.RoutineRepository;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.user.entity.User;
import com.sangmyungyaho.barocare.user.repository.UserRepository;
import com.sangmyungyaho.barocare.global.exception.ErrorCode;
import com.sangmyungyaho.barocare.global.exception.GlobalException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 오늘의 루틴(케어) 생성/조회.
 *
 * 생성({@link #generateRoutines})은 오직
 * {@link com.sangmyungyaho.barocare.skin.service.SkinAnalysisService#analyzeSkin}이 오늘 SkinAnalysis와
 * 오늘 Report를 만든 직후에만 호출된다("Checkin → SkinImage → SkinAnalysis → Report → Routine" 순서의
 * 마지막 단계). 체크인 저장 시점에는 더 이상 루틴을 생성하지 않는다 - 그 시점엔 아직 오늘 피부 분석/원인
 * 리포트가 없어 직전(과거) 데이터로 루틴을 만들게 되는 문제가 있었기 때문이다.
 *
 * 조회({@link #getTodayRoutines})는 이미 저장된 Routine만 읽으며 생성/AI 호출을 하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RoutineService {

    private static final Logger log = LoggerFactory.getLogger(RoutineService.class);

    // 루틴 카테고리(기존 문자열 그대로) <-> 원인 분석 요인(ReportCauseFactor) 매핑.
    // "피부" 카테고리는 SkinAnalysis 등급으로 이미 독립적으로 판단하므로 매핑 대상이 아니다.
    private static final Map<String, ReportCauseFactor> CATEGORY_TO_CAUSE_FACTOR = Map.of(
            "수면", ReportCauseFactor.SLEEP,
            "스트레스", ReportCauseFactor.STRESS,
            "수분", ReportCauseFactor.WATER_INTAKE
    );

    private static final String INTENSITY_HIGH = "적극개입";
    private static final String INTENSITY_LOW = "저강도";
    private static final String INTENSITY_MAINTAIN = "유지";

    // 노출 우선순위(값이 작을수록 먼저 노출). intensity 문자열의 유니코드 순서 같은 우연에 기대지 않고
    // 명시적으로 고정한다 - "유지" 단계 추가로 문자열 자연 순서만으로는 더 이상 의도한 순서가 보장되지 않는다.
    private static final Map<String, Integer> INTENSITY_PRIORITY = Map.of(
            INTENSITY_HIGH, 0,
            INTENSITY_LOW, 1,
            INTENSITY_MAINTAIN, 2
    );

    // 카테고리(피부/수면/수분/스트레스) 4개가 각각 정확히 1개씩만 생성하므로 실제로 이 값을 넘을 일은
    // 없지만, 응답이 과도하게 길어지는 걸 막기 위한 방어적 상한이다.
    private static final int MAX_ROUTINES = 4;

    // estimatedMinutes(예상 소요시간)는 각 루틴 문구의 실제 행동에 대한 MVP 추정치다(기획 확정치 아님).
    // "자기 전 스마트폰 30분 멀리하기"처럼 시간이 문구에 명시된 경우 그 값을 그대로 쓰고, 그 외에는
    // 행동을 완료하는 데 걸리는 대략적인 시간으로 부여했다. 정책이 확정되면 이 값들만 조정하면 된다.

    private final RoutineRepository routineRepository;
    private final CheckinRepository checkinRepository;
    private final UserRepository userRepository;
    private final ReportService reportService;
    private final IngredientRecommendationService ingredientRecommendationService;

    // userId+routineDate 기준 동시 생성 방지용 락(단일 인스턴스 기준). 같은 날 피부 분석이 짧은 간격으로
    // 여러 번 호출되더라도(예: 사용자가 재시도) 두 요청이 동시에 exists 체크를 통과해 루틴이 중복
    // 생성되는 것을 최소화한다. DB 유니크 제약으로 뒷받침되지는 않으므로(Routine은 1건이 아니라 여러
    // 행으로 구성되어 자연스러운 유니크 키가 없음) 단일 인스턴스 기준의 best-effort 방어임을 명시한다.
    private final Map<String, Object> routineGenerationLocks = new ConcurrentHashMap<>();

    public RoutineResponseDto getTodayRoutines(Long userId) {
        LocalDate today = LocalDate.now();

        boolean isCheckinCompleted = checkinRepository.existsByUserIdAndCheckedDate(userId, today);
        long totalCount = routineRepository.countByUserIdAndRoutineDate(userId, today);
        long completedCount = routineRepository.countByUserIdAndRoutineDateAndIsCompletedTrue(userId, today);
        int todayProgressPercent = totalCount == 0 ? 0 : (int) ((completedCount * 100) / totalCount);

        List<Routine> routines = routineRepository.findAllByUserIdAndRoutineDate(userId, today);
        List<RoutineDto.RoutineItem> routineItems = routines.stream()
                .map(RoutineDto.RoutineItem::from)
                .toList();

        // ISSUE-30: 저장된 오늘의 추천 성분/제품을 그대로 읽기만 한다(AI/웹검색 재호출 없음).
        // 아직 생성 전이거나 생성이 실패했던 경우 IngredientRecommendationService가 빈 값을 돌려준다.
        IngredientRecommendationService.TodayRecommendation recommendation =
                ingredientRecommendationService.getTodayRecommendation(userId);

        return new RoutineResponseDto(
                isCheckinCompleted,
                false, // isGenerating은 사용하지 않는다(항상 false).
                totalCount,
                completedCount,
                todayProgressPercent,
                routineItems,
                recommendation.ingredients(),
                recommendation.products()
        );
    }

    /**
     * 오늘의 루틴 생성. 오늘 Checkin/SkinAnalysis/Report가 모두 준비된 뒤에만 호출되어야 한다.
     *
     * 멱등성: 오늘자(routineDate 기준) 루틴이 이미 있으면 재생성하지 않고 조용히 반환한다 - 같은 날
     * 피부 분석 API를 여러 번 호출해도 루틴이 계속 추가되지 않으며, 이미 완료 체크한 루틴의 상태도
     * 보존된다.
     *
     * @param report 방금 생성/재사용된 오늘 Report(원인 후보는 이 리포트의 primaryCauses를 그대로
     *               반영한다 - 재조회하지 않으므로 과거 리포트를 오늘 결과로 착각할 위험이 없다).
     */
    @Transactional
    public void generateRoutines(Long userId, Checkin checkin, SkinAnalysis todaySkinAnalysis, Report report) {
        LocalDate routineDate = checkin.getCheckedDate();

        Object lock = routineGenerationLocks.computeIfAbsent(userId + ":" + routineDate, key -> new Object());
        synchronized (lock) {
            if (routineRepository.existsByUserIdAndRoutineDate(userId, routineDate)) {
                log.info("오늘자 루틴이 이미 존재해 재생성을 건너뜀: userId={}, routineDate={}", userId, routineDate);
                return;
            }

            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));
            int waterGoal = user.getWaterGoalMl() != null ? user.getWaterGoalMl() : 2000;

            // 카테고리(피부/수면/수분/스트레스) 4개 각각 정확히 1개씩 생성한다: 문제가 있으면 개선/완화
            // 루틴을, 정상 범위면 "유지" 루틴을 생성한다. "문제 있을 때만 생성"하던 예전 방식과 달리,
            // 네 항목이 전부 정상이어도 오늘의 루틴이 항상 1개 이상 나온다(빈 배열이 되지 않는다).
            List<Routine> generatedRoutines = new ArrayList<>(List.of(
                    buildSkinRoutine(userId, routineDate, todaySkinAnalysis.getSkinLevel()),
                    buildSleepRoutine(userId, routineDate, checkin.getSleepHours()),
                    buildWaterRoutine(userId, routineDate, checkin.getWaterIntakeMl(), waterGoal),
                    buildStressRoutine(userId, routineDate, checkin.getStressLevel())
            ));

            // 통합 원인 분석 결과 연동: 방금 생성/재사용된 오늘 Report의 primaryCauses에 해당하는 루틴의
            // 강도를 "저강도" -> "적극개입"으로 격상한다(재조회 없음 - 이 Report는 이미 오늘 것으로 확정됨).
            escalateRoutinesByCauseAnalysis(generatedRoutines, report);

            // 우선순위 정렬(적극개입 > 저강도 > 유지) 후 상한 적용 - 문제 있는 항목의 개선 루틴이 정상
            // 항목의 유지 루틴보다 항상 먼저 노출된다. 카테고리가 4개뿐이라 실제로 잘려나가진 않지만,
            // 응답이 과도하게 길어지지 않도록 방어적으로 MAX_ROUTINES를 적용한다.
            List<Routine> finalRoutines = generatedRoutines.stream()
                    .sorted(Comparator.comparing(routine -> INTENSITY_PRIORITY.getOrDefault(routine.getIntensity(), Integer.MAX_VALUE)))
                    .limit(MAX_ROUTINES)
                    .toList();

            routineRepository.saveAll(finalRoutines);

            // ISSUE-30: 추천 성분/제품 생성. 규칙 기반 루틴 생성 자체와는 완전히 별개 기능이므로,
            // 여기서 어떤 예외가 나도(AI 실패, 웹검색 실패, 예상치 못한 버그 등) 위에서 이미 저장된
            // 루틴에는 전혀 영향이 없어야 한다 - escalateRoutinesByCauseAnalysis와 동일한 방어 철학.
            // (IngredientRecommendationService 내부에서도 AI/웹검색 각각의 실패를 흡수하지만, 이 바깥쪽
            // catch는 그 흡수 로직 자체의 버그까지 포함한 최종 안전장치다.)
            try {
                ingredientRecommendationService.generateTodayRecommendation(userId, todaySkinAnalysis);
            } catch (RuntimeException e) {
                log.warn("추천 성분/제품 생성 실패(루틴 생성 자체에는 영향 없음): userId={}, skinAnalysisId={}",
                        userId, todaySkinAnalysis.getId(), e);
            }
        }
    }

    // 피부: DANGER=집중 케어, CAUTION=진정/자극 회피 관리, SAFE=현재 상태 유지.
    private Routine buildSkinRoutine(Long userId, LocalDate routineDate, SkinAnalysisLevel skinLevel) {
        return switch (skinLevel) {
            case DANGER -> new Routine(userId, "피부", "염증 완화를 위한 스팟 젤 바르기", INTENSITY_HIGH, routineDate, 2);
            case CAUTION -> new Routine(userId, "피부", "자극적인 성분 피하고 순한 제품으로 진정 관리하기", INTENSITY_LOW, routineDate, 3);
            case SAFE -> new Routine(userId, "피부", "지금 쓰는 기초 루틴으로 꾸준히 보습 관리하기", INTENSITY_MAINTAIN, routineDate, 2);
        };
    }

    // 수면: 6시간 미만=강한 개선, 6~7시간 미만=개선, 7시간 이상=유지.
    private Routine buildSleepRoutine(Long userId, LocalDate routineDate, double sleepHours) {
        if (sleepHours < 6.0) {
            return new Routine(userId, "수면", "자기 전 스마트폰 30분 멀리하기", INTENSITY_HIGH, routineDate, 30);
        } else if (sleepHours < 7.0) {
            return new Routine(userId, "수면", "저녁 시간에 카페인 피하기", INTENSITY_LOW, routineDate, 1);
        }
        return new Routine(userId, "수면", "지금의 수면 습관 그대로 유지하기", INTENSITY_MAINTAIN, routineDate, 1);
    }

    // 수분: 목표량의 60% 미만=강한 개선, 60~80% 미만=개선, 80% 이상=유지.
    private Routine buildWaterRoutine(Long userId, LocalDate routineDate, int waterIntakeMl, int waterGoalMl) {
        if (waterIntakeMl < waterGoalMl * 0.6) {
            return new Routine(userId, "수분", "식사 전 물 한 컵 마시기", INTENSITY_HIGH, routineDate, 1);
        } else if (waterIntakeMl < waterGoalMl * 0.8) {
            return new Routine(userId, "수분", "오후 중 물 한 컵 더 마시기", INTENSITY_LOW, routineDate, 1);
        }
        return new Routine(userId, "수분", "지금처럼 물 충분히 마시는 습관 유지하기", INTENSITY_MAINTAIN, routineDate, 1);
    }

    // 스트레스: 4~5=강한 완화, 3=완화, 1~2=유지.
    private Routine buildStressRoutine(Long userId, LocalDate routineDate, int stressLevel) {
        if (stressLevel >= 4) {
            return new Routine(userId, "스트레스", "자기 전 10분 심호흡 명상하기", INTENSITY_HIGH, routineDate, 10);
        } else if (stressLevel >= 3) {
            return new Routine(userId, "스트레스", "가벼운 스트레칭으로 긴장 풀기", INTENSITY_LOW, routineDate, 5);
        }
        return new Routine(userId, "스트레스", "지금의 여유로운 마음 상태 유지하기", INTENSITY_MAINTAIN, routineDate, 3);
    }

    /**
     * AI가 확인한 원인 요인(오늘 Report.primaryCauses)에 해당하는 "저강도" 루틴을 "적극개입"으로
     * 격상한다. Report/파싱 실패 등 예상치 못한 오류가 나도 규칙 기반 루틴 생성 자체는 항상 성공해야
     * 하므로 예외를 흡수한다.
     */
    private void escalateRoutinesByCauseAnalysis(List<Routine> generatedRoutines, Report report) {
        Set<ReportCauseFactor> aiConfirmedFactors;
        try {
            aiConfirmedFactors = Set.copyOf(reportService.getPrimaryCauseFactors(report));
        } catch (RuntimeException e) {
            log.warn("원인 분석 결과 연동 실패(루틴 생성은 기존 규칙대로 계속 진행): reportId={}", report.getId(), e);
            return;
        }

        if (aiConfirmedFactors.isEmpty()) {
            return;
        }

        for (Routine routine : generatedRoutines) {
            ReportCauseFactor factor = CATEGORY_TO_CAUSE_FACTOR.get(routine.getCategory());
            if (factor != null && aiConfirmedFactors.contains(factor) && INTENSITY_LOW.equals(routine.getIntensity())) {
                log.info("원인 분석 결과에 따라 루틴 강도 격상: userId={}, category={}, factor={}", routine.getUserId(), routine.getCategory(), factor);
                routine.escalateToActiveIntervention();
            }
        }
    }

    @Transactional
    public RoutineDto.CheckResponse checkRoutine(Long userId, Long routineId, RoutineDto.CheckRequest request) {
        Routine routine = routineRepository.findById(routineId)
                .orElseThrow(() -> new GlobalException(ErrorCode.ROUTINE_NOT_FOUND));

        if (!routine.getUserId().equals(userId)) {
            throw new GlobalException(ErrorCode.FORBIDDEN);
        }

        if (request.isCompleted()) {
            routine.complete();
        } else {
            routine.incomplete();
        }

        LocalDate routineDate = routine.getRoutineDate();
        long totalCount = routineRepository.countByUserIdAndRoutineDate(userId, routineDate);
        long completedCount = routineRepository.countByUserIdAndRoutineDateAndIsCompletedTrue(userId, routineDate);
        int todayProgressPercent = totalCount == 0 ? 0 : (int) ((completedCount * 100) / totalCount);

        return new RoutineDto.CheckResponse(
                routine.getId(),
                routine.isCompleted(),
                completedCount,
                totalCount,
                todayProgressPercent
        );
    }
}
