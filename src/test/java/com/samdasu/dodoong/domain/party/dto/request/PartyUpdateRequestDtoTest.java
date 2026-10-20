package com.samdasu.dodoong.domain.party.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R9 수정 검증 (Phase 3 — 2026-10-21 결정 7).
 * 파티 수정은 전체 교체(full-replace) 계약: isPublic 생략 시
 * unboxing NPE(500)가 아니라 Bean Validation(400)에서 거부돼야 한다.
 * 컨트롤러는 @Valid를 사용하므로(PartyController.updateParty) 이 검증이 요청 경로에서 작동한다.
 */
class PartyUpdateRequestDtoTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("[R9 수정 검증] isPublic이 없으면(null) 검증 오류로 거부된다")
    void rejectsNullIsPublic() {
        PartyUpdateRequestDto dto = new PartyUpdateRequestDto("설명만 수정", 5, null, null);

        Set<ConstraintViolation<PartyUpdateRequestDto>> violations = validator.validate(dto);

        assertThat(violations)
                .as("Phase 2에서 unboxing NPE(500)로 재현됐던 결함 (R9) — 이제 400으로 거부")
                .anyMatch(v -> v.getPropertyPath().toString().equals("isPublic"));
    }

    @Test
    @DisplayName("isPublic이 있으면 검증을 통과한다")
    void acceptsExplicitIsPublic() {
        PartyUpdateRequestDto dto = new PartyUpdateRequestDto("설명", 5, true, null);

        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("기존 검증 규칙 유지: maxMembers는 2 미만이면 거부된다")
    void rejectsMaxMembersBelowTwo() {
        PartyUpdateRequestDto dto = new PartyUpdateRequestDto("설명", 1, true, null);

        assertThat(validator.validate(dto))
                .anyMatch(v -> v.getPropertyPath().toString().equals("maxMembers"));
    }
}
