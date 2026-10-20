-- V3: 회원 탈퇴 시 채팅 보존 + 익명화(R8 결정, 2026-10-21)
-- 탈퇴한 회원의 메시지는 member_id를 NULL로 끊어 보존한다.
ALTER TABLE chat_messages MODIFY member_id bigint NULL;
