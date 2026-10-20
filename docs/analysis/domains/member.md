# member — 회원·프로필·레벨

## 책임
내 정보 조회, 프로필 수정(닉네임/소개/이미지), 프로필 이미지 Presigned URL 발급, 회원 탈퇴(연관 데이터 정리). **레벨·경험치·코인 규칙은 `Member` 엔티티 안에 있음.**

## 엔드포인트
GET /api/members/me · PATCH /api/members/me · DELETE /api/members/me · POST /api/members/me/profile-image/upload-url

## 도메인 규칙 (Member 엔티티 — Phase 2 단위 테스트 1순위)
- `addExperience(n)`: 누적 후 `experience >= (level+1)*100`인 동안 반복 레벨업. 레벨업마다 코인 `+level*10`(레벨업 **후** 레벨 기준 — 1→2 시 20코인), 경험치 이월.
- `subtractExperience(n)`: 체크 해제 롤백용. 경험치가 모자라면 레벨 다운하며 **지급했던 코인도 회수**(`spendCoin(levelUpReward())`). 코인 잔액 검사 없이 차감하므로 **코인이 음수가 될 수 있음** — 캐릭터 구매로 코인을 쓴 뒤 레벨 다운되는 시나리오. 경계 테스트 후보(잠재적 위험 R7로 추가).
- `spendCoin`은 잔액 검증을 호출부(`hasEnoughCoin`)에 위임 — 엔티티 불변식이 아님.

## 프로필 이미지 흐름 (Presigned PUT 방식)
1. `POST .../upload-url` → S3 Presigned PUT URL + key 발급 (`profiles/{memberId}/{uuid}.ext`, 5분 유효)
2. FE가 S3에 직접 업로드
3. `PATCH /me`에 key 전달 → **key가 `profiles/{memberId}/` prefix인지 검증**(타인 경로 탈취 방지) → 저장
4. 이미지 교체 시 이전 객체는 `afterCommit` 훅에서 삭제(실패해도 경고 로그만) — 트랜잭션·외부 I/O 분리의 모범 사례. 단, **발급만 받고 PATCH하지 않으면 S3 고아 객체** 발생(정리 배치 없음).

## 탈퇴 로직 (withdraw)
- 파티장인 파티가 있으면 거부(PARTY_LEADER_CANNOT_WITHDRAW).
- 일반 파티원 레코드 삭제 + 각 파티 `currentMembers` 감소(비관적 락).
- quest/routine/streak/memberCharacter를 벌크 삭제 후 회원 삭제.
- **주의**: `chat_messages`와 `party_verifications`는 탈퇴 시 삭제하지 않음 → `member_id` FK가 남은 채 회원 삭제 시 **FK 제약 위반 가능성**. 채팅을 남긴 회원의 탈퇴가 실제로 성공하는지 Phase 2 통합 테스트로 검증 필요(잠재적 위험 R8로 추가).

## 의존성
party(탈퇴 시), quest/routine/streak/character 리포지토리 직접 참조 — member가 타 도메인 테이블을 직접 정리하는 구조(도메인 이벤트 미사용).
