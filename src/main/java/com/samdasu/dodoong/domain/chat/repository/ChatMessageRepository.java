package com.samdasu.dodoong.domain.chat.repository;

import com.samdasu.dodoong.domain.chat.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    // sender가 NULL(탈퇴·익명화)인 메시지도 내역에 포함되도록 LEFT JOIN (R8)
    @Query("""
    SELECT m FROM ChatMessage m
    LEFT JOIN FETCH m.sender
    WHERE m.party.id = :partyId
        AND (:cursor IS NULL or m.id < :cursor)
    ORDER BY m.id DESC
    """)
    Slice<ChatMessage> findByPartyId(
            @Param("partyId") Long partyId,
            @Param("cursor") Long cursor,
            Pageable pageable
    );

    @Query("""
    SELECT m FROM ChatMessage m
    WHERE m.id IN (
        SELECT MAX(m2.id)
        FROM ChatMessage m2
        WHERE m2.party.id IN :partyIds
        GROUP BY m2.party.id)
    """)
    List<ChatMessage> findLatestMessagesByPartyIds(
            @Param("partyIds") List<Long> partyIds
    );

    @Modifying
    @Query("DELETE FROM ChatMessage c WHERE c.party.id = :partyId")
    void deleteByPartyId(@Param("partyId") Long partyId);

    // 회원 탈퇴 시 메시지는 보존하고 보낸 사람만 끊는다(익명화, R8)
    // clearAutomatically는 쓰지 않는다 — 호출 흐름에서 관리 중인 다른 엔티티(파티 등)의 변경을 날릴 수 있음
    @Modifying(flushAutomatically = true)
    @Query("UPDATE ChatMessage c SET c.sender = NULL WHERE c.sender.id = :memberId")
    void detachSenderByMemberId(@Param("memberId") Long memberId);
}
