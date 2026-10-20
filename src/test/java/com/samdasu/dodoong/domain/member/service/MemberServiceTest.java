package com.samdasu.dodoong.domain.member.service;

import com.samdasu.dodoong.domain.character.repository.MemberCharacterRepository;
import com.samdasu.dodoong.domain.member.dto.request.ProfileImageUploadRequest;
import com.samdasu.dodoong.domain.member.dto.request.ProfileUpdateRequest;
import com.samdasu.dodoong.domain.member.dto.response.MemberResponse;
import com.samdasu.dodoong.domain.member.dto.response.ProfileImageUploadResponse;
import com.samdasu.dodoong.domain.member.entity.Member;
import com.samdasu.dodoong.domain.member.repository.MemberRepository;
import com.samdasu.dodoong.domain.party.entity.Party;
import com.samdasu.dodoong.domain.party.entity.PartyMember;
import com.samdasu.dodoong.domain.party.entity.PartyRole;
import com.samdasu.dodoong.domain.party.repository.PartyMemberRepository;
import com.samdasu.dodoong.domain.party.repository.PartyRepository;
import com.samdasu.dodoong.domain.quest.repository.DailyQuestRepository;
import com.samdasu.dodoong.domain.routine.repository.RoutineRepository;
import com.samdasu.dodoong.domain.streak.repository.StreakRepository;
import com.samdasu.dodoong.global.exception.CustomException;
import com.samdasu.dodoong.global.response.code.ErrorCode;
import com.samdasu.dodoong.global.storage.FileStorage;
import com.samdasu.dodoong.global.storage.PresignedUpload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MemberService 단위 테스트 (Phase 2).
 * 규칙 출처: docs/analysis/domains/member.md — 프로필 이미지 key의 소유자 prefix 검증,
 * 이미지 교체 시 커밋 후(afterCommit) 이전 객체 삭제, 파티장은 탈퇴 불가.
 * 탈퇴의 FK 위반(R8)은 MemberWithdrawalFkIntegrationTest에서 재현·확정됨.
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock private MemberRepository memberRepository;
    @Mock private MemberCharacterRepository memberCharacterRepository;
    @Mock private FileStorage fileStorage;
    @Mock private PartyMemberRepository partyMemberRepository;
    @Mock private com.samdasu.dodoong.domain.party.repository.PartyVerificationRepository partyVerificationRepository;
    @Mock private com.samdasu.dodoong.domain.chat.repository.ChatMessageRepository chatMessageRepository;
    @Mock private DailyQuestRepository dailyQuestRepository;
    @Mock private RoutineRepository routineRepository;
    @Mock private StreakRepository streakRepository;
    @Mock private PartyRepository partyRepository;

    @InjectMocks
    private MemberService memberService;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Member createMember(Long id) {
        Member member = Member.builder().loginId("dodoong").encodedPassword("pw").build();
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    @Nested
    @DisplayName("getMyInfo — 내 정보 조회")
    class GetMyInfo {

        @Test
        @DisplayName("프로필 이미지가 있으면 public URL로 변환해 내려준다")
        void convertsImageKeyToPublicUrl() {
            Member member = createMember(7L);
            member.updateProfile("은서", "profiles/7/a.jpg", "소개");
            when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
            when(fileStorage.toPublicUrl("profiles/7/a.jpg")).thenReturn("https://cdn/profiles/7/a.jpg");

            MemberResponse response = memberService.getMyInfo(7L);

            assertThat(response.profileImageUrl()).isEqualTo("https://cdn/profiles/7/a.jpg");
            assertThat(response.nickname()).isEqualTo("은서");
        }

        @Test
        @DisplayName("프로필 이미지가 없으면 URL은 null이고 스토리지를 호출하지 않는다")
        void returnsNullUrlWithoutImage() {
            when(memberRepository.findById(7L)).thenReturn(Optional.of(createMember(7L)));

            MemberResponse response = memberService.getMyInfo(7L);

            assertThat(response.profileImageUrl()).isNull();
            verifyNoInteractions(fileStorage);
        }
    }

    @Nested
    @DisplayName("updateProfile — 프로필 수정")
    class UpdateProfile {

        @Test
        @DisplayName("다른 회원이 쓰는 닉네임이면 DUPLICATE_NICKNAME으로 거부된다")
        void rejectsDuplicateNickname() {
            when(memberRepository.findById(7L)).thenReturn(Optional.of(createMember(7L)));
            when(memberRepository.existsByNicknameAndIdNot("은서", 7L)).thenReturn(true);

            assertThatThrownBy(() -> memberService.updateProfile(
                    7L, new ProfileUpdateRequest("은서", null, null)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.DUPLICATE_NICKNAME);
        }

        @Test
        @DisplayName("[보안] 내 경로(profiles/{내 id}/)가 아닌 이미지 key는 INVALID_PROFILE_IMAGE_KEY로 거부된다")
        void rejectsImageKeyOfAnotherMember() {
            when(memberRepository.findById(7L)).thenReturn(Optional.of(createMember(7L)));

            assertThatThrownBy(() -> memberService.updateProfile(
                    7L, new ProfileUpdateRequest("은서", null, "profiles/99/stolen.jpg")))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.INVALID_PROFILE_IMAGE_KEY);
        }

        @Test
        @DisplayName("이미지를 교체하면 이전 객체 삭제가 커밋 후(afterCommit)로 미뤄진다")
        void deletesPreviousImageOnlyAfterCommit() {
            Member member = createMember(7L);
            member.updateProfile(null, "profiles/7/old.jpg", null);
            when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
            when(fileStorage.toPublicUrl(any())).thenReturn("https://cdn/new.jpg");

            TransactionSynchronizationManager.initSynchronization();
            memberService.updateProfile(7L, new ProfileUpdateRequest(null, null, "profiles/7/new.jpg"));

            // 커밋 전에는 삭제가 호출되지 않아야 한다
            verify(fileStorage, never()).delete(any());

            // 커밋 시점을 흉내 내 afterCommit 훅 실행
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.get(0).afterCommit();

            verify(fileStorage).delete("profiles/7/old.jpg");
            assertThat(member.getProfileImageKey()).isEqualTo("profiles/7/new.jpg");
        }

        @Test
        @DisplayName("같은 key로 다시 저장하면 삭제 훅을 등록하지 않는다")
        void doesNotRegisterDeleteHookWhenImageUnchanged() {
            Member member = createMember(7L);
            member.updateProfile(null, "profiles/7/same.jpg", null);
            when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
            when(fileStorage.toPublicUrl(any())).thenReturn("https://cdn/same.jpg");

            TransactionSynchronizationManager.initSynchronization();
            memberService.updateProfile(7L, new ProfileUpdateRequest(null, null, "profiles/7/same.jpg"));

            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        }
    }

    @Nested
    @DisplayName("createProfileImageUploadUrl — Presigned URL 발급")
    class UploadUrl {

        @Test
        @DisplayName("내 디렉터리(profiles/{id}) 하위로 Presigned PUT URL을 발급한다")
        void issuesPresignedUrlUnderOwnDirectory() {
            when(memberRepository.findById(7L)).thenReturn(Optional.of(createMember(7L)));
            when(fileStorage.createUploadUrl("profiles/7", "image/png"))
                    .thenReturn(new PresignedUpload("https://s3/upload", "profiles/7/uuid.png"));

            ProfileImageUploadResponse response = memberService.createProfileImageUploadUrl(
                    7L, new ProfileImageUploadRequest("image/png"));

            assertThat(response.uploadUrl()).isEqualTo("https://s3/upload");
            assertThat(response.profileImageKey()).startsWith("profiles/7/");
        }
    }

    @Nested
    @DisplayName("withdraw — 회원 탈퇴")
    class Withdraw {

        @Test
        @DisplayName("파티장인 회원은 PARTY_LEADER_CANNOT_WITHDRAW로 탈퇴가 거부된다")
        void rejectsPartyLeader() {
            when(memberRepository.findById(7L)).thenReturn(Optional.of(createMember(7L)));
            when(partyMemberRepository.existsByMemberIdAndRole(7L, PartyRole.LEADER)).thenReturn(true);

            assertThatThrownBy(() -> memberService.withdraw(7L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.PARTY_LEADER_CANNOT_WITHDRAW);

            verify(memberRepository, never()).delete(any());
        }

        @Test
        @DisplayName("가입 파티의 currentMembers를 줄이고 연관 데이터를 정리한 뒤 회원을 삭제한다")
        void leavesPartiesAndCleansUpBeforeDelete() {
            Member member = createMember(7L);
            Party party = Party.builder()
                    .name("미라클 모닝").maxMembers(5).isRecruiting(true)
                    .isPublic(true).questContent("기상 인증").build();
            ReflectionTestUtils.setField(party, "id", 3L);
            party.increaseCurrentMembers(); // 2명(파티장+본인)
            PartyMember partyMember = PartyMember.builder()
                    .role(PartyRole.MEMBER).member(member).party(party).build();
            ReflectionTestUtils.setField(partyMember, "id", 15L);

            when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
            when(partyMemberRepository.existsByMemberIdAndRole(7L, PartyRole.LEADER)).thenReturn(false);
            when(partyMemberRepository.findAllByMemberIdAndRole(7L, PartyRole.MEMBER))
                    .thenReturn(List.of(partyMember));
            when(partyRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(party));

            memberService.withdraw(7L);

            assertThat(party.getCurrentMembers()).isEqualTo(1);
            InOrder inOrder = inOrder(partyVerificationRepository, partyMemberRepository,
                    dailyQuestRepository, routineRepository, streakRepository,
                    memberCharacterRepository, chatMessageRepository, memberRepository);
            // R8: 인증 삭제 → 파티원 삭제, 채팅 익명화 → 회원 삭제 순서가 FK를 지킨다
            inOrder.verify(partyVerificationRepository).deleteByPartyMemberIds(List.of(15L));
            inOrder.verify(partyMemberRepository).deleteAll(List.of(partyMember));
            inOrder.verify(dailyQuestRepository).deleteAllByMemberId(7L);
            inOrder.verify(routineRepository).deleteAllByMemberId(7L);
            inOrder.verify(streakRepository).deleteAllByMemberId(7L);
            inOrder.verify(memberCharacterRepository).deleteAllByMemberId(7L);
            inOrder.verify(chatMessageRepository).detachSenderByMemberId(7L);
            inOrder.verify(memberRepository).delete(member);
        }

        @Test
        @DisplayName("없는 회원이면 MEMBER_NOT_FOUND 예외가 발생한다")
        void failsWhenMemberNotFound() {
            when(memberRepository.findById(7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> memberService.withdraw(7L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        }
    }
}
