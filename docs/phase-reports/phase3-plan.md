# Phase 3 작업 전 보고 — 확정 결함(R7·R8·R9) 수정 계획

> 2026-10-21 작성, 착수 전 승인 대기. 대상: Phase 2에서 실행 재현으로 확정된 결함 3건.
> 완료 기준: `@Disabled` 증거 테스트 5건을 **활성화해 그린**으로 만들고, 전체 스위트 그린 유지, worklog 기록.

## 해결하려는 문제
Phase 2에서 결함으로 확정된 3건을 수정한다. 셋 다 "코드 한 줄" 고치기 전에 **제품 정책을 먼저 정해야 하는** 결정이 하나씩 붙어 있다 — 그래서 결정 5·6·7로 나눠 하나씩 승인받는다.

## 예상 변경 범위
- `Member.subtractExperience` (R7) / `MemberService.withdraw`·`PartyService.leaveParty` (R8) / `PartyUpdateRequestDto` 또는 `Party.updateParty` (R9)
- `@Disabled` 테스트 5건 활성화 + 정책 변경 시 Phase 2 테스트 일부 기대값 갱신(변경 건마다 이유·영향 설명 후 반영 — "현재 동작 기록" 테스트는 정책이 바뀌면 함께 바뀌는 것이 설계 의도)
- ~~DB 스키마 변경은 없음을 원칙~~ → **결정 6(채팅 보존)에 따라 Flyway 정석 도입 + 스키마 마이그레이션 포함으로 확장**(2026-10-21): build.gradle, application*.yml, `src/main/resources/db/migration/V1~V3`, IntegrationTestSupport. C2(마이그레이션 도구 부재)·C5(시드 부재)가 함께 해결됨

---

## 결정 5 — R7: 음수 코인, 어떤 정책으로 막을까

**현재 동작**: 체크 해제로 레벨 다운하면 레벨업 보상 코인을 잔액 검사 없이 회수 → 이미 쓴 경우 음수.

**중요한 전제 — 왜 단순 "0 하한(클램프)"은 위험한가**: 코인 회수는 **코인 복제 악용을 막는 장치**다. 회수를 없애거나 0으로 클램프하면: 체크(레벨업, +20) → 코인 소비 → 해제(회수 실패, 0 유지) → 재체크(다시 레벨업, +20) → … 반복으로 **코인 무한 생성**이 가능해진다. 그래서 선택지는 사실상 셋이다.

| 선택지 | 내용 | 장점 | 단점 |
|--------|------|------|------|
| (a) **레벨 다운 폐지** | 체크 해제 시 경험치만 차감(현재 레벨의 0 하한), 레벨·코인은 유지 | 음수 불가능, 복제 불가능(레벨 경계를 다시 넘지 않으므로 보상 재지급 없음), 구현 단순, 흔한 게임 UX | 해제해도 레벨이 유지되는 "약간의 이득"이 사용자에게 남음. Phase 2 테스트 ~5건 기대값 변경 |
| (b) 현 동작 명세화 | 음수 잔액을 "부채"로 허용(구매는 이미 `hasEnoughCoin`으로 차단됨) | 코드 무변경, 경제적으로 가장 정확 | FE/UI가 음수 표시를 처리해야 함, 직관에 반함 — 결함이 아니라 스펙이 됨 |
| (c) 잔액 부족 시 체크 해제 거부 | 회수 불가능하면 에러 응답 | 정합성 완벽 | "실수로 체크한 걸 해제 못 하는" 나쁜 UX, 에러 코드 추가 |

**추천: (a)** — 음수와 복제를 동시에 없애는 유일한 단순안. 근거: 회수 로직의 존재 이유(복제 방지)를 레벨 유지가 대신 충족하면서 UX 손해가 없다.
**검증**: R7 `@Disabled` 테스트 활성화(음수 불가) + 복제 시나리오 테스트 신규 작성(체크→해제→재체크 시 코인 증가 없음).

> ✅ **결정 5 승인(2026-10-21 @nomellc): (a) 레벨 다운 폐지** — 체크 해제 시 경험치만 차감(0 하한), 레벨·코인 유지.

## 결정 6 — R8: 탈퇴·파티탈퇴 시 채팅/인증 레코드 처리

> ✅ **방향 결정(2026-10-21 @nomellc): (b) 채팅 보존 + 익명화, 선행 작업(Flyway 도입) 포함.** 아래는 그에 따라 다시 잡은 계획. (최초 제안 (a) 삭제 통일안은 이력으로 유지: 파티 삭제가 이미 삭제 정책이라 일관적이었으나, 대화 맥락 보존을 우선하기로 결정.)

**목표 동작**:
- 회원 탈퇴 시 채팅 메시지는 남고, 보낸 사람만 끊긴다(`member_id = NULL`). 응답에서는 senderId/닉네임이 null → FE가 "탈퇴한 사용자"로 표시.
- 인증(party_verifications)은 **파티원 삭제 시 함께 삭제** 제안 — 인증은 특정 파티원 자격(party_member)에 종속된 기록이고, 파티 삭제가 이미 인증을 삭제하며, 탈퇴자는 랭킹 명단에서도 빠지므로 보존 실익이 없음. (보존하려면 party_member까지 남겨야 해서 soft delete급 설계가 됨 — 범위 초과)

### 선행 작업: Flyway 도입 방식 (C2 해결을 겸함)

