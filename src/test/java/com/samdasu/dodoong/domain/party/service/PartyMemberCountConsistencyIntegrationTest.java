package com.samdasu.dodoong.domain.party.service;

import com.samdasu.dodoong.domain.member.entity.Member;
import com.samdasu.dodoong.domain.member.repository.MemberRepository;
import com.samdasu.dodoong.domain.party.dto.request.PartyJoinRequest;
import com.samdasu.dodoong.domain.party.entity.Party;
import com.samdasu.dodoong.domain.party.entity.PartyMember;
import com.samdasu.dodoong.domain.party.entity.PartyRole;
import com.samdasu.dodoong.domain.party.repository.PartyMemberRepository;
import com.samdasu.dodoong.domain.party.repository.PartyRepository;
import com.samdasu.dodoong.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2 일치 검증 테스트 (Phase 2 — 2026-10-21 결정: 이중 소스 구조 유지 + 감지 장치).
 * 파티 정원은 두 곳에 존재한다: 가입 검증은 party_members 실측 count,
 * 모집 상태는 parties.currentMembers 필드. 이 테스트는 가입/탈퇴 후
 * 두 값이 일치함을 고정해, 어긋나게 만드는 변경이 들어오면 실패한다.
 */
class PartyMemberCountConsistencyIntegrationTest extends IntegrationTestSupport {

    @Autowired private PartyService partyService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PartyRepository partyRepository;
    @Autowired private PartyMemberRepository partyMemberRepository;

    @Autowired private com.samdasu.dodoong.domain.chat.repository.ChatMessageRepository chatMessageRepository;
    @Autowired private com.samdasu.dodoong.domain.party.repository.PartyVerificationRepository partyVerificationRepository;

    @BeforeEach
    void cleanUp() {
        // 같은 컨테이너를 공유하는 다른 테스트 클래스가 남긴 행까지 FK 역순으로 정리
        chatMessageRepository.deleteAll();
        partyVerificationRepository.deleteAll();
        partyMemberRepository.deleteAll();
        partyRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("가입 후 currentMembers 필드와 party_members 실측 count가 일치한다")
    void currentMembersMatchesCountAfterJoin() {
        Party party = createPartyWithLeader("r2-leader-join");
        Member joiner = saveMember("r2-member-join");

        partyService.joinParty(joiner.getId(), party.getId(), new PartyJoinRequest(null));

        assertConsistent(party.getId(), 2);
    }

    @Test
    @DisplayName("파티 탈퇴 후에도 두 값이 일치한다")
    void currentMembersMatchesCountAfterLeave() {
        Party party = createPartyWithLeader("r2-leader-leave");
        Member joiner = saveMember("r2-member-leave");
        partyService.joinParty(joiner.getId(), party.getId(), new PartyJoinRequest(null));

        partyService.leaveParty(joiner.getId(), party.getId());

        assertConsistent(party.getId(), 1);
    }

    @Test
    @DisplayName("가입-탈퇴를 반복해도 두 값이 일치하고 모집 상태도 필드 기준과 맞는다")
    void staysConsistentAcrossJoinLeaveCycles() {
        Party party = createPartyWithLeader("r2-leader-cycle");
        Member m1 = saveMember("r2-member-c1");
        Member m2 = saveMember("r2-member-c2");

        partyService.joinParty(m1.getId(), party.getId(), new PartyJoinRequest(null));
        partyService.joinParty(m2.getId(), party.getId(), new PartyJoinRequest(null));
        partyService.leaveParty(m1.getId(), party.getId());

        assertConsistent(party.getId(), 2);
        Party reloaded = partyRepository.findById(party.getId()).orElseThrow();
        assertThat(reloaded.isRecruiting())
                .isEqualTo(reloaded.getCurrentMembers() < reloaded.getMaxMembers());
    }

    private void assertConsistent(Long partyId, int expected) {
        Party reloaded = partyRepository.findById(partyId).orElseThrow();
        long actualCount = partyMemberRepository.countByPartyId(partyId);

        assertThat(reloaded.getCurrentMembers())
                .as("currentMembers 필드와 party_members 실측 count는 항상 일치해야 한다 (R2)")
                .isEqualTo((int) actualCount)
                .isEqualTo(expected);
    }

    private Member saveMember(String loginId) {
        return memberRepository.save(Member.builder()
                .loginId(loginId)
                .encodedPassword("encodedPassword")
                .build());
    }

    private Party createPartyWithLeader(String leaderLoginId) {
        Member leader = saveMember(leaderLoginId);
        Party party = partyRepository.save(Party.builder()
                .name("알고리즘 스터디")
                .maxMembers(3)
                .isRecruiting(true)
                .isPublic(true)
                .questContent("매일 1문제 풀기")
                .build());
        partyMemberRepository.save(PartyMember.builder()
                .role(PartyRole.LEADER)
                .member(leader)
                .party(party)
                .build());
        return party;
    }
}
