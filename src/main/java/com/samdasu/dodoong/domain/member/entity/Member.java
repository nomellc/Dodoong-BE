package com.samdasu.dodoong.domain.member.entity;

import com.samdasu.dodoong.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name= "members")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    @Id
    @Column(name = "member_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String loginId;

    @Column(nullable = false, length = 255)
    private String password;

    @Column(unique = true, length = 50)
    private String nickname;

    @Column
    private String profileImageKey;

    @Column(length = 255)
    private String introduction;

    @Column(nullable = false)
    private int level = 1;

    @Column(nullable = false)
    private int experience = 0;

    @Column(nullable = false)
    private int coin = 0;


    @Builder
    public Member(String loginId, String encodedPassword) {
        this.loginId = loginId;
        this.password = encodedPassword;
    }

    public void updateProfile(String nickname, String profileImageKey, String introduction) {
        if (nickname != null) {
            this.nickname = nickname;
        }

        if (profileImageKey != null && !profileImageKey.isBlank()) {
            this.profileImageKey = profileImageKey;
        }

        if (introduction != null) {
            this.introduction = introduction;
        }
    }

    public void addExperience(int amount) {
        if (amount <= 0) {
            return;
        }

        this.experience += amount;

        while (canLevelUp()) {
            int requiredExperience = calculateRequiredExperience();
            this.experience -= requiredExperience;
            this.level += 1;
            earnCoin(levelUpReward());
        }
    }

    // 체크 해제 롤백: 경험치만 차감(0 하한), 레벨·코인은 유지 (R7 수정 — 2026-10-21 결정)
    // 레벨 다운+코인 회수 방식은 보상 코인을 이미 소비한 경우 잔액을 음수로 만들었다.
    // 레벨을 유지하면 체크-해제-재체크를 반복해도 레벨 경계를 다시 넘지 않으므로
    // 보상이 중복 지급되지 않는다(코인 복제 불가).
    public void subtractExperience(int amount) {
        if (amount <= 0) {
            return;
        }

        this.experience = Math.max(0, this.experience - amount);
    }

    //코인 보유량 검사
    public boolean hasEnoughCoin(int amount){
        return this.coin >= amount;
    }
    //코인 감소
    public void spendCoin(int amount){
        this.coin -= amount;
    }
    //코인 증가
    public void earnCoin(int amount) {
        this.coin += amount;
    }

    // 레벨업
    public int calculateRequiredExperience() {
        return (this.level + 1) * 100;
    }

    public boolean canLevelUp() {
        return this.experience >= calculateRequiredExperience();
    }

    private int levelUpReward() {
        return this.level * 10;
    }
}
