package com.samdasu.dodoong.domain.character.service;

import com.samdasu.dodoong.domain.character.dto.response.CharacterDetailResponse;
import com.samdasu.dodoong.domain.character.dto.response.CharacterEquipResponse;
import com.samdasu.dodoong.domain.character.dto.response.CharacterListResponse;
import com.samdasu.dodoong.domain.character.entity.CharacterItem;
import com.samdasu.dodoong.domain.character.entity.MemberCharacter;
import com.samdasu.dodoong.domain.character.repository.CharacterItemRepository;
import com.samdasu.dodoong.domain.character.repository.MemberCharacterRepository;
import com.samdasu.dodoong.domain.member.entity.Member;
import com.samdasu.dodoong.domain.member.repository.MemberRepository;
import com.samdasu.dodoong.global.exception.CustomException;
import com.samdasu.dodoong.global.response.code.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CharacterService 단위 테스트 (Phase 2).
 * 규칙 출처: docs/analysis/domains/character.md — 구매는 중복 보유·잔액 검사 후 코인 차감(장착 안 함),
 * 장착은 멱등이고 기존 장착을 해제한 뒤 1개만 장착.
 */
@ExtendWith(MockitoExtension.class)
class CharacterServiceTest {

    @Mock private CharacterItemRepository characterItemRepository;
    @Mock private MemberCharacterRepository memberCharacterRepository;
    @Mock private MemberRepository memberRepository;

    @InjectMocks
    private CharacterService characterService;

    private Member member;

    @BeforeEach
    void setUp() {
        member = Member.builder().loginId("dodoong").encodedPassword("encodedPassword").build();
        ReflectionTestUtils.setField(member, "id", 7L);
    }

    private CharacterItem characterItem(Long id, int price) {
        CharacterItem item = mock(CharacterItem.class);
        lenient().when(item.getId()).thenReturn(id);
        lenient().when(item.getPrice()).thenReturn(price);
        return item;
    }

    @Nested
    @DisplayName("purchaseCharacter — 구매")
    class Purchase {

        @Test
        @DisplayName("잔액이 충분하면 가격만큼 코인을 차감하고 장착하지 않은 상태로 보유시킨다")
        void purchaseSpendsCoinAndSavesUnequipped() {
            member.earnCoin(50);
            CharacterItem item = characterItem(2L, 30);
            when(memberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(member));
            when(characterItemRepository.findById(2L)).thenReturn(Optional.of(item));
            when(memberCharacterRepository.existsByMemberIdAndCharacterItemId(7L, 2L)).thenReturn(false);

            characterService.purchaseCharacter(7L, 2L);

            assertThat(member.getCoin()).isEqualTo(20);
            ArgumentCaptor<MemberCharacter> captor = ArgumentCaptor.forClass(MemberCharacter.class);
            verify(memberCharacterRepository).save(captor.capture());
            assertThat(captor.getValue().isEquipped()).isFalse();
        }

        @Test
        @DisplayName("경계값: 잔액이 가격과 정확히 같으면 구매할 수 있고 잔액은 0이 된다")
        void purchaseSucceedsWithExactBalance() {
            member.earnCoin(30);
            CharacterItem item = characterItem(2L, 30);
            when(memberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(member));
            when(characterItemRepository.findById(2L)).thenReturn(Optional.of(item));
            when(memberCharacterRepository.existsByMemberIdAndCharacterItemId(7L, 2L)).thenReturn(false);

            characterService.purchaseCharacter(7L, 2L);

            assertThat(member.getCoin()).isZero();
        }

