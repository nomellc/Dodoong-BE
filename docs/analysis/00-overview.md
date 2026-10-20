# Dodoong 백엔드 아키텍처 개요

> Phase 1 산출물. 기준 커밋: `d6a1384` (develop). 작성: 2026-10-21, Claude Code 분석 후 사람 검토 전.

## 1. 시스템 한눈에 보기

```
[FE (Vercel)] ──HTTPS──> [Nginx 1.27] ──> [Spring Boot 3.5 (8080)]
[FE WebSocket] ──WSS /ws──┘                    │        │
                                          [MySQL 8.4] [Redis 7.4]
                                               │
                                          [AWS S3] (이미지)
```

- 단일 모듈 Spring Boot 애플리케이션. 전형적인 Controller → Service → Repository 계층 구조.
- 인증: JWT를 **httpOnly 쿠키**(`accessToken`/`refreshToken`)로 주고받음. Redis에 Refresh Token(SHA-256 해시)과 Access Token 블랙리스트 저장.
- 실시간: STOMP over WebSocket(`/ws`), simple broker(`/topic`), 발행 prefix `/app`.
- 배치: `@Scheduled` 2개 — 자정(루틴→오늘 퀘스트 생성), 00:10(전날 스트릭 평가). 둘 다 Asia/Seoul 기준.
- 파일: S3 직접 업로드(파티/인증 이미지) + Presigned PUT(프로필 이미지)의 혼합 방식.

## 2. 도메인 구성과 의존 방향

| 도메인 | 책임 | 의존하는 도메인 |
|--------|------|----------------|
| auth | 가입/로그인/로그아웃/재발급, JWT, 토큰 저장소 | member, character(기본 캐릭터 지급) |
| member | 프로필 조회/수정, 탈퇴(연관 데이터 정리) | party, quest, routine, streak, character |
| quest | 일일 퀘스트 CRUD, 캘린더/사분면, 경험치 부여 | member, routine |
| routine | 반복 규칙 저장, 루틴 삭제 시 퀘스트 정리 | quest(repository), member |
| streak | 연속 달성 계산, 7일마다 코인 보상 | member, quest(AchievementSource 경유) |
| party | 파티 CRUD/가입/탈퇴, 일일 인증, 월간 랭킹 | member, character, chat(삭제 시 정리) |
| chat | 파티 채팅 전송/조회, 채팅방 목록 | party, member |
| character | 캐릭터 목록/구매/장착 | member |

의존 그래프상 **member가 허브**이고, `party ↔ chat`, `quest ↔ routine`이 상호 참조합니다. 모듈 경계는 패키지 수준일 뿐 강제 장치(ArchUnit 등)는 없습니다.

특기할 설계:
- `streak`은 `QuestAchievementSource` 인터페이스(목록 주입)로 달성 소스를 추상화 — 현재 구현체는 `DailyQuestAchievementSource` 1개.
- `chat`은 저장 후 `ChatMessageCreatedEvent`를 발행하고 `@TransactionalEventListener(AFTER_COMMIT)`에서 STOMP 브로드캐스트 — 커밋 전 브로드캐스트를 방지하는 올바른 패턴.
- 동시성 제어는 비관적 락(`PESSIMISTIC_WRITE`, timeout 3s)을 member/party/party_member 조회에 사용.

## 3. API 인벤토리 (39개)

| 영역 | 엔드포인트 |
|------|-----------|
| auth (4) | POST /api/auth/{signup, login, logout, reissue} |
| member (4) | GET·PATCH·DELETE /api/members/me, POST /api/members/me/profile-image/upload-url |
| quest (9) | POST·GET /api/daily-quests, GET /calendar, GET /quadrants, GET /quadrants/{category}, PATCH /{id}, PATCH /{id}/check, PATCH /{id}/postpone, DELETE /{id} |
| routine (1) | DELETE /api/routines/{routineId} |
| streak (1) | GET /api/streaks |
| character (6) | GET /api/characters, GET /{id}, GET /me, GET /me/equipped, POST /{id}/purchase, PATCH /{id}/equip |
| party (12) | POST·PATCH /api/parties, DELETE·GET /{partyId}, GET /my, GET /search, POST /{partyId}(가입), DELETE /{partyId}/leave, GET /{partyId}/me/monthly, GET /{partyId}/ranking(s)/monthly, GET /{partyId}/verifications, POST /{partyId}/verifications |
| chat (2 + WS) | GET /api/parties/{partyId}/chats, GET /api/chat-rooms, STOMP SEND /app/parties/{partyId}/chat |

인증 예외(permitAll): login/signup/reissue, Swagger, `/actuator/health`, `/ws/**`(단, WebSocket은 핸드셰이크 인터셉터에서 쿠키 JWT 검증).

## 4. 공통 규약

- 응답: `BaseResponse<T>`(code/message/data) + `BaseResponseStatusAdvice`가 HTTP 상태를 코드에 맞춤.
- 예외: `CustomException(ErrorCode)` → `GlobalExceptionHandler`. 락 타임아웃, 검증 오류 등 매핑 존재. 최후순위 핸들러가 500 + 에러 로그.
- 엔티티: `BaseTimeEntity`(createdAt/updatedAt, JPA Auditing) 상속. `CharacterItem`, `MemberCharacter`는 예외적으로 미상속.
- 시간대: 비즈니스 로직 전반이 `Asia/Seoul` 고정(`ZoneId.of("Asia/Seoul")` 하드코딩이 서비스 3곳 + 스케줄러 2곳에 중복).

## 5. 게이미피케이션 규칙 (코드에서 확인된 것)

- 퀘스트/파티 인증 완료 시 경험치 +10. 체크 해제 시 -10(레벨 다운 롤백 포함, `Member.subtractExperience`).
- 레벨업 필요 경험치: `(level+1) * 100`. 레벨업 보상 코인: `level * 10` (레벨업 **후** 레벨 기준 — 1→2 레벨업 시 20코인, 기존 테스트로 확인됨).
- 스트릭: 하루 1개 이상 완료 시 인정, 7일 연속마다 코인 +10. 평가는 다음날 00:10 배치에서만 수행(실시간 아님).
- 파티 인증: 파티원당 하루 1회(unique 제약), 이미지 필수.

## 6. 관련 문서

- [01-erd.md](01-erd.md) — 실제 코드 기준 ERD와 README ERD 대조
- [02-sequences.md](02-sequences.md) — 핵심 플로우 시퀀스 다이어그램
- [domains/](domains/) — 모듈별 기술 문서
- [99-analysis-log.md](99-analysis-log.md) — AI 분석 검증 기록(오류·불일치 목록)
- 개선 후보 ID(C/R/O)는 사전 분석 보고서와 [../baseline/phase0-baseline.md](../baseline/phase0-baseline.md) 참조
