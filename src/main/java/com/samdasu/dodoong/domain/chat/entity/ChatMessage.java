package com.samdasu.dodoong.domain.chat.entity;

import com.samdasu.dodoong.domain.member.entity.Member;
import com.samdasu.dodoong.domain.party.entity.Party;
import com.samdasu.dodoong.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "chat_messages")
public class ChatMessage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_message_id")
    private Long id;

    @Column(nullable = false, length = 500)
    private String content;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "party_id", nullable = false)
    private Party party;

    // 회원 탈퇴 시 메시지는 보존되고 sender만 NULL이 된다(익명화, R8 — V3 마이그레이션)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member sender;

    @Builder
    public ChatMessage(String content, Party party, Member sender) {
        this.content = content;
        this.party = party;
        this.sender = sender;
    }
}
