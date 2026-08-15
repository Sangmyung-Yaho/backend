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
    }

    public void updateFcmToken(String fcmToken) {
        this.fcmToken = fcmToken;
    }

    public void completeOnboarding() {
        this.isOnboarded = true;
    }
    
    public void deleteUser() {
        this.status = UserStatus.DELETED;
    }

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
        this.isOnboarded = true;
    }

    public void updateMarketingAgreement(boolean agreed) {
        this.pushMarketing = agreed;
    }
}
