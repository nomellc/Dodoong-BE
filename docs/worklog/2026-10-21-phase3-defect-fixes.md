# Worklog — Phase 3: 확정 결함 수정 (R7·R8·R9) + Flyway 도입 (2026-10-21)

> 기록 형식: HANDOFF §3. 계획·결정 이력: [phase-reports/phase3-plan.md](../phase-reports/phase3-plan.md)

## 목적
Phase 2에서 실행 재현으로 확정된 결함 3건을 승인된 정책(결정 5·6·7)대로 수정하고, `@Disabled` 증거 테스트 5건을 활성화해 수정을 증명한다. 결정 6의 선행 작업으로 Flyway를 도입한다(C2·C5 동시 해결).

## 사람 결정 (전부 사전 승인)
- **결정 5 (R7)**: 레벨 다운 폐지 — 체크 해제는 경험치만 차감(0 하한), 레벨·코인 유지. 단순 0 클램프는 코인 복제 악용을 열어 배제.
- **결정 6 (R8)**: 채팅 보존+익명화(senderId/닉네임 null, 표시는 FE), 인증은 파티원 삭제 시 함께 삭제, Flyway 정석 도입 포함.
- **결정 7 (R9)**: `isPublic`에 `@NotNull` — 전체 교체(full-replace) 계약 명시, 500→400.

## 변경 내역

### Flyway (C2 해결)
- [build.gradle](../../build.gradle): `flyway-core`, `flyway-mysql`
- [V1__baseline.sql](../../src/main/resources/db/migration/V1__baseline.sql): 기존 스키마 전체(세션 DB의 mysqldump를 검토·정리, FK 의존 순서로 재배열)
- [V2__seed_character_items.sql](../../src/main/resources/db/migration/V2__seed_character_items.sql): 기본 캐릭터 시드 — **C5 해결** (중복 시 no-op이라 기존 DB에도 안전)
- [V3__chat_messages_sender_nullable.sql](../../src/main/resources/db/migration/V3__chat_messages_sender_nullable.sql): `member_id` NOT NULL 해제
- 설정: dev/prod `ddl-auto: update → validate`, 공통에 `baseline-on-migrate: true, baseline-version: 1`
- "하이브리드 최소 도입"을 배제한 이유: Flyway는 JPA보다 먼저 실행되므로 ALTER만 있는 마이그레이션은 테이블이 없는 새 DB(테스트·신규 환경)에서 즉시 실패한다.

### R8 — 탈퇴·파티탈퇴 FK 위반
- [MemberService](../../src/main/java/com/samdasu/dodoong/domain/member/service/MemberService.java): 파티원 삭제 전 인증 삭제, 회원 삭제 전 `detachSenderByMemberId`(채팅 익명화)
- [PartyService.leaveParty](../../src/main/java/com/samdasu/dodoong/domain/party/service/PartyService.java): 파티원 삭제 전 인증 삭제
- [ChatMessageRepository](../../src/main/java/com/samdasu/dodoong/domain/chat/repository/ChatMessageRepository.java): `detachSenderByMemberId` 추가, `findByPartyId`를 **LEFT** JOIN FETCH로(inner join이면 익명화된 메시지가 내역에서 사라짐)
- [PartyVerificationRepository](../../src/main/java/com/samdasu/dodoong/domain/party/repository/PartyVerificationRepository.java): `deleteByPartyMemberIds` 추가
- [ChatMessage](../../src/main/java/com/samdasu/dodoong/domain/chat/entity/ChatMessage.java): sender nullable, [ChatMessageHistoryResponse](../../src/main/java/com/samdasu/dodoong/domain/chat/dto/response/ChatMessageHistoryResponse.java): null-safe 매핑(NPE 방지)
- 설계 주의점(기록): 새 bulk 쿼리에 `clearAutomatically`를 쓰지 않음 — 같은 트랜잭션에서 관리 중인 party의 `currentMembers` 감소가 영속성 컨텍스트에서 날아가는 lost update를 만들 수 있음. 통합 테스트가 이 지점을 검증.

