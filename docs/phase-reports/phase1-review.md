# Phase 1 결과 검토 보고서 — 사람 검토용

> 작성: 2026-10-21, Claude Code. 대상 독자: @nomellc.
> 목적: Phase 2 착수 전, Phase 1 산출물을 **단순 요약이 아니라 재검증**하여 (1) 코드로 확인된 사실과 추정을 구분하고 (2) Phase 1 문서 자체의 오류를 찾고 (3) 사람이 승인해야 할 사항을 정리한다.
>
> 작성 방법: 이 보고서를 위해 Phase 1 문서의 핵심 주장 중 위험도가 높은 7건(R1, R7, R8, R9, R10, 레벨업 보상, C3)을 실제 소스와 **재대조**했다. 그 결과 Phase 1 문서의 부정확한 서술 1건과, Phase 1이 놓친 시나리오 1건을 새로 발견했다(§4-3, §4-4).

표기 규칙:
- ✅ **사실** — 코드 원문 또는 실행(테스트)으로 확인됨
- 🔶 **추정** — 코드를 읽고 내린 추론. 실행으로 재현 전까지 결함으로 확정하지 않음
- ❓ **스펙 확인 필요** — 코드는 사실이나, 그것이 "의도"인지는 사람만 판단 가능

---

## 1. 실제로 완료한 작업과 생성한 산출물

| 작업 | 산출물 | 비고 |
|------|--------|------|
| Phase 0 베이스라인 측정 | [docs/baseline/phase0-baseline.md](../baseline/phase0-baseline.md) | ✅ 실측: 빌드 1m13s, 커버리지 L24.0%/B15.3%, 기존 테스트 17건 통과, API p50/p95 응답 |
| JaCoCo 도입 | [build.gradle](../../build.gradle) (커밋 `d6a1384`) | ✅ 실행 확인됨 |
| 아키텍처 개요 | [docs/analysis/00-overview.md](../analysis/00-overview.md) | API 39개 인벤토리, 도메인 의존 그래프 |
| 코드 기준 ERD + README ERD 대조 | [docs/analysis/01-erd.md](../analysis/01-erd.md) | README ERD와 차이 10건 기록 |
| 시퀀스 다이어그램 6종 | [docs/analysis/02-sequences.md](../analysis/02-sequences.md) | Mermaid, GitHub 렌더링 확인은 미수행 |
| 도메인 문서 8건 | [docs/analysis/domains/](../analysis/domains/) | auth/member/quest-routine/streak/party/chat/character/global |
| AI 분석 오류·검증 기록 | [docs/analysis/99-analysis-log.md](../analysis/99-analysis-log.md) | AI 자체 오류 3건 교정 기록 포함 |
| 인수인계 문서 | [docs/HANDOFF.md](../HANDOFF.md) | 작업 원칙·환경·ID 체계의 단일 출처 |

커밋: `d6a1384`(Phase 0), `d323163`(Phase 1) — 현재 `ai-engineering` 브랜치, push는 fork(origin)에만 예정, upstream 접근 없음.

**측정의 한계(정직한 기록)**: 커버리지 24%는 설정 클래스·DTO가 테스트 부수 효과로 로드된 수치를 포함하며, party 도메인을 제외한 서비스 계층의 실질 커버리지는 0%대다. API 응답 시간은 데이터 수십 건 기준이라 절대값 의미가 제한적이다(베이스라인 문서에 명시됨).

---

## 2. 확인된 시스템 구조와 주요 비즈니스 로직 (✅ 코드로 확인)

구조: 단일 모듈 Spring Boot 3.5 / Java 17, Controller→Service→Repository. MySQL 8.4 + Redis 7.4(토큰 저장 전용) + S3. 상세는 [00-overview.md](../analysis/00-overview.md).

핵심 비즈니스 로직과 코드 위치:

