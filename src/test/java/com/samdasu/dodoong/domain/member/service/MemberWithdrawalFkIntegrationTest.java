package com.samdasu.dodoong.domain.member.service;

import com.samdasu.dodoong.domain.character.repository.CharacterItemRepository;
import com.samdasu.dodoong.domain.chat.dto.response.ChatHistoryResponse;
import com.samdasu.dodoong.domain.chat.entity.ChatMessage;
import com.samdasu.dodoong.domain.chat.repository.ChatMessageRepository;
import com.samdasu.dodoong.domain.chat.service.ChatService;
import com.samdasu.dodoong.domain.member.entity.Member;
import com.samdasu.dodoong.domain.member.repository.MemberRepository;
import com.samdasu.dodoong.domain.party.entity.Party;
import com.samdasu.dodoong.domain.party.entity.PartyMember;
import com.samdasu.dodoong.domain.party.entity.PartyRole;
import com.samdasu.dodoong.domain.party.entity.PartyVerification;
import com.samdasu.dodoong.domain.party.repository.PartyMemberRepository;
import com.samdasu.dodoong.domain.party.repository.PartyRepository;
import com.samdasu.dodoong.domain.party.repository.PartyVerificationRepository;
import com.samdasu.dodoong.domain.party.service.PartyService;
import com.samdasu.dodoong.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * R8 통합 테스트.
 * Phase 2(2026-10-21): FK 위반으로 탈퇴/파티탈퇴가 실패함을 재현해 결함 확정.
 * Phase 3(2026-10-21 결정 6): 수정 후 활성화 — 채팅은 보존+익명화(sender NULL),
 * 인증은 파티원 삭제 시 함께 삭제. (docs/phase-reports/phase3-plan.md)
 */
class MemberWithdrawalFkIntegrationTest extends IntegrationTestSupport {

    private static final ZoneId ZONE_KST = ZoneId.of("Asia/Seoul");

    @Autowired private MemberService memberService;
    @Autowired private PartyService partyService;
    @Autowired private ChatService chatService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PartyRepository partyRepository;
    @Autowired private PartyMemberRepository partyMemberRepository;
    @Autowired private PartyVerificationRepository partyVerificationRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private CharacterItemRepository characterItemRepository;