### R7 — 음수 코인
- [Member.subtractExperience](../../src/main/java/com/samdasu/dodoong/domain/member/entity/Member.java): 레벨 다운·코인 회수 제거, 경험치 0 하한만.

### R9 — isPublic NPE
- [PartyUpdateRequestDto](../../src/main/java/com/samdasu/dodoong/domain/party/dto/request/PartyUpdateRequestDto.java): `@NotNull` (컨트롤러에 `@Valid` 이미 존재 → 400 동작 확인)

## 테스트 변경 (기대값 변경은 전부 정책 변경에 따른 것 — 건별 사유)
| 변경 | 사유 |
|------|------|
| R7·R8 `@Disabled` 4건 활성화, R9는 Bean Validation 테스트([PartyUpdateRequestDtoTest](../../src/test/java/com/samdasu/dodoong/domain/party/dto/request/PartyUpdateRequestDtoTest.java))로 교체 | 수정 증명. R9는 계약이 검증 계층으로 이동했으므로 시나리오 자체가 바뀜 |
| MemberTest의 "레벨 다운·코인 회수" 기대 3건 → "레벨·코인 유지" 2건 + 악용 방지(체크-해제-재체크 반복에도 코인 1회 지급) 1건 | 결정 5의 정책 변경. "음수 코인 현재 동작 기록" 테스트는 의미 소멸로 제거 |
| DailyQuestServiceTest `uncheckRollsBackLevelUp` → `uncheckKeepsLevelAfterLevelUp` | 동일 |
| MemberServiceTest withdraw 순서 검증에 인증 삭제·채팅 익명화 단계 추가, 새 의존성 mock 2개 | R8 구현 반영 |
| IntegrationTestSupport: `create-drop → validate` + Flyway가 스키마 생성 | 마이그레이션이 모든 통합 테스트에서 검증되는 효과 |
| R8 통합 테스트에 보강: 메시지 보존+sender null, 채팅 내역 조회에 포함(NPE 없음), 인증 삭제 확인, currentMembers 일치, V2 시드 존재(C5) | 수정의 실질 검증 |
| 기존 테스트(Phase 0의 17건) 기대값: **무수정**. PartyService가 `Collections.singletonList`를 쓴 것은 기존 PartyServiceTest(목에 id 없음)와의 호환 유지 목적 | 원칙 준수 |

## 실측 결과
- `./gradlew test jacocoTestReport`: **126건 전부 통과, 0 실패, 0 skipped** (Phase 2의 skipped 5건이 전부 활성화·그린)
- 서비스 계층 라인 커버리지 80.5% 유지, 전체 브랜치 51.4%
- **기존 DB 경로 실검증**: 세션 DB(dodoong-ai-mysql, ddl-auto가 만든 스키마)에 dev 프로파일로 실제 기동 → flyway_schema_history에 `1 baseline(skip) / 2 seed / 3 alter` 기록, `chat_messages.member_id Null=YES` 확인, actuator health UP(validate 통과), 기동 후 종료.
- **새 DB 경로**: 통합 테스트가 매번 V1→V3로 스키마를 만들고 validate 통과 — 테스트 그린이 곧 검증.

## 성과·한계
- R7·R8·R9 종결(증거 테스트가 회귀 방지 장치로 전환), C2·C5 동시 해결.
- 한계: V1 baseline은 ddl-auto가 누적시킨 스키마의 스냅숏 — upstream의 실제 운영 DB와 미세하게 다를 가능성은 남음(운영 적용 시 스키마 diff 선행 필요). STOMP(R10)·전송 경로의 ChatMessageResponse는 sender 비-null 전제 유지(전송 시점엔 항상 존재).
- FE 영향 공지 필요: ①파티 수정 요청에 isPublic 필수(누락 시 400) ②채팅 응답의 senderId/senderNickname이 null일 수 있음(탈퇴자) — FE가 "탈퇴한 사용자"로 표시.

## 투입 시간
- AI 작업(계획 문서 이후 구현~검증): 약 25분. 사람 결정 3건 별도.
