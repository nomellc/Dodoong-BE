-- V2: 기본 캐릭터 시드 (C5 해결 — 이 행이 없으면 회원가입이 CHARACTER_NOT_FOUND로 실패)
-- 기존 DB(이미 시드가 있는 환경)에서도 안전하도록 중복 시 no-op.
INSERT INTO character_items (character_id, name, price, summary, description)
VALUES (1, '두둥이', 0, '기본 캐릭터', '회원가입 시 지급되는 기본 캐릭터')
ON DUPLICATE KEY UPDATE character_id = character_id;