| 규칙 | 위치 | 검증 상태 |
|------|------|----------|
| 레벨업: 필요 경험치 `(level+1)*100`, 경험치 이월, 보상 코인 `레벨업 후 레벨×10` (1→2 시 20코인) | [Member.java:66-79](../../src/main/java/com/samdasu/dodoong/domain/member/entity/Member.java) | ✅ 기존 PartyServiceTest 기대값(coin=20)과 교차 확인 |
| 체크 해제 롤백: 경험치 부족 시 레벨 다운 + 지급 코인 회수 | [Member.java:81-96](../../src/main/java/com/samdasu/dodoong/domain/member/entity/Member.java) | ✅ 코드 확인 (음수 코인 가능성은 🔶 R7, §3) |
| 퀘스트 체크 멱등(상태 불변 시 경험치 중복 지급 방지) | DailyQuest `changeChecked` | ✅ 코드 확인 |
| 루틴 "가상 전개": 미래 퀘스트는 DB에 안 만들고 조회 시 계산, 당일분만 자정 스케줄러가 생성(3중 멱등 방어) | [quest-routine.md](../analysis/domains/quest-routine.md) | ✅ 코드 확인 |
| 루틴 삭제: 미체크 퀘스트 삭제, 체크된 퀘스트는 routine_id 끊고 보존 | DailyQuestRepository `deleteUncheckedByRoutineId` / `detachCheckedByRoutineId` | ✅ 코드 확인 |
| 스트릭: 하루 1개+ 완료 시 인정, 7일마다 코인 +10, 평가는 다음날 00:10 배치(실시간 아님) | [streak.md](../analysis/domains/streak.md) | ✅ 코드 확인 |
| 파티: 정원 검증은 count 실측, 모집 상태는 currentMembers 필드(이중 소스 → ❓ R2) | [party.md](../analysis/domains/party.md) | ✅ 코드 확인 |
| 인증: JWT httpOnly 쿠키, refresh 해시 저장 + rotation, access 블랙리스트 | [auth.md](../analysis/domains/auth.md) | ✅ 코드 확인 |
| 채팅: 커밋 후(AFTER_COMMIT) STOMP 브로드캐스트, SEND 시 파티원 검증 | [chat.md](../analysis/domains/chat.md) | ✅ 코드 확인 (SUBSCRIBE는 🔶 R10, §3) |

---

## 3. AI가 내린 추정과 아직 검증되지 않은 내용

아래는 전부 **코드만 읽고 내린 추론**이며, 실행으로 재현된 것이 하나도 없다. 프로젝트 원칙상 재현 전에는 "결함"이 아니라 "잠재적 위험"이다.

| ID | 추정 내용 | 근거 코드 | 재현 방법(Phase 2) |
|----|----------|----------|-------------------|
| 🔶 R7 | 음수 코인: `spendCoin`은 잔액 검사가 없다([Member.java:103-105](../../src/main/java/com/samdasu/dodoong/domain/member/entity/Member.java)). 레벨 다운 시 [Member.java:90](../../src/main/java/com/samdasu/dodoong/domain/member/entity/Member.java)이 잔액 확인 없이 코인을 회수한다. 시나리오: 레벨업(코인 +20) → 캐릭터 구매로 코인 소진 → 퀘스트 체크 해제 → 레벨 다운(코인 -20) → **잔액 음수** | Member.java 재확인 완료 | 단위 테스트 (DB 불필요) |
| 🔶 R8 | 탈퇴/파티탈퇴 FK 위반 — **이번 재검증에서 범위가 수정됨, §4-3 참조** | ChatMessage.java:31, PartyVerification.java:31 | Testcontainers 통합 테스트 |
| 🔶 R9 | 파티 수정 NPE: 요청 DTO의 `isPublic`은 nullable `Boolean`([PartyUpdateRequestDto.java:13](../../src/main/java/com/samdasu/dodoong/domain/party/dto/request/PartyUpdateRequestDto.java))인데 [Party.java:82](../../src/main/java/com/samdasu/dodoong/domain/party/entity/Party.java)에서 `boolean` 필드에 대입 — JSON에 isPublic 생략 시 unboxing NPE → 500 예상 | 재확인 완료 | MockMvc 또는 단위 테스트 |
| 🔶 R10 | 채팅 구독 권한: [WebSocketConfig.java](../../src/main/java/com/samdasu/dodoong/global/config/WebSocketConfig.java)에 inbound 채널 인터셉터가 없어 SUBSCRIBE 시 파티원 검증이 없다. 인증된 회원이면 남의 파티 토픽 구독이 가능해 보임 | 재확인 완료 — `configureClientInboundChannel` 미구현 | STOMP 클라이언트 통합 테스트 |
| 🔶 R3 | 파티 인증 S3 업로드가 재검증 "전" 수행 → 실패 시 고아 객체. 파티 삭제는 트랜잭션 안에서 S3 delete → S3 장애 시 삭제 롤백 | PartyService | 재현 비용 높음 — Phase 3에서 설계 리뷰로 처리 제안 |
| 🔶 R4 | 00:10 스트릭 배치 미실행 시 보정 없음 → 스트릭 영구 리셋 | StreakScheduler | 재현보다 설계 논의가 적절 |
| 🔶 R6 | 비관 락 경합 성능 미측정 | — | Phase 5 |
| 🔶 기타 | 로그인 ~74ms는 BCrypt 비용이라는 가설(프로파일링 안 함), `daily_quests`에 (member_id, quest_date) 인덱스 부재 추정(DDL 미확인) | phase0-baseline.md, quest-routine.md | Phase 5 |

