package com.samdasu.dodoong.domain.quest.service;

import com.samdasu.dodoong.domain.member.entity.Member;
import com.samdasu.dodoong.domain.member.repository.MemberRepository;
import com.samdasu.dodoong.domain.quest.dto.request.DailyQuestCheckRequest;
import com.samdasu.dodoong.domain.quest.dto.request.DailyQuestCreateRequest;
import com.samdasu.dodoong.domain.quest.dto.request.DailyQuestUpdateRequest;
import com.samdasu.dodoong.domain.quest.dto.response.DailyQuestCheckResponse;
import com.samdasu.dodoong.domain.quest.dto.response.DailyQuestQuadrantResponse;
import com.samdasu.dodoong.domain.quest.dto.response.DailyQuestSummary;
import com.samdasu.dodoong.domain.quest.entity.DailyQuest;
import com.samdasu.dodoong.domain.quest.entity.QuestCategory;
import com.samdasu.dodoong.domain.quest.repository.DailyQuestRepository;
import com.samdasu.dodoong.domain.quest.repository.projection.DailyQuestQuadrantProjection;
import com.samdasu.dodoong.domain.routine.entity.Routine;
import com.samdasu.dodoong.domain.routine.repository.RoutineRepository;
import com.samdasu.dodoong.domain.routine.service.RoutineService;
import com.samdasu.dodoong.global.exception.CustomException;
import com.samdasu.dodoong.global.response.code.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DailyQuestService 단위 테스트 (Phase 2).
 * 규칙 출처: docs/analysis/domains/quest-routine.md — 체크 멱등(경험치 중복 지급 방지),
 * 루틴 퀘스트는 미루기 불가, 체크된 퀘스트는 삭제 불가, 루틴 생성 시 당일 분만 즉시 생성.
 */
@ExtendWith(MockitoExtension.class)
class DailyQuestServiceTest {

    private static final ZoneId ZONE_KST = ZoneId.of("Asia/Seoul");

    @Mock private MemberRepository memberRepository;
    @Mock private DailyQuestRepository dailyQuestRepository;
    @Mock private RoutineService routineService;
    @Mock private RoutineRepository routineRepository;

    @InjectMocks
    private DailyQuestService dailyQuestService;