**왜 "최소 도입(하이브리드)"은 안 되는가**: Flyway는 애플리케이션 시작 시 JPA보다 먼저 실행된다. `ddl-auto: update`를 유지한 채 "ALTER TABLE만 하는 V1"을 추가하면, **테이블이 아직 없는 새 DB(테스트 컨테이너·신규 로컬)에서 V1이 즉시 실패**한다. 따라서 정석 도입이 필요하다.

**정석 도입 계획**:
1. 의존성: `org.flywaydb:flyway-core` + `org.flywaydb:flyway-mysql` (버전은 Spring Boot BOM 관리)
2. `V1__baseline.sql`: 현재 전체 스키마(Hibernate가 만든 스키마를 export해 사람이 검토). 새 환경은 이걸로 생성.
3. `V2__seed_character_items.sql`: 기본 캐릭터(id=1) 시드 — **C5가 함께 해결됨**(새 환경에서 가입 즉시 실패하던 문제)
4. `V3__chat_messages_sender_nullable.sql`: `member_id` NOT NULL 해제
5. 설정: `ddl-auto: update → validate` (dev/prod), 기존 DB는 `baseline-on-migrate: true` + `baseline-version: 1`로 V1을 건너뛰고 V2부터 적용
6. 테스트 인프라: IntegrationTestSupport가 create-drop 대신 **Flyway 마이그레이션으로 스키마 생성** — 마이그레이션 자체가 매 테스트마다 검증되는 부수 효과

위험·롤백: 운영 서버는 중지 상태(HANDOFF §2)이고 fork에는 배포 경로가 없어 실DB 영향 없음. 로컬 세션 DB(dodoong-ai-mysql)로 기존-DB 경로(baseline)와 신규-DB 경로(테스트 컨테이너) 둘 다 검증한다.

### 코드 변경
- `MemberService.withdraw`: 회원 삭제 전 `chatMessageRepository`에 sender 분리 bulk UPDATE(`SET member_id = NULL WHERE member_id = :id`) 추가, 파티원 삭제 전 해당 파티원들의 verification 삭제 추가
- `PartyService.leaveParty`: 파티원 삭제 전 그 파티원의 verification 삭제
- **조회 경로 수정(놓치기 쉬운 부분)**: `ChatMessageRepository.findByPartyId`의 `JOIN FETCH m.sender`는 inner join이라 **sender가 NULL인 메시지가 조회에서 사라짐** → `LEFT JOIN FETCH`로 변경. `ChatMessageHistoryResponse.of`/`ChatMessageResponse.from`은 `sender.getId()`를 바로 호출해 **NPE** → null-safe 매핑(senderId/닉네임/프로필 null, isMine=false)
- 채팅방 목록의 마지막 메시지 표시도 동일 경로 점검

### 검증
- R8 `@Disabled` 테스트 3건을 새 정책에 맞게 수정 후 활성화: 탈퇴 성공 + **채팅은 남아 있고 sender만 null** + 인증은 삭제됨 + 파티 탈퇴 성공
- 신규: 익명화된 메시지가 채팅 내역 조회에 포함되고 NPE 없이 매핑되는지, 새 DB에서 V1~V3 적용 후 가입이 시드 덕에 성공하는지(C5)

~~**내가(사람이) 판단할 사항**: ① 인증 레코드 "삭제" 제안 수용 여부 ② 익명화 표시를 백엔드가 문자열("탈퇴한 사용자")로 내릴지, null로 내려 FE가 표시할지~~
> ✅ **결정 6 확정(2026-10-21 @nomellc)**: 채팅 보존+익명화 & Flyway 정석 도입. 세부 — ① 인증 레코드는 파티원 삭제 시 함께 삭제 ② 익명화는 senderId/닉네임 null로 전달, 표시 문구는 FE 책임.

## 결정 7 — R9: 파티 수정 API의 계약

**현재 동작**: `isPublic` 생략 시 NPE → 500. 참고로 이 API는 `description`을 무조건 대입(생략하면 null로 덮임)하는 **전체 교체(full-replace)** 설계다(phase1-review §4-5).

| 선택지 | 내용 | 장점 | 단점 |
|--------|------|------|------|
| (a) **`@NotNull` 추가** | DTO의 `isPublic`에 `@NotNull` → 생략 시 400 + 명확한 메시지 | full-replace 계약을 명시화, 한 줄 수정, 500→400 | 부분 수정을 원하는 FE가 있다면 깨짐(현재도 500이므로 사실상 영향 없음) |
| (b) null이면 기존 값 유지 | 부분 수정(partial update) 의미론 | 관대한 API | description은 여전히 덮어쓰는 구조라 **반쪽짜리 partial** — 일관성 없음. 제대로 하려면 API 재설계 |

**추천: (a)** — 이 API가 전체 교체라는 사실을 계약으로 못 박는 최소 수정. (b)는 R9 수정이 아니라 API 재설계 과제로 분리하는 게 맞다.

> ✅ **결정 7 승인(2026-10-21 @nomellc): (a) `@NotNull`** — isPublic 생략 시 400으로 거부. 결정 5·6·7 완료로 구현 착수.
**검증**: R9 `@Disabled` 테스트는 (a) 채택 시 "null이면 검증에서 거부"로 시나리오를 바꿔 활성화(엔티티가 아니라 Bean Validation 테스트), NPE 고정 테스트는 제거.

---

## 진행 방식
결정 5 → 6 → 7 순서로 하나씩 승인받고, 셋 다 정해지면 구현 → `@Disabled` 활성화 → 전체 그린 → worklog → 커밋 승인 요청. 구현 중 기존(Phase 2 포함) 테스트 기대값을 바꿔야 하는 경우 해당 건마다 이유를 보고한다.
