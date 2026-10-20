package com.samdasu.dodoong.domain.party.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PartyUpdateRequestDto(
        String description,

        @Min(value = 2, message = "최대 인원은 최소 2명 이상이어야 합니다.")
        int maxMembers,

        // 이 API는 전체 교체(full-replace) 계약 — 생략 시 unboxing NPE(500)가 나던 것을
        // 검증 오류(400)로 바꾼다 (R9 수정 — 2026-10-21 결정)
        @NotNull(message = "isPublic은 필수입니다.")
        Boolean isPublic,

        @Size(min = 4, message = "비밀번호는 4자 이상이여야 합니다.")
        String partyPassword
) {
}