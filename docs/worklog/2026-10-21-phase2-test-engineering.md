# Worklog — Phase 2: AI 테스트 엔지니어링 (2026-10-21)

> 기록 형식: HANDOFF §3 (목적 / AI 사용 이유 / 지시·자료 / 산출물 / 최초안 vs 채택안 / 사람 판단 / 실측 결과 / 성과·한계 / 투입 시간)

## 목적
- 서비스 계층 테스트 공백(C1) 해소: 라인 커버리지 70%+ (베이스라인: 전체 24.0%, party 외 서비스 0%대)
- 잠재 위험 R7(음수 코인)·R8(탈퇴 FK 위반)·R9(isPublic NPE)를 **실패하는 테스트로 재현**해 결함 확정 여부 판단
- R2 결정(2026-10-21) 이행: 정원 이중 소스의 일치 검증 테스트

## AI 사용 이유
- 8개 서비스에 걸친 반복적 테스트 작성은 AI 위임 효율이 높은 작업. 사람은 우선순위·재현 처리 방식 결정과 결과 검증을 담당(결정 4로 사전 승인).

## 지시·자료
- 사람 지시: 우선순위 ①Member 단위 ②재현 통합 ③AuthService ④DailyQuestService, 재현된 결함 테스트는 `@Disabled` 처리, R10은 범위 제외
- 자료: docs/analysis/ 도메인 문서(기대 규칙의 출처), 기존 PartyServiceTest(컨벤션), DodoongApplicationTests(Testcontainers 패턴)

## 산출물 (테스트 109건 신규, 총 126건)
| 파일 | 내용 |
|------|------|
| [MemberTest](../../src/test/java/com/samdasu/dodoong/domain/member/entity/MemberTest.java) | 경험치/레벨/코인 경계값 18건. **R7 재현 포함** |
| [PartyTest](../../src/test/java/com/samdasu/dodoong/domain/party/entity/PartyTest.java) | updateParty·정원 경계값. **R9 재현 포함** |
| [IntegrationTestSupport](../../src/test/java/com/samdasu/dodoong/support/IntegrationTestSupport.java) | 싱글턴 Testcontainers MySQL 공유 베이스 |
| [MemberWithdrawalFkIntegrationTest](../../src/test/java/com/samdasu/dodoong/domain/member/service/MemberWithdrawalFkIntegrationTest.java) | **R8 재현 3경로**(채팅 탈퇴/인증 탈퇴/인증 파티탈퇴) + 정상 탈퇴 |
| [PartyMemberCountConsistencyIntegrationTest](../../src/test/java/com/samdasu/dodoong/domain/party/service/PartyMemberCountConsistencyIntegrationTest.java) | R2 일치 검증(가입/탈퇴/반복) |
| [AuthServiceTest](../../src/test/java/com/samdasu/dodoong/domain/auth/service/AuthServiceTest.java) | 가입(C5 포함)/로그인/로그아웃/재발급 rotation 16건 |
| [DailyQuestServiceTest](../../src/test/java/com/samdasu/dodoong/domain/quest/service/DailyQuestServiceTest.java) | 생성/체크 멱등·레벨 연동/미루기·삭제 규칙/가상 전개 |
| [MemberServiceTest](../../src/test/java/com/samdasu/dodoong/domain/member/service/MemberServiceTest.java) | 프로필 key prefix 보안, afterCommit 삭제 훅, 탈퇴 순서 |
| [StreakServiceTest](../../src/test/java/com/samdasu/dodoong/domain/streak/service/StreakServiceTest.java) | 7일/14일 보상 경계, 끊김/중복 반영 |
| [CharacterServiceTest](../../src/test/java/com/samdasu/dodoong/domain/character/service/CharacterServiceTest.java) | 구매 잔액 경계(정확히/1 부족), 장착 멱등·단일 장착 |
| [ChatServiceTest](../../src/test/java/com/samdasu/dodoong/domain/chat/service/ChatServiceTest.java) | 파티원 검증, 커서 페이징 클램프/재정렬, 채팅방 정렬 |
| [RoutineServiceTest](../../src/test/java/com/samdasu/dodoong/domain/routine/service/RoutineServiceTest.java) | 삭제 시 체크 퀘스트 기록 보존 |

