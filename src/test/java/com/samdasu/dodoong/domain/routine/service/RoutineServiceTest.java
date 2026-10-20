package com.samdasu.dodoong.domain.routine.service;

import com.samdasu.dodoong.domain.member.entity.Member;
import com.samdasu.dodoong.domain.quest.entity.QuestCategory;
import com.samdasu.dodoong.domain.quest.repository.DailyQuestRepository;
import com.samdasu.dodoong.domain.routine.entity.Routine;
import com.samdasu.dodoong.domain.routine.repository.RoutineRepository;
import com.samdasu.dodoong.global.exception.CustomException;
import com.samdasu.dodoong.global.response.code.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RoutineService 단위 테스트 (Phase 2).
 * 규칙 출처: docs/analysis/domains/quest-routine.md — 루틴 삭제 시
 * 미체크 퀘스트는 삭제하고 체크된 퀘스트는 routine_id를 끊어 기록을 보존한다.
 */
@ExtendWith(MockitoExtension.class)
class RoutineServiceTest {

    @Mock private RoutineRepository routineRepository;
    @Mock private DailyQuestRepository dailyQuestRepository;

    @InjectMocks
    private RoutineService routineService;

    private Member createMember(Long id) {
        Member member = Member.builder().loginId("dodoong").encodedPassword("pw").build();
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    @Test
    @DisplayName("createRoutine은 반복 규칙을 저장하고 저장된 엔티티를 반환한다")
    void createRoutineSavesAndReturns() {
        Member member = createMember(7L);
        when(routineRepository.save(any(Routine.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Routine routine = routineService.createRoutine(
                QuestCategory.IMPORTANT_NOT_URGENT, "아침 스트레칭",
                LocalDate.of(2026, 12, 31), Set.of(DayOfWeek.MONDAY), member);

        assertThat(routine.getContent()).isEqualTo("아침 스트레칭");
        assertThat(routine.getRepeatDays()).containsExactly(DayOfWeek.MONDAY);
        verify(routineRepository).save(any(Routine.class));
    }

    @Test
    @DisplayName("deleteRoutine은 미체크 퀘스트 삭제 → 체크 퀘스트 연결 해제 → 루틴 삭제 순으로 수행한다")
    void deleteRoutineKeepsCheckedQuestHistory() {
        Member member = createMember(7L);
        Routine routine = Routine.create(
                QuestCategory.IMPORTANT_NOT_URGENT, "아침 스트레칭",
                LocalDate.of(2026, 12, 31), Set.of(DayOfWeek.MONDAY), member);
        ReflectionTestUtils.setField(routine, "id", 3L);
        when(routineRepository.findByIdAndMemberId(3L, 7L)).thenReturn(Optional.of(routine));

        routineService.deleteRoutine(7L, 3L);

        InOrder inOrder = inOrder(dailyQuestRepository, routineRepository);
        inOrder.verify(dailyQuestRepository).deleteUncheckedByRoutineId(3L);
        inOrder.verify(dailyQuestRepository).detachCheckedByRoutineId(3L);
        inOrder.verify(routineRepository).delete(routine);
    }

    @Test
    @DisplayName("남의 루틴이거나 없는 루틴이면 ROUTINE_NOT_FOUND로 거부된다")
    void deleteRoutineFailsWhenNotFound() {
        when(routineRepository.findByIdAndMemberId(3L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> routineService.deleteRoutine(7L, 3L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getBaseCode())
                .isEqualTo(ErrorCode.ROUTINE_NOT_FOUND);

        verifyNoInteractions(dailyQuestRepository);
    }
}