❓ **스펙 확인 필요** (코드는 사실, 의도 여부만 판단 필요 — §6의 결정 사항):
- **R1 → 스펙으로 확정(2026-10-21)**: 파티 검색의 공개 필터 주석 처리([PartyRepositoryImpl.java:30, 43](../../src/main/java/com/samdasu/dodoong/domain/party/repository/PartyRepositoryImpl.java))는 의도된 것 — 비공개 파티도 검색에 노출되고 가입만 비밀번호로 제한. 위험 목록에서 제외.
- **R2 → 결정됨(2026-10-21)**: 이중 소스(검증은 `countByPartyId()` 실측, 표시는 `currentMembers` 필드)는 현 구조 유지. Phase 2에서 가입/탈퇴 시 두 값 일치 검증 테스트를 추가해 어긋남 감지 장치로 삼는다.

---

## 4. 실제 코드와 분석 결과 사이에서 발견한 불일치

### 4-1. Phase 1에서 이미 기록된 것
- **README ERD는 구버전**: `routine.endDate`, `party.currentMembers` 누락 등 10건 — [01-erd.md](../analysis/01-erd.md) §대조표.
- **코드 주석 ≠ 구현**: StreakService 주석은 "일일퀘스트 또는 파티 퀘스트 합산"이라 하나 실제 달성 소스는 DailyQuest 1종뿐(파티 인증은 스트릭 미반영) — [streak.md](../analysis/domains/streak.md).
- **AI 자체 오류 3건 교정**(레벨업 보상 기준 레벨 등) — [99-analysis-log.md](../analysis/99-analysis-log.md) §3.

### 4-2. 이번 재검증으로 "정확함"을 재확인한 것
R1·R7·R9·R10의 코드 근거, 레벨업 보상 공식, prod CORS의 localhost 잔존([application-prod.yml:66](../../src/main/resources/application-prod.yml) — `#TODO: 추후 삭제` 주석까지 있어 팀도 인지한 상태, C3).

### 4-3. ⚠️ 이번 재검증에서 발견한 Phase 1 문서의 오류 (교정 필요)

**[domains/member.md](../analysis/domains/member.md)의 R8 서술이 부정확하다.**

- 문서 주장: "`chat_messages`와 `party_verifications`는 탈퇴 시 삭제하지 않음 → **`member_id` FK**가 남은 채 회원 삭제 시 FK 제약 위반 가능성"
- 실제 코드: `party_verifications`는 `member_id`가 아니라 **`party_member_id`**를 참조한다([PartyVerification.java:31-32](../../src/main/java/com/samdasu/dodoong/domain/party/entity/PartyVerification.java), NOT NULL). 자체 산출물인 [01-erd.md:102](../analysis/01-erd.md)에는 올바르게 그려져 있다 — 도메인 문서와 ERD 문서가 서로 불일치했던 것.
- 따라서 탈퇴 시 FK 위반이 난다면 발생 지점이 다르다: 회원 삭제 시점(chat_messages.member_id, [ChatMessage.java:31](../../src/main/java/com/samdasu/dodoong/domain/chat/entity/ChatMessage.java))이 아니라, 그보다 앞선 **파티원 레코드 삭제 시점**([MemberService.java:205](../../src/main/java/com/samdasu/dodoong/domain/member/service/MemberService.java) `deleteAll`)에 party_verifications가 먼저 터진다. "탈퇴가 실패할 수 있다"는 결론 자체는 유지되나 메커니즘 서술이 틀렸다.

### 4-4. ⚠️ 이번 재검증에서 발견한 신규 시나리오 (Phase 1이 놓친 것)

**일반 파티 탈퇴(`DELETE /api/parties/{id}/leave`)도 같은 위험이 있다.** [PartyService.leaveParty:184-197](../../src/main/java/com/samdasu/dodoong/domain/party/service/PartyService.java)는 PartyMember를 삭제하면서 그 파티원의 `party_verifications`를 정리하지 않는다. 인증을 한 번이라도 올린 파티원은 **회원 탈퇴가 아니라 단순 파티 탈퇴조차 실패**할 가능성이 있다 — 회원 탈퇴보다 훨씬 빈번한 경로이므로 실사용 영향이 더 크다. (🔶 추정 — `ddl-auto: update`가 MySQL InnoDB에 FK 제약을 실제로 생성했는지에 달려 있으며, 이것이 Phase 2 재현 테스트의 1순위 확인 사항)

→ 제안: R8을 "회원 탈퇴 + **파티 탈퇴** 시 verification/chat FK 위반 가능"으로 재정의하고 member.md·HANDOFF를 교정(§6 결정 1).

