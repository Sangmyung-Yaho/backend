package com.sangmyungyaho.barocare.routine.service;

import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
import com.sangmyungyaho.barocare.report.dto.ReportDto;
import com.sangmyungyaho.barocare.report.entity.ReportCauseFactor;
import com.sangmyungyaho.barocare.report.service.ReportService;
import com.sangmyungyaho.barocare.routine.dto.RoutineDto;
import com.sangmyungyaho.barocare.routine.dto.RoutineDto.RoutineResponseDto;
import com.sangmyungyaho.barocare.routine.entity.Routine;
import com.sangmyungyaho.barocare.routine.repository.RoutineRepository;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysis;
import com.sangmyungyaho.barocare.skin.entity.SkinAnalysisLevel;
import com.sangmyungyaho.barocare.skin.repository.SkinAnalysisRepository;
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
import java.util.stream.Collectors;

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

    private static final String INTENSITY_LOW = "저강도";

    private final RoutineRepository routineRepository;
    private final CheckinRepository checkinRepository;
    private final SkinAnalysisRepository skinAnalysisRepository;
    private final UserRepository userRepository;
    private final ReportService reportService;

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

        return new RoutineResponseDto(
                isCheckinCompleted,
                false, // isGenerating is ignored (always false)
                totalCount,
                completedCount,
                todayProgressPercent,
                routineItems
        );
    }

    @Transactional
    public void generateRoutines(Long userId, Checkin checkin) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));

        List<SkinAnalysis> recentAnalyses = skinAnalysisRepository.findTop2ByUserIdOrderByAnalyzedAtDesc(userId);
        boolean isSkinDanger = false;
        if (!recentAnalyses.isEmpty()) {
            SkinAnalysis latest = recentAnalyses.get(0);
            if (latest.getSkinLevel() == SkinAnalysisLevel.DANGER) {
                isSkinDanger = true;
            }
        }

        List<Routine> generatedRoutines = new ArrayList<>();

        // 1순위: 피부 위험군
        if (isSkinDanger) {
            generatedRoutines.add(new Routine(
                    userId, "피부", "염증 완화를 위한 스팟 젤 바르기", "적극개입", checkin.getCheckedDate()
            ));
        }

        // 2순위: 수면
        if (checkin.getSleepHours() < 6.0) {
            generatedRoutines.add(new Routine(
                    userId, "수면", "자기 전 스마트폰 30분 멀리하기", "적극개입", checkin.getCheckedDate()
            ));
        } else if (checkin.getSleepHours() < 7.0 && generatedRoutines.size() < 4) {
            generatedRoutines.add(new Routine(
                    userId, "수면", "저녁 시간에 카페인 피하기", "저강도", checkin.getCheckedDate()
            ));
        }

        // 3순위: 수분
        int waterGoal = user.getWaterGoalMl() != null ? user.getWaterGoalMl() : 2000;
        if (checkin.getWaterIntakeMl() < waterGoal * 0.5 && generatedRoutines.size() < 4) {
            generatedRoutines.add(new Routine(
                    userId, "수분", "식사 전 물 한 컵 마시기", "적극개입", checkin.getCheckedDate()
            ));
        } else if (checkin.getWaterIntakeMl() < waterGoal * 0.8 && generatedRoutines.size() < 4) {
            generatedRoutines.add(new Routine(
                    userId, "수분", "오후 중 물 한 컵 더 마시기", "저강도", checkin.getCheckedDate()
            ));
        }

        // 4순위: 스트레스
        if (checkin.getStressLevel() >= 4 && generatedRoutines.size() < 4) {
            generatedRoutines.add(new Routine(
                    userId, "스트레스", "자기 전 10분 심호흡 명상하기", "적극개입", checkin.getCheckedDate()
            ));
        } else if (checkin.getStressLevel() >= 3 && generatedRoutines.size() < 4) {
            generatedRoutines.add(new Routine(
                    userId, "스트레스", "가벼운 스트레칭으로 긴장 풀기", "저강도", checkin.getCheckedDate()
            ));
        }

        // 통합 원인 분석 결과 연동: 이미 계산돼 있거나(재사용, OpenAI 재호출 없음) 방금 계산 가능한 원인
        // 리포트가 있으면, AI가 확인한 요인(primaryCauses)에 해당하는 루틴의 강도를 "저강도" -> "적극개입"으로
        // 격상한다. 원인 리포트가 아직 없거나(신규 사용자 등) 조회에 실패해도 기존 규칙 기반 루틴 생성 자체는
        // 항상 성공해야 하므로, 이 단계 전체를 안전하게 처리한다(데이터 부족 fallback).
        escalateRoutinesByCauseAnalysis(userId, generatedRoutines);

        // 순서 정렬: 적극개입 > 저강도
        generatedRoutines.sort(Comparator.comparing(Routine::getIntensity).reversed());

        routineRepository.saveAll(generatedRoutines);
    }

    /**
     * 통합 원인 분석("오늘의 케어 및 루틴 연동") - AI가 확인한 원인 요인에 해당하는 "저강도" 루틴을
     * "적극개입"으로 격상한다. 원인 리포트를 구할 수 없는 모든 경우(피부 분석/체크인 이력 부족, AI 분석
     * 실패, 예상치 못한 오류 등)에는 아무것도 바꾸지 않고 조용히 넘어간다 - 체크인 저장과 기존 규칙 기반
     * 루틴 생성은 이 단계와 무관하게 항상 성공해야 한다.
     */
    private void escalateRoutinesByCauseAnalysis(Long userId, List<Routine> generatedRoutines) {
        Set<ReportCauseFactor> aiConfirmedFactors;
        try {
            aiConfirmedFactors = reportService.tryGetLatestSkinReport(userId)
                    .map(report -> report.primaryCauses().stream()
                            .map(ReportDto.PrimaryCause::factor)
                            .collect(Collectors.toSet()))
                    .orElse(Set.of());
        } catch (RuntimeException e) {
            // tryGetLatestSkinReport()가 이미 GlobalException은 흡수하지만, 예상치 못한 오류까지 방어한다.
            log.warn("원인 분석 결과 연동 실패(루틴 생성은 기존 규칙대로 계속 진행): userId={}", userId, e);
            aiConfirmedFactors = Set.of();
        }

        if (aiConfirmedFactors.isEmpty()) {
            return;
        }

        for (Routine routine : generatedRoutines) {
            ReportCauseFactor factor = CATEGORY_TO_CAUSE_FACTOR.get(routine.getCategory());
            if (factor != null && aiConfirmedFactors.contains(factor) && INTENSITY_LOW.equals(routine.getIntensity())) {
                log.info("원인 분석 결과에 따라 루틴 강도 격상: userId={}, category={}, factor={}", userId, routine.getCategory(), factor);
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
