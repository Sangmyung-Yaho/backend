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
}
