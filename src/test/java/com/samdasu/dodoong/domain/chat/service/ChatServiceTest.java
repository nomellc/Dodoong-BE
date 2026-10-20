package com.samdasu.dodoong.domain.chat.service;

import com.samdasu.dodoong.domain.chat.dto.request.ChatMessageRequest;
import com.samdasu.dodoong.domain.chat.dto.response.ChatHistoryResponse;
import com.samdasu.dodoong.domain.chat.dto.response.ChatRoomResponse;
import com.samdasu.dodoong.domain.chat.entity.ChatMessage;
import com.samdasu.dodoong.domain.chat.event.ChatMessageCreatedEvent;
import com.samdasu.dodoong.domain.chat.repository.ChatMessageRepository;
import com.samdasu.dodoong.domain.member.entity.Member;
import com.samdasu.dodoong.domain.member.repository.MemberRepository;
import com.samdasu.dodoong.domain.party.entity.Party;
import com.samdasu.dodoong.domain.party.repository.PartyMemberRepository;
import com.samdasu.dodoong.domain.party.repository.PartyRepository;
import com.samdasu.dodoong.domain.party.repository.projection.PartyMemberCountProjection;
import com.samdasu.dodoong.global.exception.CustomException;
import com.samdasu.dodoong.global.response.code.ErrorCode;
import com.samdasu.dodoong.global.storage.FileStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ChatService 단위 테스트 (Phase 2).
 * 규칙 출처: docs/analysis/domains/chat.md — SEND는 파티원 검증 후 저장하고
 * 이벤트 발행(브로드캐스트는 AFTER_COMMIT 리스너), 조회는 id DESC 커서 페이징 후 ASC 재정렬,
 * size는 1~50으로 클램프.
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private PartyMemberRepository partyMemberRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private PartyRepository partyRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private FileStorage fileStorage;

    @InjectMocks
    private ChatService chatService;

    private Member createMember(Long id, String nickname) {
        Member member = Member.builder().loginId("login-" + id).encodedPassword("pw").build();
        ReflectionTestUtils.setField(member, "id", id);
        ReflectionTestUtils.setField(member, "nickname", nickname);
        return member;
    }

    private Party createParty(Long id, String name) {
        Party party = Party.builder()
                .name(name)
                .maxMembers(5)
                .isRecruiting(true)
                .isPublic(true)
                .questContent("매일 인증")
                .build();
        ReflectionTestUtils.setField(party, "id", id);
        return party;
    }

    private ChatMessage createMessage(Long id, Party party, Member sender, String content, LocalDateTime createdAt) {
        ChatMessage message = ChatMessage.builder()
                .content(content)
                .party(party)
                .sender(sender)
                .build();
        ReflectionTestUtils.setField(message, "id", id);
        ReflectionTestUtils.setField(message, "createdAt", createdAt);
        return message;
    }

    @Nested
    @DisplayName("sendMessage — 전송")
    class Send {

        @Test
        @DisplayName("파티원이 아니면 PARTY_MEMBER_ONLY로 거부되고 아무것도 저장되지 않는다")
        void rejectsNonPartyMember() {
            when(partyMemberRepository.existsByMemberIdAndPartyId(7L, 3L)).thenReturn(false);

            assertThatThrownBy(() -> chatService.sendMessage(3L, 7L, new ChatMessageRequest("hi")))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.PARTY_MEMBER_ONLY);

            verifyNoInteractions(chatMessageRepository, eventPublisher);
        }

        @Test
        @DisplayName("파티원이면 메시지를 저장하고 커밋 후 브로드캐스트용 이벤트를 발행한다")
        void savesMessageAndPublishesEvent() {
            Member sender = createMember(7L, "은서");
            Party party = createParty(3L, "미라클 모닝");
            ChatMessage saved = createMessage(21L, party, sender, "오늘도 완료!", LocalDateTime.now());

            when(partyMemberRepository.existsByMemberIdAndPartyId(7L, 3L)).thenReturn(true);
            when(memberRepository.findById(7L)).thenReturn(java.util.Optional.of(sender));
            when(partyRepository.getReferenceById(3L)).thenReturn(party);
            when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(saved);

            chatService.sendMessage(3L, 7L, new ChatMessageRequest("오늘도 완료!"));

            ArgumentCaptor<ChatMessageCreatedEvent> captor =
                    ArgumentCaptor.forClass(ChatMessageCreatedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().partyId()).isEqualTo(3L);
            assertThat(captor.getValue().message().messageId()).isEqualTo(21L);
            assertThat(captor.getValue().message().content()).isEqualTo("오늘도 완료!");
        }
    }

    @Nested
    @DisplayName("getChatHistory — 커서 페이징 조회")
    class History {

        @Test
        @DisplayName("파티원이 아니면 조회가 거부된다")
        void rejectsNonPartyMember() {
            when(partyMemberRepository.existsByMemberIdAndPartyId(7L, 3L)).thenReturn(false);

            assertThatThrownBy(() -> chatService.getChatHistory(3L, 7L, null, 20))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.PARTY_MEMBER_ONLY);
        }

        @Test
        @DisplayName("경계값: size는 1~50으로 클램프된다 (100→50, 0→1)")
        void clampsPageSize() {
            when(partyMemberRepository.existsByMemberIdAndPartyId(7L, 3L)).thenReturn(true);
            when(chatMessageRepository.findByPartyId(eq(3L), any(), any(Pageable.class)))
                    .thenReturn(new SliceImpl<>(List.of()));

            chatService.getChatHistory(3L, 7L, null, 100);
            chatService.getChatHistory(3L, 7L, null, 0);

            verify(chatMessageRepository).findByPartyId(eq(3L), any(), eq(PageRequest.of(0, 50)));
            verify(chatMessageRepository).findByPartyId(eq(3L), any(), eq(PageRequest.of(0, 1)));
        }

        @Test
        @DisplayName("저장소의 DESC 결과를 ASC로 재정렬하고, 다음 페이지가 있으면 커서를 내려준다")
        void reordersAscendingAndReturnsNextCursor() {
            Member sender = createMember(7L, "은서");
            Party party = createParty(3L, "미라클 모닝");
            ChatMessage newer = createMessage(22L, party, sender, "두 번째", LocalDateTime.now());
            ChatMessage older = createMessage(21L, party, sender, "첫 번째", LocalDateTime.now().minusMinutes(1));

            when(partyMemberRepository.existsByMemberIdAndPartyId(7L, 3L)).thenReturn(true);
            when(chatMessageRepository.findByPartyId(eq(3L), any(), any(Pageable.class)))
                    .thenReturn(new SliceImpl<>(List.of(newer, older), PageRequest.of(0, 2), true));

            ChatHistoryResponse response = chatService.getChatHistory(3L, 7L, null, 2);

            assertThat(response.messages()).extracting("messageId").containsExactly(21L, 22L);
            assertThat(response.hasNext()).isTrue();
            assertThat(response.nextCursor()).isEqualTo(21L); // DESC 기준 마지막(가장 오래된) id
            assertThat(response.messages().get(0).isMine()).isTrue();
        }

        @Test
        @DisplayName("마지막 페이지면 nextCursor가 null이다")
        void returnsNullCursorOnLastPage() {
            Member sender = createMember(7L, "은서");
            Party party = createParty(3L, "미라클 모닝");
            ChatMessage only = createMessage(21L, party, sender, "유일한 메시지", LocalDateTime.now());

            when(partyMemberRepository.existsByMemberIdAndPartyId(7L, 3L)).thenReturn(true);
            when(chatMessageRepository.findByPartyId(eq(3L), any(), any(Pageable.class)))
                    .thenReturn(new SliceImpl<>(List.of(only), PageRequest.of(0, 20), false));

            ChatHistoryResponse response = chatService.getChatHistory(3L, 7L, null, 20);

            assertThat(response.hasNext()).isFalse();
            assertThat(response.nextCursor()).isNull();
        }
    }

    @Nested
    @DisplayName("getMyChatRooms — 채팅방 목록")
    class ChatRooms {

        @Test
        @DisplayName("가입한 파티가 없으면 빈 목록을 반환한다")
        void returnsEmptyListWithoutParties() {
            when(partyRepository.findAllMyParties(7L)).thenReturn(List.of());

            assertThat(chatService.getMyChatRooms(7L)).isEmpty();
            verifyNoInteractions(chatMessageRepository);
        }

        @Test
        @DisplayName("마지막 메시지가 최신인 방이 먼저 오고, 메시지 없는 방은 뒤로 간다")
        void sortsByLastMessageTimeWithNullsLast() {
            Member sender = createMember(7L, "은서");
            Party active = createParty(3L, "활발한 파티");
            Party silent = createParty(4L, "조용한 파티");
            ChatMessage lastMessage = createMessage(21L, active, sender, "최근 메시지", LocalDateTime.now());

            PartyMemberCountProjection countActive = mock(PartyMemberCountProjection.class);
            when(countActive.getPartyId()).thenReturn(3L);
            when(countActive.getMemberCount()).thenReturn(4L);

            when(partyRepository.findAllMyParties(7L)).thenReturn(List.of(silent, active));
            when(partyMemberRepository.countByPartyIds(List.of(4L, 3L))).thenReturn(List.of(countActive));
            when(chatMessageRepository.findLatestMessagesByPartyIds(List.of(4L, 3L)))
                    .thenReturn(List.of(lastMessage));

            List<ChatRoomResponse> rooms = chatService.getMyChatRooms(7L);

            assertThat(rooms).extracting(ChatRoomResponse::partyId).containsExactly(3L, 4L);
            assertThat(rooms.get(0).lastMessage()).isEqualTo("최근 메시지");
            assertThat(rooms.get(0).memberCount()).isEqualTo(4);
            assertThat(rooms.get(1).lastMessage()).isNull();
            assertThat(rooms.get(1).memberCount()).isZero();
        }
    }
}