        @Test
        @DisplayName("경계값: 잔액이 1코인이라도 모자라면 INSUFFICIENT_COIN으로 거부되고 코인은 그대로다")
        void purchaseFailsWhenOneCoinShort() {
            member.earnCoin(29);
            CharacterItem item = characterItem(2L, 30);
            when(memberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(member));
            when(characterItemRepository.findById(2L)).thenReturn(Optional.of(item));
            when(memberCharacterRepository.existsByMemberIdAndCharacterItemId(7L, 2L)).thenReturn(false);

            assertThatThrownBy(() -> characterService.purchaseCharacter(7L, 2L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.INSUFFICIENT_COIN);

            assertThat(member.getCoin()).isEqualTo(29);
            verify(memberCharacterRepository, never()).save(any());
        }

        @Test
        @DisplayName("이미 보유한 캐릭터는 ALREADY_OWNED_CHARACTER로 거부된다")
        void purchaseFailsWhenAlreadyOwned() {
            CharacterItem item = characterItem(2L, 30);
            when(memberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(member));
            when(characterItemRepository.findById(2L)).thenReturn(Optional.of(item));
            when(memberCharacterRepository.existsByMemberIdAndCharacterItemId(7L, 2L)).thenReturn(true);

            assertThatThrownBy(() -> characterService.purchaseCharacter(7L, 2L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.ALREADY_OWNED_CHARACTER);
        }

        @Test
        @DisplayName("없는 캐릭터면 CHARACTER_NOT_FOUND 예외가 발생한다")
        void purchaseFailsWhenCharacterMissing() {
            when(memberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(member));
            when(characterItemRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> characterService.purchaseCharacter(7L, 99L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.CHARACTER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("equipCharacter — 장착")
    class Equip {

        @Test
        @DisplayName("기존 장착 캐릭터를 해제하고 대상 캐릭터를 장착한다 (항상 1개 장착)")
        void equipUnequipsPreviousCharacter() {
            CharacterItem target = characterItem(2L, 30);
            MemberCharacter owned = new MemberCharacter(member, target);
            MemberCharacter previouslyEquipped = new MemberCharacter(member, characterItem(1L, 0));
            previouslyEquipped.equip();

            when(memberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(member));
            when(characterItemRepository.findById(2L)).thenReturn(Optional.of(target));
            when(memberCharacterRepository.findByMemberIdAndCharacterItemId(7L, 2L))
                    .thenReturn(Optional.of(owned));
            when(memberCharacterRepository.findEquippedByMemberId(7L))
                    .thenReturn(Optional.of(previouslyEquipped));

            characterService.equipCharacter(7L, 2L);

            assertThat(previouslyEquipped.isEquipped()).isFalse();
            assertThat(owned.isEquipped()).isTrue();
        }

        @Test
        @DisplayName("멱등성: 이미 장착된 캐릭터를 다시 장착하면 아무것도 바꾸지 않고 반환한다")
        void equipIsIdempotent() {
            CharacterItem target = characterItem(2L, 30);
            MemberCharacter owned = new MemberCharacter(member, target);
            owned.equip();

            when(memberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(member));
            when(characterItemRepository.findById(2L)).thenReturn(Optional.of(target));
            when(memberCharacterRepository.findByMemberIdAndCharacterItemId(7L, 2L))
                    .thenReturn(Optional.of(owned));

            CharacterEquipResponse response = characterService.equipCharacter(7L, 2L);

            assertThat(owned.isEquipped()).isTrue();
            assertThat(response).isNotNull();
            verify(memberCharacterRepository, never()).findEquippedByMemberId(any());
        }

        @Test
        @DisplayName("보유하지 않은 캐릭터는 CHARACTER_NOT_OWNED로 거부된다")
        void equipFailsWhenNotOwned() {
            CharacterItem target = characterItem(2L, 30);
            when(memberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(member));
            when(characterItemRepository.findById(2L)).thenReturn(Optional.of(target));
            when(memberCharacterRepository.findByMemberIdAndCharacterItemId(7L, 2L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> characterService.equipCharacter(7L, 2L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.CHARACTER_NOT_OWNED);
        }
    }

    @Nested
    @DisplayName("조회")
    class Queries {

        @Test
        @DisplayName("전체 목록은 보유·장착 여부를 함께 표시한다")
        void listMarksOwnedAndEquipped() {
            CharacterItem ownedItem = characterItem(1L, 0);
            CharacterItem notOwnedItem = characterItem(2L, 30);
            MemberCharacter owned = new MemberCharacter(member, ownedItem);
            owned.equip();
            when(characterItemRepository.findAll()).thenReturn(List.of(ownedItem, notOwnedItem));
            when(memberCharacterRepository.findAllByMemberId(7L)).thenReturn(List.of(owned));

            CharacterListResponse response = characterService.getCharacters(7L);

            assertThat(response.characters()).hasSize(2);
        }

        @Test
        @DisplayName("상세 조회: 보유하지 않은 캐릭터는 owned=false로 응답한다")
        void detailForNotOwnedCharacter() {
            CharacterItem item = characterItem(2L, 30);
            when(characterItemRepository.findById(2L)).thenReturn(Optional.of(item));
            when(memberCharacterRepository.findByMemberIdAndCharacterItemId(7L, 2L))
                    .thenReturn(Optional.empty());

            CharacterDetailResponse response = characterService.getCharacter(7L, 2L);

            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("장착 캐릭터가 없으면 EQUIPPED_CHARACTER_NOT_FOUND 예외가 발생한다")
        void equippedQueryFailsWhenNothingEquipped() {
            when(memberCharacterRepository.findEquippedByMemberId(7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> characterService.getEquippedCharacter(7L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.EQUIPPED_CHARACTER_NOT_FOUND);
        }
    }
}
