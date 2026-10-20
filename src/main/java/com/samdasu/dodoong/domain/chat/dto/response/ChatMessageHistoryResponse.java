package com.samdasu.dodoong.domain.chat.dto.response;

import com.samdasu.dodoong.domain.chat.entity.ChatMessage;
import com.samdasu.dodoong.domain.member.entity.Member;

import java.time.LocalDateTime;

public record ChatMessageHistoryResponse(
        Long messageId,
        Long senderId,
        String senderNickname,
        String senderProfileImageKey,
        String content,
        boolean isMine,
        LocalDateTime createAt
) {
    public static ChatMessageHistoryResponse of(ChatMessage message, Long viewerId) {
        // sender가 NULL이면 탈퇴한 회원의 메시지(익명화, R8) — 표시 문구는 FE 책임
        Member sender = message.getSender();
        return new ChatMessageHistoryResponse(
                message.getId(),
                sender != null ? sender.getId() : null,
                sender != null ? sender.getNickname() : null,
                sender != null ? sender.getProfileImageKey() : null,
                message.getContent(),
                sender != null && sender.getId().equals(viewerId), // 내 메시지 여부 판단
                message.getCreatedAt()
        );
    }
}
