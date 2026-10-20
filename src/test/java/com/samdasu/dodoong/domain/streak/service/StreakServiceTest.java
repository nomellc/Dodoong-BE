package com.samdasu.dodoong.domain.streak.service;

import com.samdasu.dodoong.domain.member.entity.Member;
import com.samdasu.dodoong.domain.member.repository.MemberRepository;
import com.samdasu.dodoong.domain.streak.dto.StreakResponse;
import com.samdasu.dodoong.domain.streak.entity.Streak;
import com.samdasu.dodoong.domain.streak.repository.StreakRepository;
import com.samdasu.dodoong.domain.streak.service.achievement.QuestAchievementSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * StreakService 단위 테스트 (Phase 2).
 * 규칙 출처: docs/analysis/domains/streak.md — 하루 1개 이상 완료 시 스트릭 인정,
 * 7일 연속마다 코인 +10, 같은 날 중복 반영 방지, 끊기면 새 스트릭 시작.
 * 달성 소스는 QuestAchievementSource 목록 주입(현재 구현체는 DailyQuest 1종).
 */
@ExtendWith(MockitoExtension.class)
class StreakServiceTest {

    private static final LocalDate TARGET = LocalDate.of(2026, 10, 20);

    @Mock private StreakRepository streakRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private QuestAchievementSource achievementSource;

    private StreakService streakService;

    private Member member;

    @BeforeEach
    void setUp() {
        // achievementSources가 List 주입이라 @InjectMocks 대신 직접 생성
        streakService = new StreakService(streakRepository, memberRepository, List.of(achievementSource));
        member = Member.builder().loginId("dodoong").encodedPassword("encodedPassword").build();
        ReflectionTestUtils.setField(member, "id", 7L);
    }

    private Streak streakWith(LocalDate lastCheckedDate, int consecutiveDays) {
        Streak streak = Streak.start(member, lastCheckedDate.minusDays(consecutiveDays - 1L));
        ReflectionTestUtils.setField(streak, "lastCheckedDate", lastCheckedDate);
        ReflectionTestUtils.setField(streak, "consecutiveDays", consecutiveDays);
        return streak;
    }

    @Nested
    @DisplayName("evaluateDailyStreak — 배치 평가")
    class Evaluate {

        @Test
        @DisplayName("그날 완료한 퀘스트가 0개면 아무것도 하지 않는다")
        void skipsWhenNothingCompleted() {
            when(achievementSource.countCompletedQuests(7L, TARGET)).thenReturn(0L);

            streakService.evaluateDailyStreak(7L, TARGET);

            verifyNoInteractions(memberRepository, streakRepository);
        }

        @Test
        @DisplayName("기존 스트릭이 없으면 1일짜리 새 스트릭을 시작한다")
        void startsNewStreakWhenNoneExists() {
            when(achievementSource.countCompletedQuests(7L, TARGET)).thenReturn(1L);
            when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
            when(streakRepository.findTopByMemberIdOrderByLastCheckedDateDesc(7L))
                    .thenReturn(Optional.empty());

            streakService.evaluateDailyStreak(7L, TARGET);

            ArgumentCaptor<Streak> captor = ArgumentCaptor.forClass(Streak.class);
            verify(streakRepository).save(captor.capture());
            assertThat(captor.getValue().getConsecutiveDays()).isEqualTo(1);
            assertThat(captor.getValue().getStartDate()).isEqualTo(TARGET);
            assertThat(captor.getValue().getLastCheckedDate()).isEqualTo(TARGET);
        }

        @Test
        @DisplayName("같은 날이 이미 반영돼 있으면 중복 반영하지 않는다")
        void skipsWhenAlreadyReflected() {
            when(achievementSource.countCompletedQuests(7L, TARGET)).thenReturn(2L);
            when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
            Streak streak = streakWith(TARGET, 3);
            when(streakRepository.findTopByMemberIdOrderByLastCheckedDateDesc(7L))
                    .thenReturn(Optional.of(streak));

            streakService.evaluateDailyStreak(7L, TARGET);

            assertThat(streak.getConsecutiveDays()).isEqualTo(3);
            verify(streakRepository, never()).save(any());
            assertThat(member.getCoin()).isZero();
        }

        @Test
        @DisplayName("전날까지 이어진 스트릭이면 하루 연장된다")
        void continuesConsecutiveStreak() {
            when(achievementSource.countCompletedQuests(7L, TARGET)).thenReturn(1L);
            when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
            Streak streak = streakWith(TARGET.minusDays(1), 3);
            when(streakRepository.findTopByMemberIdOrderByLastCheckedDateDesc(7L))
                    .thenReturn(Optional.of(streak));

            streakService.evaluateDailyStreak(7L, TARGET);

            assertThat(streak.getConsecutiveDays()).isEqualTo(4);
            assertThat(streak.getLastCheckedDate()).isEqualTo(TARGET);
            assertThat(member.getCoin()).isZero(); // 4일은 보상 아님
        }