    private Member createMember(Long id) {
        Member member = Member.builder()
                .loginId("dodoong")
                .encodedPassword("encodedPassword")
                .build();
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    private DailyQuest createQuest(Long id, Member member, Routine routine) {
        DailyQuest quest = DailyQuest.create(
                QuestCategory.IMPORTANT_URGENT,
                "알고리즘 1문제",
                LocalDate.now(ZONE_KST),
                member,
                routine
        );
        ReflectionTestUtils.setField(quest, "id", id);
        return quest;
    }

    private Routine createRoutine(Long id, Member member, Set<java.time.DayOfWeek> repeatDays, LocalDate endDate) {
        Routine routine = Routine.create(
                QuestCategory.IMPORTANT_NOT_URGENT,
                "아침 스트레칭",
                endDate,
                repeatDays,
                member
        );
        ReflectionTestUtils.setField(routine, "id", id);
        return routine;
    }

    @Nested
    @DisplayName("createDailyQuest — 생성")
    class Create {

        @Test
        @DisplayName("단일 퀘스트는 오늘 날짜로, 루틴 연결 없이 저장된다")
        void createsSingleQuestForToday() {
            Member member = createMember(7L);
            when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
            when(dailyQuestRepository.save(any(DailyQuest.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            dailyQuestService.createDailyQuest(7L, new DailyQuestCreateRequest(
                    QuestCategory.IMPORTANT_URGENT, "알고리즘 1문제", false, null, null));

            ArgumentCaptor<DailyQuest> captor = ArgumentCaptor.forClass(DailyQuest.class);
            verify(dailyQuestRepository).save(captor.capture());
            assertThat(captor.getValue().getQuestDate()).isEqualTo(LocalDate.now(ZONE_KST));
            assertThat(captor.getValue().isFromRoutine()).isFalse();
            verifyNoInteractions(routineService);
        }

        @Test
        @DisplayName("루틴 생성 시 오늘이 반복 요일이면 당일 퀘스트를 즉시 1건 생성한다")
        void createsTodayQuestWhenRoutineStartsToday() {
            Member member = createMember(7L);
            LocalDate today = LocalDate.now(ZONE_KST);
            Routine routine = createRoutine(3L, member, Set.of(today.getDayOfWeek()), today.plusWeeks(2));

            when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
            when(routineService.createRoutine(any(), any(), any(), any(), any())).thenReturn(routine);
            when(dailyQuestRepository.save(any(DailyQuest.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            dailyQuestService.createDailyQuest(7L, new DailyQuestCreateRequest(
                    QuestCategory.IMPORTANT_NOT_URGENT, "아침 스트레칭", true,
                    Set.of(today.getDayOfWeek()), today.plusWeeks(2)));

            ArgumentCaptor<DailyQuest> captor = ArgumentCaptor.forClass(DailyQuest.class);
            verify(dailyQuestRepository).save(captor.capture());
            assertThat(captor.getValue().getQuestDate()).isEqualTo(today);
            assertThat(captor.getValue().isFromRoutine()).isTrue();
        }

        @Test
        @DisplayName("루틴 생성 시 오늘이 반복 요일이 아니면 퀘스트를 미리 만들지 않는다 (가상 전개 설계)")
        void doesNotPreCreateQuestWhenRoutineStartsLater() {
            Member member = createMember(7L);
            LocalDate today = LocalDate.now(ZONE_KST);
            Set<java.time.DayOfWeek> tomorrowOnly = Set.of(today.plusDays(1).getDayOfWeek());
            Routine routine = createRoutine(3L, member, tomorrowOnly, today.plusWeeks(2));

            when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
            when(routineService.createRoutine(any(), any(), any(), any(), any())).thenReturn(routine);

            dailyQuestService.createDailyQuest(7L, new DailyQuestCreateRequest(
                    QuestCategory.IMPORTANT_NOT_URGENT, "아침 스트레칭", true,
                    tomorrowOnly, today.plusWeeks(2)));

            verify(dailyQuestRepository, never()).save(any());
        }

        @Test
        @DisplayName("회원이 없으면 MEMBER_NOT_FOUND 예외가 발생한다")
        void failsWhenMemberNotFound() {
            when(memberRepository.findById(7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> dailyQuestService.createDailyQuest(7L, new DailyQuestCreateRequest(
                    QuestCategory.IMPORTANT_URGENT, "알고리즘 1문제", false, null, null)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("checkDailyQuest — 체크/해제와 경험치 연동")
    class Check {

        @Test
        @DisplayName("체크하면 경험치 +10이 지급된다")
        void checkGrantsExperience() {
            Member member = createMember(7L);
            DailyQuest quest = createQuest(11L, member, null);
            when(memberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(member));
            when(dailyQuestRepository.findByIdAndMemberId(11L, 7L)).thenReturn(Optional.of(quest));

            DailyQuestCheckResponse response =
                    dailyQuestService.checkDailyQuest(7L, 11L, new DailyQuestCheckRequest(true));

            assertThat(member.getExperience()).isEqualTo(10);
            assertThat(response.experienceDelta()).isEqualTo(10);
            assertThat(response.leveledUp()).isFalse();
            assertThat(response.quest().isChecked()).isTrue();
        }

        @Test
        @DisplayName("경계값: 경험치 190에서 체크하면 레벨 2로 올라가고 leveledUp이 true다")
        void checkTriggersLevelUpAtBoundary() {
            Member member = createMember(7L);
            member.addExperience(190);
            DailyQuest quest = createQuest(11L, member, null);
            when(memberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(member));
            when(dailyQuestRepository.findByIdAndMemberId(11L, 7L)).thenReturn(Optional.of(quest));

            DailyQuestCheckResponse response =
                    dailyQuestService.checkDailyQuest(7L, 11L, new DailyQuestCheckRequest(true));

            assertThat(response.level()).isEqualTo(2);
            assertThat(response.experience()).isZero();
            assertThat(response.leveledUp()).isTrue();
            assertThat(member.getCoin()).isEqualTo(20);
        }

        @Test
        @DisplayName("멱등성: 이미 체크된 퀘스트를 다시 체크해도 경험치가 중복 지급되지 않는다")
        void checkIsIdempotent() {
            Member member = createMember(7L);
            DailyQuest quest = createQuest(11L, member, null);
            quest.changeChecked(true);
            member.addExperience(10);
            when(memberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(member));
            when(dailyQuestRepository.findByIdAndMemberId(11L, 7L)).thenReturn(Optional.of(quest));

            DailyQuestCheckResponse response =
                    dailyQuestService.checkDailyQuest(7L, 11L, new DailyQuestCheckRequest(true));

            assertThat(member.getExperience()).isEqualTo(10);
            assertThat(response.experienceDelta()).isZero();
        }

        @Test
        @DisplayName("체크 해제하면 경험치 -10이 차감된다")
        void uncheckSubtractsExperience() {
            Member member = createMember(7L);
            member.addExperience(30);
            DailyQuest quest = createQuest(11L, member, null);
            quest.changeChecked(true);
            when(memberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(member));
            when(dailyQuestRepository.findByIdAndMemberId(11L, 7L)).thenReturn(Optional.of(quest));

            DailyQuestCheckResponse response =
                    dailyQuestService.checkDailyQuest(7L, 11L, new DailyQuestCheckRequest(false));

            assertThat(member.getExperience()).isEqualTo(20);
            assertThat(response.experienceDelta()).isEqualTo(-10);
            assertThat(response.quest().isChecked()).isFalse();
        }

        @Test
        @DisplayName("레벨업 직후 체크 해제해도 레벨은 유지되고 경험치만 차감된다 (R7 수정 — 레벨 다운 폐지)")
        void uncheckKeepsLevelAfterLevelUp() {
            Member member = createMember(7L);
            member.addExperience(200); // 레벨 2, 경험치 0, 코인 20
            DailyQuest quest = createQuest(11L, member, null);
            quest.changeChecked(true);
            when(memberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(member));
            when(dailyQuestRepository.findByIdAndMemberId(11L, 7L)).thenReturn(Optional.of(quest));

            DailyQuestCheckResponse response =
                    dailyQuestService.checkDailyQuest(7L, 11L, new DailyQuestCheckRequest(false));

            assertThat(response.level()).isEqualTo(2);
            assertThat(response.experience()).isZero();
            assertThat(response.leveledUp()).isFalse();
            assertThat(member.getCoin()).isEqualTo(20);
        }

        @Test
        @DisplayName("남의 퀘스트이거나 없는 퀘스트면 DAILY_QUEST_NOT_FOUND 예외가 발생한다")
        void failsWhenQuestNotFound() {
            when(memberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(createMember(7L)));
            when(dailyQuestRepository.findByIdAndMemberId(99L, 7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> dailyQuestService.checkDailyQuest(7L, 99L, new DailyQuestCheckRequest(true)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.DAILY_QUEST_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("updateDailyQuest — 수정")
    class Update {

        @Test
        @DisplayName("전달된 필드만 갱신되고 null 필드는 기존 값이 유지된다")
        void updatesOnlyProvidedFields() {
            Member member = createMember(7L);
            DailyQuest quest = createQuest(11L, member, null);
            when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
            when(dailyQuestRepository.findByIdAndMemberId(11L, 7L)).thenReturn(Optional.of(quest));

            DailyQuestSummary summary = dailyQuestService.updateDailyQuest(
                    7L, 11L, new DailyQuestUpdateRequest(null, "새 내용"));

            assertThat(summary.content()).isEqualTo("새 내용");
            assertThat(summary.questCategory()).isEqualTo(QuestCategory.IMPORTANT_URGENT);
        }
    }

    @Nested
    @DisplayName("postpone / delete — 미루기와 삭제 규칙")
    class PostponeAndDelete {

        @Test
        @DisplayName("미체크 단일 퀘스트는 하루 뒤로 미뤄진다")
        void postponesSingleQuestByOneDay() {
            Member member = createMember(7L);
            DailyQuest quest = createQuest(11L, member, null);
            LocalDate originalDate = quest.getQuestDate();
            when(dailyQuestRepository.findByIdAndMemberId(11L, 7L)).thenReturn(Optional.of(quest));

            dailyQuestService.postpone(7L, 11L);

            assertThat(quest.getQuestDate()).isEqualTo(originalDate.plusDays(1));
        }

        @Test
        @DisplayName("루틴에서 생성된 퀘스트는 미룰 수 없다")
        void routineQuestCannotBePostponed() {
            Member member = createMember(7L);
            LocalDate today = LocalDate.now(ZONE_KST);
            Routine routine = createRoutine(3L, member, Set.of(today.getDayOfWeek()), today.plusWeeks(1));
            DailyQuest quest = createQuest(11L, member, routine);
            when(dailyQuestRepository.findByIdAndMemberId(11L, 7L)).thenReturn(Optional.of(quest));

            assertThatThrownBy(() -> dailyQuestService.postpone(7L, 11L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.ROUTINE_QUEST_CANNOT_BE_POSTPONED);
        }

        @Test
        @DisplayName("체크된 퀘스트는 미룰 수 없다")
        void checkedQuestCannotBePostponed() {
            Member member = createMember(7L);
            DailyQuest quest = createQuest(11L, member, null);
            quest.changeChecked(true);
            when(dailyQuestRepository.findByIdAndMemberId(11L, 7L)).thenReturn(Optional.of(quest));

            assertThatThrownBy(() -> dailyQuestService.postpone(7L, 11L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.CHECKED_QUEST_CANNOT_BE_POSTPONED);
        }

        @Test
        @DisplayName("미체크 퀘스트는 삭제되고, 체크된 퀘스트는 삭제할 수 없다")
        void checkedQuestCannotBeDeleted() {
            Member member = createMember(7L);
            DailyQuest unchecked = createQuest(11L, member, null);
            when(dailyQuestRepository.findByIdAndMemberId(11L, 7L)).thenReturn(Optional.of(unchecked));

            dailyQuestService.deleteDailyQuest(7L, 11L);
            verify(dailyQuestRepository).delete(unchecked);

            DailyQuest checked = createQuest(12L, member, null);
            checked.changeChecked(true);
            when(dailyQuestRepository.findByIdAndMemberId(12L, 7L)).thenReturn(Optional.of(checked));

            assertThatThrownBy(() -> dailyQuestService.deleteDailyQuest(7L, 12L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.DAILY_QUEST_CANNOT_BE_DELETED);
            verify(dailyQuestRepository, never()).delete(checked);
        }
    }

    @Nested
    @DisplayName("getDailyQuestByDate — 루틴 가상 전개(virtual expansion)")
    class VirtualExpansion {

        @Test
        @DisplayName("미래 날짜 조회는 DB 대신 루틴을 가상 퀘스트로 전개해 응답한다")
        void expandsRoutineVirtuallyForFutureDate() {
            Member member = createMember(7L);
            LocalDate future = LocalDate.now(ZONE_KST).plusDays(3);
            Routine routine = createRoutine(3L, member, Set.of(future.getDayOfWeek()), future.plusWeeks(1));

            when(dailyQuestRepository.findSummariesByDateAndNoRoutine(7L, future)).thenReturn(List.of());
            when(routineRepository.findActiveRoutineWithRepeatDays(7L, future)).thenReturn(List.of(routine));

            var response = dailyQuestService.getDailyQuestByDate(7L, future);

            assertThat(response.quests()).hasSize(1);
            assertThat(response.quests().get(0).dailyQuestId()).isNull(); // 가상 퀘스트 — DB 레코드 없음
            assertThat(response.quests().get(0).isRoutine()).isTrue();
            assertThat(response.quests().get(0).routineId()).isEqualTo(3L);
        }

        @Test
        @DisplayName("루틴 종료일이 지난 날짜에는 가상 퀘스트를 만들지 않는다")
        void doesNotExpandBeyondRoutineEndDate() {
            Member member = createMember(7L);
            LocalDate future = LocalDate.now(ZONE_KST).plusDays(10);
            Routine routine = createRoutine(3L, member, Set.of(future.getDayOfWeek()), future.minusDays(1));

            when(dailyQuestRepository.findSummariesByDateAndNoRoutine(7L, future)).thenReturn(List.of());
            when(routineRepository.findActiveRoutineWithRepeatDays(7L, future)).thenReturn(List.of(routine));

            var response = dailyQuestService.getDailyQuestByDate(7L, future);

            assertThat(response.quests()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getQuadrant — 사분면 조회")
    class Quadrant {

        @Test
        @DisplayName("미완료 퀘스트를 카테고리별로 묶고, 비어 있는 사분면도 빈 리스트로 채운다")
        void groupsByCategoryAndFillsEmptyQuadrants() {
            DailyQuestQuadrantProjection projection = mock(DailyQuestQuadrantProjection.class);
            when(projection.getQuestCategory()).thenReturn(QuestCategory.IMPORTANT_URGENT);
            when(projection.getDailyQuestId()).thenReturn(11L);
            when(projection.getContent()).thenReturn("알고리즘 1문제");
            when(projection.getQuestDate()).thenReturn(LocalDate.now(ZONE_KST));
            when(projection.getRoutineId()).thenReturn(null);
            when(dailyQuestRepository.findIncompleteQuests(7L)).thenReturn(List.of(projection));

            DailyQuestQuadrantResponse response = dailyQuestService.getQuadrant(7L);

            assertThat(response.quadrants()).hasSize(QuestCategory.values().length);
        }
    }
}
