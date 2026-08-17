package com.sangmyungyaho.barocare.user.entity;

import com.sangmyungyaho.barocare.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 50)
    private Provider provider;

    @Column(name = "social_id", nullable = false)
    private String socialId;

    @Column(name = "nickname", length = 50)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "fcm_token")
    private String fcmToken;

    @Column(name = "is_onboarded", nullable = false)
    private boolean isOnboarded;

    @Column(name = "current_streak", nullable = false)
    private int currentStreak;

    @Column(name = "last_activity_date")
    private LocalDate lastActivityDate;

    @Column(name = "push_routine", nullable = false)
    private boolean pushRoutine;

    @Column(name = "push_marketing", nullable = false)
    private boolean pushMarketing;

    @Enumerated(EnumType.STRING)
    @Column(name = "skin_type", length = 20)
    private SkinType skinType;

    @Column(name = "height")
    private Double height;

    @Column(name = "weight")
    private Double weight;

    @Column(name = "water_goal_ml")
    private Integer waterGoalMl;

    // 온보딩 필수 약관 동의. 마케팅 수신 동의(pushMarketing, 선택)와는 별개로 관리한다.
    @Column(name = "terms_agreed", nullable = false)
    private boolean termsAgreed;

    @Column(name = "privacy_agreed", nullable = false)
    private boolean privacyAgreed;

    @Column(name = "photo_guide_agreed", nullable = false)
    private boolean photoGuideAgreed;

    @Column(name = "skin_care_pause_reason", length = 500)
    private String skinCarePauseReason;

    @Builder
    public User(Provider provider, String socialId, String nickname) {
        this.provider = provider;
        this.socialId = socialId;
        this.nickname = nickname;
        this.status = UserStatus.ACTIVE;
        this.isOnboarded = false;
        this.currentStreak = 0;
        this.pushRoutine = true;
        this.pushMarketing = false;
        this.termsAgreed = false;
        this.privacyAgreed = false;
        this.photoGuideAgreed = false;
    }

    public void updateFcmToken(String fcmToken) {
        this.fcmToken = fcmToken;
    }

    // 명시적인 온보딩 완료 API(POST /api/v1/onboarding/complete)를 통해서만 호출되어야 한다.
    // 필수 약관 동의 여부 검증은 UserService가 담당한다(엔티티는 상태 전이만 책임진다).
    public void completeOnboarding() {
        this.isOnboarded = true;
    }

    public void deleteUser() {
        this.status = UserStatus.DELETED;
    }

    // 일반 프로필 수정. 온보딩 완료 여부(isOnboarded)는 여기서 바꾸지 않는다 -
    // 온보딩 완료는 반드시 completeOnboarding()을 통해서만 이뤄져야 한다.
    public void updateProfile(String nickname, Double height, Double weight, SkinType skinType, Integer waterGoalMl) {
        if (nickname != null) {
            this.nickname = nickname;
        }
        if (height != null) {
            this.height = height;
        }
        if (weight != null) {
            this.weight = weight;
        }
        if (skinType != null) {
            this.skinType = skinType;
        }
        if (waterGoalMl != null) {
            this.waterGoalMl = waterGoalMl;
        }
    }

    public void updateMarketingAgreement(boolean agreed) {
        this.pushMarketing = agreed;
    }

    // 온보딩 필수 약관(이용약관/개인정보 수집·이용) 동의 저장. 마케팅 수신 동의와는 별개다.
    public void updateRequiredAgreements(boolean termsAgreed, boolean privacyAgreed) {
        this.termsAgreed = termsAgreed;
        this.privacyAgreed = privacyAgreed;
    }

    public void updatePhotoGuideAgreement(boolean agreed) {
        this.photoGuideAgreed = agreed;
    }

    public void updateSkinCarePauseReason(String reason) {
        this.skinCarePauseReason = reason;
    }

    public boolean hasAgreedToRequiredTerms() {
        return this.termsAgreed && this.privacyAgreed;
    }

    public void recordActivity(LocalDate activityDate) {
        if (this.lastActivityDate == null) {
            this.currentStreak = 1;
        } else if (activityDate.equals(this.lastActivityDate.plusDays(1))) {
            this.currentStreak++;
        } else if (!activityDate.equals(this.lastActivityDate)) {
            this.currentStreak = 1;
        }
        this.lastActivityDate = activityDate;
    }
}