        @Test
        @DisplayName("경계값: 연속 7일째 되는 날 코인 +10이 지급된다 (6일→7일)")
        void rewardsCoinOnSeventhDay() {
            when(achievementSource.countCompletedQuests(7L, TARGET)).thenReturn(1L);
            when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
            Streak streak = streakWith(TARGET.minusDays(1), 6);
            when(streakRepository.findTopByMemberIdOrderByLastCheckedDateDesc(7L))
                    .thenReturn(Optional.of(streak));

            streakService.evaluateDailyStreak(7L, TARGET);

            assertThat(streak.getConsecutiveDays()).isEqualTo(7);
            assertThat(member.getCoin()).isEqualTo(10);
        }

        @Test
        @DisplayName("경계값: 14일째에도 다시 코인이 지급된다 (7일 간격 반복)")
        void rewardsCoinAgainOnFourteenthDay() {
            when(achievementSource.countCompletedQuests(7L, TARGET)).thenReturn(1L);
            when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
            Streak streak = streakWith(TARGET.minusDays(1), 13);
            when(streakRepository.findTopByMemberIdOrderByLastCheckedDateDesc(7L))
                    .thenReturn(Optional.of(streak));

            streakService.evaluateDailyStreak(7L, TARGET);

            assertThat(streak.getConsecutiveDays()).isEqualTo(14);
            assertThat(member.getCoin()).isEqualTo(10);
        }

        @Test
        @DisplayName("하루라도 끊겼으면 기존 스트릭은 두고 1일짜리 새 스트릭을 만든다")
        void startsFreshStreakAfterGap() {
            when(achievementSource.countCompletedQuests(7L, TARGET)).thenReturn(1L);
            when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
            Streak oldStreak = streakWith(TARGET.minusDays(2), 5); // 그제까지 — 어제 끊김
            when(streakRepository.findTopByMemberIdOrderByLastCheckedDateDesc(7L))
                    .thenReturn(Optional.of(oldStreak));

            streakService.evaluateDailyStreak(7L, TARGET);

            assertThat(oldStreak.getConsecutiveDays()).isEqualTo(5); // 기존 이력 보존
            ArgumentCaptor<Streak> captor = ArgumentCaptor.forClass(Streak.class);
            verify(streakRepository).save(captor.capture());
            assertThat(captor.getValue().getConsecutiveDays()).isEqualTo(1);
        }

        @Test
        @DisplayName("여러 달성 소스가 있으면 합산해서 1개 이상인지 판단한다 (확장 포인트)")
        void sumsAcrossAchievementSources() {
            QuestAchievementSource secondSource = mock(QuestAchievementSource.class);
            streakService = new StreakService(
                    streakRepository, memberRepository, List.of(achievementSource, secondSource));
            when(achievementSource.countCompletedQuests(7L, TARGET)).thenReturn(0L);
            when(secondSource.countCompletedQuests(7L, TARGET)).thenReturn(1L);
            when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
            when(streakRepository.findTopByMemberIdOrderByLastCheckedDateDesc(7L))
                    .thenReturn(Optional.empty());

            streakService.evaluateDailyStreak(7L, TARGET);

            verify(streakRepository).save(any(Streak.class));
        }
    }

    @Nested
    @DisplayName("getStreak — 조회")
    class GetStreak {

        @Test
        @DisplayName("최신 스트릭 1건을 응답으로 변환한다")
        void returnsLatestStreak() {
            Streak streak = streakWith(TARGET, 3);
            when(streakRepository.findTopByMemberIdOrderByLastCheckedDateDesc(7L))
                    .thenReturn(Optional.of(streak));

            StreakResponse response = streakService.getStreak(7L);

            assertThat(response.consecutiveDays()).isEqualTo(3);
            assertThat(response.lastCheckedDate()).isEqualTo(TARGET);
        }

        @Test
        @DisplayName("스트릭이 없으면 빈 응답(0일)을 반환한다")
        void returnsEmptyWhenNoStreak() {
            when(streakRepository.findTopByMemberIdOrderByLastCheckedDateDesc(7L))
                    .thenReturn(Optional.empty());

            assertThat(streakService.getStreak(7L)).isEqualTo(StreakResponse.empty());
        }
    }
}
