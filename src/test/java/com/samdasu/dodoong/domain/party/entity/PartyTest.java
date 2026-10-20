package com.samdasu.dodoong.domain.party.entity;

import com.samdasu.dodoong.domain.party.dto.request.PartyUpdateRequestDto;
import com.samdasu.dodoong.global.exception.CustomException;
import com.samdasu.dodoong.global.response.code.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Party 엔티티 단위 테스트 (Phase 2).
 * R9(파티 수정 isPublic 누락 NPE) 재현 포함 — docs/analysis/domains/party.md.
 */
class PartyTest {

    private Party createParty(int maxMembers, boolean isPublic, String password) {
        return Party.builder()
                .name("미라클 모닝")
                .description("아침 기상 인증 파티")
                .maxMembers(maxMembers)
                .isRecruiting(true)
                .isPublic(isPublic)
                .partyPassword(password)
                .questContent("아침 7시 기상 인증")
                .build();
    }

    @Nested
    @DisplayName("updateParty — 파티 수정")
    class UpdateParty {

        @Test
        @DisplayName("isPublic이 포함된 요청은 정상 반영된다")
        void updatesWithExplicitIsPublic() {
            Party party = createParty(5, true, null);
            PartyUpdateRequestDto dto = new PartyUpdateRequestDto("새 설명", 4, false, "1234");

            party.updateParty(dto, "$2a$10$encoded");

            assertThat(party.getDescription()).isEqualTo("새 설명");
            assertThat(party.getMaxMembers()).isEqualTo(4);
            assertThat(party.isPublic()).isFalse();
            assertThat(party.getPartyPassword()).isEqualTo("$2a$10$encoded");
        }

        @Test
        @DisplayName("공개로 전환하면 비밀번호가 제거된다")
        void clearsPasswordWhenTurningPublic() {
            Party party = createParty(5, false, "$2a$10$old");
            PartyUpdateRequestDto dto = new PartyUpdateRequestDto("설명", 5, true, null);

            party.updateParty(dto, null);

            assertThat(party.isPublic()).isTrue();
            assertThat(party.getPartyPassword()).isNull();
        }

        @Test
        @DisplayName("maxMembers를 현재 인원보다 작게 줄이면 INVALID_MAX_MEMBERS 예외가 발생한다")
        void rejectsMaxMembersBelowCurrentMembers() {
            Party party = createParty(5, true, null);
            party.increaseCurrentMembers(); // currentMembers = 2
            PartyUpdateRequestDto dto = new PartyUpdateRequestDto("설명", 1, true, null);

            assertThatThrownBy(() -> party.updateParty(dto, null))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.INVALID_MAX_MEMBERS);
        }

        // R9 수정(2026-10-21 결정 7): isPublic 생략은 엔티티에 도달하기 전에 @NotNull 검증(400)으로
        // 거부된다. Phase 2의 "NPE 재현/현재 동작 기록" 테스트는 PartyUpdateRequestDtoTest의
        // Bean Validation 테스트로 교체됨.
    }

    @Nested
    @DisplayName("정원 증감과 모집 상태")
    class Capacity {

        @Test
        @DisplayName("정원이 가득 차면 increaseCurrentMembers가 PARTY_FULL 예외를 던진다")
        void throwsWhenPartyIsFull() {
            Party party = createParty(2, true, null);
            party.increaseCurrentMembers(); // 2/2

            assertThatThrownBy(party::increaseCurrentMembers)
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.PARTY_FULL);
        }

        @Test
        @DisplayName("정원 도달 시 모집이 마감되고, 빈자리가 생기면 다시 모집 중이 된다")
        void updatesRecruitingStatusOnBoundary() {
            Party party = createParty(2, true, null);

            party.increaseCurrentMembers(); // 2/2
            assertThat(party.isRecruiting()).isFalse();

            party.decreaseCurrentMembers(); // 1/2
            assertThat(party.isRecruiting()).isTrue();
        }

        @Test
        @DisplayName("currentMembers는 파티장 1명 아래로 내려가지 않는다")
        void neverDecreasesBelowLeader() {
            Party party = createParty(5, true, null);

            party.decreaseCurrentMembers();

            assertThat(party.getCurrentMembers()).isEqualTo(1);
        }
    }
}
