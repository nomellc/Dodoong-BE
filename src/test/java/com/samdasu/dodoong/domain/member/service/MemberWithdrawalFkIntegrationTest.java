package com.samdasu.dodoong.domain.member.service;

import com.samdasu.dodoong.domain.chat.entity.ChatMessage;
import com.samdasu.dodoong.domain.chat.repository.ChatMessageRepository;
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
 * R8 재현 통합 테스트 (Phase 2).
 * 가설: 회원 탈퇴/파티 탈퇴가 chat_messages(member_id FK)·party_verifications(party_member_id FK)를
 * 정리하지 않아 FK 제약 위반으로 실패한다. (docs/analysis/domains/member.md,
 * docs/phase-reports/phase1-review.md §4-3·4-4)
 *
 * 원칙: 원하는 동작("탈퇴가 성공해야 한다")을 기대값으로 작성 — 결함이 실재하면 테스트가 실패한다.
 */
class MemberWithdrawalFkIntegrationTest extends IntegrationTestSupport {

    private static final ZoneId ZONE_KST = ZoneId.of("Asia/Seoul");

    @Autowired private MemberService memberService;
    @Autowired private PartyService partyService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PartyRepository partyRepository;
    @Autowired private PartyMemberRepository partyMemberRepository;
    @Autowired private PartyVerificationRepository partyVerificationRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;

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
    @org.junit.jupiter.api.Disabled("R8 결함 확정(2026-10-21 재현: chat_messages.member_id FK 위반, 회원 삭제 시점) — Phase 3에서 수정 후 활성화")
    @DisplayName("[R8 재현] 채팅 메시지를 남긴 회원도 탈퇴에 성공해야 한다")
    void withdrawShouldSucceedForMemberWhoWroteChatMessages() {
        Member leader = saveMember("leader-chat");
        Member member = saveMember("member-chat");
        Party party = savePartyWithLeader(leader);
        joinAsMember(member, party);
        chatMessageRepository.save(ChatMessage.builder()
                .content("오늘도 인증 완료!")
                .party(party)
                .sender(member)
                .build());

        assertThatCode(() -> memberService.withdraw(member.getId()))
                .as("채팅을 남긴 회원의 탈퇴 — chat_messages.member_id FK가 남아 있으면 실패한다 (R8)")
                .doesNotThrowAnyException();

        assertThat(memberRepository.findById(member.getId())).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.Disabled("R8 결함 확정(2026-10-21 재현: party_verifications.party_member_id FK 위반, 파티원 삭제 시점) — Phase 3에서 수정 후 활성화")
    @DisplayName("[R8 재현] 파티 인증을 올린 회원도 탈퇴에 성공해야 한다")
    void withdrawShouldSucceedForMemberWithVerification() {
        Member leader = saveMember("leader-verify");
        Member member = saveMember("member-verify");
        Party party = savePartyWithLeader(leader);
        PartyMember partyMember = joinAsMember(member, party);
        saveVerification(party, partyMember);

        assertThatCode(() -> memberService.withdraw(member.getId()))
                .as("인증을 올린 회원의 탈퇴 — party_verifications.party_member_id FK가 남아 있으면 실패한다 (R8)")
                .doesNotThrowAnyException();

        assertThat(memberRepository.findById(member.getId())).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.Disabled("R8 확장 결함 확정(2026-10-21 재현: 일반 파티 탈퇴도 party_verifications FK 위반) — Phase 3에서 수정 후 활성화")
    @DisplayName("[R8 재현·신규 시나리오] 파티 인증을 올린 파티원은 파티 탈퇴에 성공해야 한다")
    void leavePartyShouldSucceedForMemberWithVerification() {
        Member leader = saveMember("leader-leave");
        Member member = saveMember("member-leave");
        Party party = savePartyWithLeader(leader);
        PartyMember partyMember = joinAsMember(member, party);
        saveVerification(party, partyMember);

        assertThatCode(() -> partyService.leaveParty(member.getId(), party.getId()))
                .as("인증 이력이 있는 파티원의 파티 탈퇴 — party_verifications FK가 남아 있으면 실패한다 (R8 확장)")
                .doesNotThrowAnyException();

        assertThat(partyMemberRepository.findByMemberIdAndPartyId(member.getId(), party.getId())).isEmpty();
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

    private void saveVerification(Party party, PartyMember partyMember) {
        partyVerificationRepository.save(PartyVerification.builder()
                .party(party)
                .partyMember(partyMember)
                .imageUrl("https://example.com/verifications/1.jpg")
                .verified(true)
                .verificationDate(LocalDate.now(ZONE_KST))
                .build());
    }
}
