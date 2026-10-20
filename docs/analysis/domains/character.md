# character — 캐릭터 상점·장착

## 책임
캐릭터 카탈로그 조회, 보유/장착 상태 조회, 코인으로 구매, 장착 변경.

## 엔드포인트 (6개)
GET /api/characters(전체+보유 여부), GET /{id}, GET /me(보유 목록), GET /me/equipped, POST /{id}/purchase, PATCH /{id}/equip

## 규칙
- 구매: 회원 비관적 락 → 중복 보유 검사 → 잔액 검사(`hasEnoughCoin`) → `spendCoin` → MemberCharacter 생성(장착 안 함).
- 장착: 보유 검사 → 이미 장착이면 그대로 반환(멱등) → 기존 장착 해제 후 장착. **"장착은 항상 1개"가 쿼리(`findEquippedByMemberId`)와 로직으로만 보장** — DB 제약 없음(부분 unique 인덱스가 MySQL엔 없으므로 구조적 한계).
- 가입 시 id=1 캐릭터 자동 지급+장착(auth 도메인에서 수행).

## 데이터 특성
- `CharacterItem`은 운영자가 미리 넣는 **마스터 데이터** — 생성 API 없음, 시드 필요(C5).
- `CharacterItem`/`MemberCharacter`는 BaseTimeEntity 미상속 — 감사 컬럼 없음(다른 엔티티와 비일관).

## 관찰
- 장착 조회 실패 시 EQUIPPED_CHARACTER_NOT_FOUND 404류 응답 — 가입 로직상 발생하지 않아야 하나, 데이터 수동 조작 시 가능.
- `findEquippedByMemberIds`(파티 랭킹용 벌크 조회)는 party 도메인이 사용 — 도메인 간 조회 의존.
