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
- **주의(R8 — 2026-10-21 교정, [phase1-review §4-3·4-4](../../phase-reports/phase1-review.md))**: 탈퇴가 `chat_messages`와 `party_verifications`를 정리하지 않는다. 단, 두 테이블의 FK는 서로 다르다:
  - `chat_messages.member_id`(NOT NULL, ChatMessage.java:31) → **회원 삭제 시점**(MemberService.java:110)에 위반 가능.
  - `party_verifications.party_member_id`(NOT NULL, PartyVerification.java:31 — ~~member_id 참조라던 최초 서술은 오류~~, 01-erd.md에는 올바르게 기재) → 회원 삭제보다 앞선 **파티원 레코드 삭제 시점**(MemberService.java:205 `deleteAll`)에 위반 가능.
  - **범위 확장**: 같은 이유로 일반 **파티 탈퇴**(PartyService.leaveParty:195)도 인증 이력이 있는 파티원이면 실패할 수 있음 — 회원 탈퇴보다 빈번한 경로라 영향이 더 큼. Phase 1 최초 분석이 놓친 시나리오.
  - 전부 코드 추론(FK 제약은 `ddl-auto: update`가 실제 생성했는지에 의존) — Phase 2 통합 테스트로 재현 전까지 결함 미확정.

## 의존성
party(탈퇴 시), quest/routine/streak/character 리포지토리 직접 참조 — member가 타 도메인 테이블을 직접 정리하는 구조(도메인 이벤트 미사용).