### 4-5. 그 외 이번에 추가로 관찰한 것 (기록만, 위험 분류 보류)
- [Party.updateParty:80](../../src/main/java/com/samdasu/dodoong/domain/party/entity/Party.java)은 `description`을 무조건 대입 — 수정 요청에서 생략하면 **기존 설명이 null로 덮임**. R9와 묶어 보면 이 API는 "전체 교체(full replace)" 의도로 보이는데, 그렇다면 isPublic만 nullable인 것이 더 부자연스러움. 스펙 판단 시 함께 보면 좋음.
- `PartyUpdateRequestDto.maxMembers`는 primitive `int` + `@Min(2)` — 생략 시 0이 되어 400으로 거부됨(안전). `@NotNull` import는 미사용.

---

## 5. 향후 개선이 필요하다고 판단한 부분과 근거

ID 체계는 [HANDOFF.md](../HANDOFF.md) §5가 단일 출처. 요약과 근거만 재정리:

**확인된 문제 (C — 근거가 실측/원문으로 이미 확보됨)**
| ID | 내용 | 근거 |
|----|------|------|
| C1 | 서비스 계층 테스트 공백 | ✅ JaCoCo 실측: party 외 서비스 0%대 |
| C2 | `ddl-auto: update`, 마이그레이션 도구 없음 — 스키마 변경 이력·롤백 수단 부재 | ✅ application.yml 원문 |
| C3 | prod CORS에 localhost:5173 잔존 — 쿠키 인증+SameSite=None 환경에서 CORS가 사실상 유일한 CSRF 방어라 심각도 높음 | ✅ application-prod.yml:66 |
| C4 | 미사용 쿼리 2건(과거 content 문자열 매칭 설계 흔적) | ✅ 호출부 없음 확인 |
| C5 | `character_items(id=1)` 시드 부재 — 새 환경에서 가입 즉시 실패 | ✅ 로컬에서 실제 재현됨(Phase 0) |
| C6 | CI에 커버리지 보고·배포 헬스체크 없음 | ✅ 워크플로 원문 |

**개선 기회 (O)**: O1 PartyService 636줄 분리, O5 로컬 재현성(시드 스크립트), O6 dev/prod open-in-view 불일치, O8 ZoneId 하드코딩 5곳 등 — 전부 코드 원문 근거, [99-analysis-log.md](../analysis/99-analysis-log.md) §4.

**R 항목은 §3처럼 재현 전까지 "개선 필요"로 승격하지 않는다.**

---

## 6. 사람이 직접 검토하고 승인해야 할 사항

중요도 순. 한 번에 하나씩 진행한다(결정 1부터).

| # | 결정 사항 | 왜 중요한가 | 선택지 |
|---|----------|------------|--------|
| 1 | ~~**Phase 1 문서 교정 승인**~~ → **승인·반영 완료(2026-10-21)**: member.md·99-analysis-log.md·HANDOFF §5에 R8 재정의 반영 | 문서가 단일 출처인데 현재 서술이 부정확 — Phase 2 테스트 설계가 이 서술을 입력으로 사용함 | (a) 교정 반영 ✅ |
| 2 | ~~**R1 스펙 판단**~~ → **스펙으로 확정(2026-10-21)**: 비공개 파티도 검색 노출, 가입만 비밀번호 제한. party.md·HANDOFF에 기록, Phase 2 테스트로 스펙 고정 예정 | Phase 3에서 "수정할 결함"인지 "스펙"인지가 갈림. 코드만으로 판단 불가(주석 처리는 사람이 한 결정) | (a) 의도됨 ✅ |
| 3 | ~~**R2 스펙 판단**~~ → **결정됨(2026-10-21)**: 현 구조 유지 + Phase 2에서 가입/탈퇴 시 `count == currentMembers` 일치 검증 테스트 추가. 단일화는 개선 후보로만 유지 | 동일 | (a) 유지+테스트 ✅ |
| 4 | ~~**Phase 2 착수 승인**~~ → **승인(2026-10-21)**: 순서 ①Member 단위(R7 포함) ②재현 통합(R8·R9·R2 일치) ③AuthService ④DailyQuestService. 재현된 결함 테스트는 `@Disabled`로 증거 보존, R10은 범위 제외(별도 판단) | 70%+ 커버리지 목표의 투입 순서 결정 | (a) 제안대로 ✅ |
| 5 | **운영 방식 변경 반영**: Codex 역할 분담 제외, 작업 전/후 보고 절차를 HANDOFF §3에 명문화 | 이후 모든 세션이 HANDOFF를 따르므로 | (a) 반영 (b) 문구 조정 |
| 6 | (Phase 6 전까지 여유) LLM 제공자·예산 상한 | HANDOFF §6-2 | — |

---

*이 보고서 자체도 AI 산출물이다. §4-3·§4-4의 신규 주장은 위에 적힌 파일:라인을 직접 열어 확인할 수 있게 작성했다.*
