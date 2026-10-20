package com.samdasu.dodoong.domain.member.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Member 엔티티의 경험치/레벨/코인 규칙 단위 테스트 (Phase 2).
 * 규칙 출처: docs/analysis/domains/member.md — 필요 경험치 (level+1)*100,
 * 레벨업 보상은 "레벨업 후 레벨" × 10, 체크 해제 시 레벨 다운 + 보상 코인 회수.
 */
class MemberTest {

    private Member createMember() {
        return Member.builder()
                .loginId("dodoong")
                .encodedPassword("encodedPassword")
                .build();
    }

    @Nested
    @DisplayName("addExperience — 경험치 적립과 레벨업")
    class AddExperience {

        @Test
        @DisplayName("0 이하의 경험치는 무시된다")
        void ignoresZeroOrNegativeAmount() {
            Member member = createMember();

            member.addExperience(0);
            member.addExperience(-10);

            assertThat(member.getLevel()).isEqualTo(1);
            assertThat(member.getExperience()).isZero();
            assertThat(member.getCoin()).isZero();
        }

        @Test
        @DisplayName("필요 경험치(레벨1→2는 200) 미만이면 레벨이 오르지 않는다")
        void doesNotLevelUpBelowThreshold() {
            Member member = createMember();

            member.addExperience(199);

            assertThat(member.getLevel()).isEqualTo(1);
            assertThat(member.getExperience()).isEqualTo(199);
            assertThat(member.getCoin()).isZero();
        }

        @Test
        @DisplayName("경계값: 정확히 필요 경험치에 도달하면 레벨업하고 경험치는 0이 된다")
        void levelsUpExactlyAtThreshold() {
            Member member = createMember();

            member.addExperience(200);

            assertThat(member.getLevel()).isEqualTo(2);
            assertThat(member.getExperience()).isZero();
        }

        @Test
        @DisplayName("레벨업 보상 코인은 레벨업 '후' 레벨 기준이다 (1→2 레벨업 시 20코인)")
        void levelUpRewardUsesPostLevelUpLevel() {
            Member member = createMember();

            member.addExperience(200);

            assertThat(member.getCoin()).isEqualTo(20);
        }

        @Test
        @DisplayName("초과분 경험치는 다음 레벨로 이월된다")
        void carriesOverRemainingExperience() {
            Member member = createMember();

            member.addExperience(250);

            assertThat(member.getLevel()).isEqualTo(2);
            assertThat(member.getExperience()).isEqualTo(50);
            assertThat(member.getCoin()).isEqualTo(20);
        }