    @BeforeEach
    void cleanUp() {
        chatMessageRepository.deleteAll();
        partyVerificationRepository.deleteAll();
        partyMemberRepository.deleteAll();
        partyRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("연관 데이터(채팅·인증)가 없는 일반 파티원은 탈퇴에 성공한다")
    void withdrawSucceedsWithoutChatOrVerification() {
        Member leader = saveMember("leader-plain");
        Member member = saveMember("member-plain");
        Party party = savePartyWithLeader(leader);
        joinAsMember(member, party);

        memberService.withdraw(member.getId());

        assertThat(memberRepository.findById(member.getId())).isEmpty();
        assertThat(partyMemberRepository.findAllByMemberIdAndRole(member.getId(), PartyRole.MEMBER)).isEmpty();
    }

    @Test
    @DisplayName("[R8 수정 검증] 채팅을 남긴 회원이 탈퇴하면 메시지는 보존되고 sender만 익명화된다")
    void withdrawShouldSucceedForMemberWhoWroteChatMessages() {
        Member leader = saveMember("leader-chat");
        Member member = saveMember("member-chat");
        Party party = savePartyWithLeader(leader);
        joinAsMember(member, party);
        ChatMessage message = chatMessageRepository.save(ChatMessage.builder()
                .content("오늘도 인증 완료!")
                .party(party)
                .sender(member)
                .build());

        assertThatCode(() -> memberService.withdraw(member.getId()))
                .as("채팅을 남긴 회원의 탈퇴 — Phase 2에서 chat_messages.member_id FK 위반으로 재현된 결함 (R8)")
                .doesNotThrowAnyException();

        assertThat(memberRepository.findById(member.getId())).isEmpty();

        // 메시지는 보존되고 보낸 사람만 끊긴다(익명화 — FE가 "탈퇴한 사용자"로 표시)
        ChatMessage preserved = chatMessageRepository.findById(message.getId()).orElseThrow();
        assertThat(preserved.getContent()).isEqualTo("오늘도 인증 완료!");
        assertThat(preserved.getSender()).isNull();

        // 익명화된 메시지가 채팅 내역 조회에 포함되고 NPE 없이 매핑된다
        ChatHistoryResponse history = chatService.getChatHistory(party.getId(), leader.getId(), null, 20);
        assertThat(history.messages()).hasSize(1);
        assertThat(history.messages().get(0).senderId()).isNull();
        assertThat(history.messages().get(0).senderNickname()).isNull();
        assertThat(history.messages().get(0).isMine()).isFalse();
        assertThat(history.messages().get(0).content()).isEqualTo("오늘도 인증 완료!");
    }

    @Test
    @DisplayName("[R8 수정 검증] 파티 인증을 올린 회원이 탈퇴하면 인증 레코드도 함께 삭제된다")
    void withdrawShouldSucceedForMemberWithVerification() {
        Member leader = saveMember("leader-verify");
        Member member = saveMember("member-verify");
        Party party = savePartyWithLeader(leader);
        PartyMember partyMember = joinAsMember(member, party);
        PartyVerification verification = saveVerification(party, partyMember);

        assertThatCode(() -> memberService.withdraw(member.getId()))
                .as("인증을 올린 회원의 탈퇴 — Phase 2에서 party_verifications FK 위반으로 재현된 결함 (R8)")
                .doesNotThrowAnyException();

        assertThat(memberRepository.findById(member.getId())).isEmpty();
        assertThat(partyVerificationRepository.findById(verification.getId())).isEmpty();

        // 파티 정원 필드도 함께 줄어든다(R2 일치)
        Party reloaded = partyRepository.findById(party.getId()).orElseThrow();
        assertThat(reloaded.getCurrentMembers()).isEqualTo(1);
    }

    @Test
    @DisplayName("[R8 수정 검증] 파티 인증을 올린 파티원도 파티 탈퇴에 성공하고 인증은 삭제된다")
    void leavePartyShouldSucceedForMemberWithVerification() {
        Member leader = saveMember("leader-leave");
        Member member = saveMember("member-leave");
        Party party = savePartyWithLeader(leader);
        PartyMember partyMember = joinAsMember(member, party);
        PartyVerification verification = saveVerification(party, partyMember);

        assertThatCode(() -> partyService.leaveParty(member.getId(), party.getId()))
                .as("인증 이력이 있는 파티원의 파티 탈퇴 — Phase 2에서 FK 위반으로 재현된 결함 (R8 확장)")
                .doesNotThrowAnyException();

        assertThat(partyMemberRepository.findByMemberIdAndPartyId(member.getId(), party.getId())).isEmpty();
        assertThat(partyVerificationRepository.findById(verification.getId())).isEmpty();

        // bulk 삭제가 영속성 컨텍스트의 파티 변경(currentMembers 감소)을 날리지 않는지 확인
        Party reloaded = partyRepository.findById(party.getId()).orElseThrow();
        assertThat(reloaded.getCurrentMembers()).isEqualTo(1);
    }

    @Test
    @DisplayName("[C5/V2 검증] 새 DB에도 Flyway 시드로 기본 캐릭터(id=1)가 존재한다")
    void defaultCharacterSeedExists() {
        assertThat(characterItemRepository.findById(1L)).isPresent();
    }

    private Member saveMember(String loginId) {
        return memberRepository.save(Member.builder()
                .loginId(loginId)
                .encodedPassword("encodedPassword")
                .build());
    }

    private Party savePartyWithLeader(Member leader) {
        Party party = partyRepository.save(Party.builder()
                .name("미라클 모닝")
                .maxMembers(5)
                .isRecruiting(true)
                .isPublic(true)
                .questContent("아침 7시 기상 인증")
                .build());
        partyMemberRepository.save(PartyMember.builder()
                .role(PartyRole.LEADER)
                .member(leader)
                .party(party)
                .build());
        return party;
    }

    private PartyMember joinAsMember(Member member, Party party) {
        PartyMember partyMember = partyMemberRepository.save(PartyMember.builder()
                .role(PartyRole.MEMBER)
                .member(member)
                .party(party)
                .build());
        party.increaseCurrentMembers();
        partyRepository.save(party);
        return partyMember;
    }

    private PartyVerification saveVerification(Party party, PartyMember partyMember) {
        return partyVerificationRepository.save(PartyVerification.builder()
                .party(party)
                .partyMember(partyMember)
                .imageUrl("https://example.com/verifications/1.jpg")
                .verified(true)
                .verificationDate(LocalDate.now(ZONE_KST))
                .build());
    }
}