## 재현 결과 — 결함 확정 (전부 실행으로 검증됨)
| ID | 결과 | 증거 |
|----|------|------|
| **R7 확정** | 레벨업 코인(20) 소비 후 체크 해제 → **coin = -20** | MemberTest 재현 테스트 실패 후 `@Disabled` 처리, 현재 동작 고정 테스트 별도 유지 |
| **R8 확정** | ①채팅 남긴 회원 탈퇴: `chat_messages.member_id` FK 위반(회원 삭제 시점) ②인증 올린 회원 탈퇴: `party_verifications.party_member_id` FK 위반(파티원 삭제 시점) ③**인증 올린 파티원의 일반 파티 탈퇴도 동일 실패** | 통합 테스트 3건 모두 `DataIntegrityViolationException` — phase1-review §4-3·4-4의 교정된 메커니즘과 정확히 일치. `ddl-auto`가 FK 제약을 실제 생성함도 확인 |
| **R9 확정** | isPublic 생략(null) 시 unboxing **NPE** | PartyTest 재현 테스트 실패 후 `@Disabled`, NPE 고정 테스트 유지 |
| R2 | 현 구조에서 가입/탈퇴 반복 시 count == currentMembers **일치 확인**(결함 아님) — 감지 테스트로 고정 | 통합 테스트 3건 그린 |

`@Disabled` 5건은 "Phase 3에서 수정 후 활성화"가 명시된 결함 증거 테스트(R7 1, R8 3, R9 1).

## 최초안 vs 채택안
- 최초안에서 R9 재현 테스트를 실행 전에 `@Disabled`로 작성 → **원칙 위반(재현 전 확정 금지)이라 폐기**, 실행해 실패를 확인한 뒤 비활성화로 수정.
- R2 테스트의 cleanUp이 chat/verification을 빼고 삭제해 다른 테스트 클래스의 잔여 행과 FK 충돌 → FK 역순 정리로 수정(결함 아님, 테스트 결함).
- 통합 테스트 컨테이너는 클래스별 `@Container` 대신 싱글턴 공유(기동 1회)로 채택 — 전체 스위트 55초 유지.

## 실측 결과 (`./gradlew test jacocoTestReport`)
- **테스트 126건: 0 실패, 5 skipped(@Disabled 결함 증거)** — 기존 17건 기대값 무수정
- **서비스 계층 라인 커버리지 80.4% (543/675)** — 목표 70% 달성
- 전체 라인 54.5% (베이스라인 24.0%), 전체 브랜치 51.0% (베이스라인 15.3%)

| 패키지 | 베이스라인 | Phase 2 후 |
|--------|-----------|-----------|
| party/service | 73.9% | 73.9% |
| auth/service | 1.5% | 98.5% |
| member/service | 1.5% | 95.6% |
| quest/service | 0.7% | 61.9% |
| chat/service | 0% | 96.1% |
| character/service | 0% | 89.1% |
| routine/service | 0% | 100% |
| streak/service | 0% | 100% |

## 성과·한계
- 성과: R7·R8·R9가 추정에서 **확정 결함**으로 전환(Phase 3 입력), R8은 파티 탈퇴 경로까지 범위 확장. R2는 정상 동작 확인으로 종결.
- 한계: quest/service 61.9%(getCalendar 집계 경로 미커버), PartyService 자체는 추가 테스트 없음(기존 73.9% 유지). R10(STOMP 구독 권한)은 범위 제외로 미검증. PIT(뮤테이션 테스트)는 미수행 — 계획상 선택 항목.
- 통합 테스트는 Hibernate가 생성한 스키마(create-drop) 기준 — 운영 DB(ddl-auto=update 누적 스키마)와 제약이 다를 가능성은 남음.

## 투입 시간
- AI 작업(분석~그린 확인): 약 40분(세션 시간 기준). 사람 검토·결정 대기 별도.