        @Test
        @DisplayName("한 번에 여러 레벨을 연속으로 올릴 수 있고 보상은 레벨별로 누적된다")
        void levelsUpMultipleTimesInOneCall() {
            Member member = createMember();

            // 1→2에 200, 2→3에 300 필요. 500이면 정확히 레벨 3 도달.
            member.addExperience(500);

            assertThat(member.getLevel()).isEqualTo(3);
            assertThat(member.getExperience()).isZero();
            // 보상: 레벨2 도달 시 20 + 레벨3 도달 시 30
            assertThat(member.getCoin()).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("subtractExperience — 체크 해제 롤백(레벨 다운·코인 회수)")
    class SubtractExperience {

        @Test
        @DisplayName("0 이하의 차감량은 무시된다")
        void ignoresZeroOrNegativeAmount() {
            Member member = createMember();
            member.addExperience(100);

            member.subtractExperience(0);
            member.subtractExperience(-10);

            assertThat(member.getExperience()).isEqualTo(100);
        }

        @Test
        @DisplayName("보유 경험치 범위 안에서는 경험치만 줄어든다")
        void subtractsWithinCurrentLevel() {
            Member member = createMember();
            member.addExperience(100);

            member.subtractExperience(10);

            assertThat(member.getLevel()).isEqualTo(1);
            assertThat(member.getExperience()).isEqualTo(90);
            assertThat(member.getCoin()).isZero();
        }

        @Test
        @DisplayName("레벨 1에서는 레벨 다운 없이 경험치가 0 밑으로 내려가지 않는다")
        void clampsAtZeroOnLevelOne() {
            Member member = createMember();
            member.addExperience(5);

            member.subtractExperience(10);

            assertThat(member.getLevel()).isEqualTo(1);
            assertThat(member.getExperience()).isZero();
        }

        @Test
        @DisplayName("경험치가 모자라면 레벨 다운하고 레벨업 때 받았던 보상 코인을 회수한다")
        void levelsDownAndReclaimsReward() {
            Member member = createMember();
            member.addExperience(200); // 레벨 2, 경험치 0, 코인 20

            member.subtractExperience(10);

            assertThat(member.getLevel()).isEqualTo(1);
            // 레벨 1의 만료 경험치(200)로 복원 후 10 차감
            assertThat(member.getExperience()).isEqualTo(190);
            assertThat(member.getCoin()).isZero();
        }

        @Test
        @DisplayName("레벨업 직후 체크 해제하면 레벨·경험치·코인이 레벨업 전 상태로 돌아간다 (대칭성)")
        void addThenSubtractIsSymmetric() {
            Member member = createMember();
            member.addExperience(190);

            member.addExperience(10);  // 레벨업: 레벨2, 경험치 0, 코인 20
            member.subtractExperience(10); // 롤백

            assertThat(member.getLevel()).isEqualTo(1);
            assertThat(member.getExperience()).isEqualTo(190);
            assertThat(member.getCoin()).isZero();
        }

        @Test
        @DisplayName("여러 레벨을 내려가면 레벨별 보상이 각각 회수된다")
        void levelsDownMultipleTimes() {
            Member member = createMember();
            member.addExperience(500); // 레벨 3, 경험치 0, 코인 50

            member.subtractExperience(310); // 레벨3 구간(300) + 10

            assertThat(member.getLevel()).isEqualTo(1);
            // 레벨2 복원(300) → 잔여 310-0=310... 단계별: L3→L2 회수 30, L2→L1 회수 20
            assertThat(member.getCoin()).isZero();
            assertThat(member.getExperience()).isEqualTo(190);
        }

        @Test
        @org.junit.jupiter.api.Disabled("R7 결함 확정(2026-10-21 재현: coin=-20) — Phase 3에서 수정 후 활성화")
        @DisplayName("[R7 재현] 보상 코인을 이미 소비한 뒤 레벨 다운하면 코인이 음수가 되지 않아야 한다")
        void coinShouldNeverGoNegativeAfterLevelDown() {
            Member member = createMember();
            member.addExperience(200); // 레벨 2, 코인 20
            member.spendCoin(20);      // 캐릭터 구매 등으로 전액 소비 (호출부는 hasEnoughCoin으로 검증)
            assertThat(member.getCoin()).isZero();

            member.subtractExperience(10); // 퀘스트 체크 해제 → 레벨 다운 → 보상 20 회수 시도

            assertThat(member.getCoin())
                    .as("레벨 다운 시 잔액 검사 없이 코인을 회수하면 음수가 된다 (R7)")
                    .isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("[R7 현재 동작 기록] 보상 코인 소비 후 레벨 다운 시 코인이 -20이 된다")
        void documentsCurrentNegativeCoinBehavior() {
            Member member = createMember();
            member.addExperience(200);
            member.spendCoin(20);

            member.subtractExperience(10);

            // 현재 구현의 실제 동작을 고정하는 테스트 — 수정(Phase 3) 시 이 테스트를 갱신해야 함
            assertThat(member.getCoin()).isEqualTo(-20);
        }
    }

    @Nested
    @DisplayName("코인 규칙")
    class Coin {

        @Test
        @DisplayName("hasEnoughCoin은 잔액과 같은 금액이면 true, 1이라도 모자라면 false")
        void hasEnoughCoinBoundary() {
            Member member = createMember();
            member.earnCoin(100);

            assertThat(member.hasEnoughCoin(100)).isTrue();
            assertThat(member.hasEnoughCoin(101)).isFalse();
        }

        @Test
        @DisplayName("earnCoin과 spendCoin은 잔액을 증감한다")
        void earnAndSpend() {
            Member member = createMember();

            member.earnCoin(30);
            member.spendCoin(10);

            assertThat(member.getCoin()).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("updateProfile — 프로필 부분 수정")
    class UpdateProfile {

        @Test
        @DisplayName("null 필드는 기존 값을 유지하고, 전달된 필드만 갱신한다")
        void updatesOnlyProvidedFields() {
            Member member = createMember();
            member.updateProfile("도둥이", "profiles/1/a.jpg", "소개글");

            member.updateProfile(null, null, null);

            assertThat(member.getNickname()).isEqualTo("도둥이");
            assertThat(member.getProfileImageKey()).isEqualTo("profiles/1/a.jpg");
            assertThat(member.getIntroduction()).isEqualTo("소개글");
        }

        @Test
        @DisplayName("프로필 이미지 key는 빈 문자열이면 무시된다")
        void ignoresBlankProfileImageKey() {
            Member member = createMember();
            member.updateProfile(null, "profiles/1/a.jpg", null);

            member.updateProfile(null, "  ", null);

            assertThat(member.getProfileImageKey()).isEqualTo("profiles/1/a.jpg");
        }
    }
}
