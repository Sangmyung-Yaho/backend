package com.sangmyungyaho.barocare.routine.service;

import com.sangmyungyaho.barocare.checkin.entity.Checkin;
import com.sangmyungyaho.barocare.checkin.repository.CheckinRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RoutineService {

    private final RoutineRepository routineRepository;
    private final CheckinRepository checkinRepository;
    private final SkinAnalysisRepository skinAnalysisRepository;
    private final UserRepository userRepository;

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

        // 순서 정렬: 적극개입 > 저강도
        generatedRoutines.sort(Comparator.comparing(Routine::getIntensity).reversed());

        routineRepository.saveAll(generatedRoutines);
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
