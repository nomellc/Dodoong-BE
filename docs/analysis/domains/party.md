# party — 파티·인증·랭킹

## 책임
파티 CRUD, 검색(QueryDSL), 가입/탈퇴(정원·비밀번호 검증), 일일 인증(이미지), 월간 참여 현황·랭킹. **636라인으로 최대 서비스** — 분리 후보(O1).

## 엔드포인트 (12개)
생성·수정(multipart), 삭제, 상세, 내 파티, 검색, 가입, 탈퇴, 내 월간 현황, 월간 랭킹(경로 2개 별칭), 인증 내역(커서), 인증 생성(multipart).

## 핵심 규칙
- 역할: LEADER(생성자) / MEMBER. 파티장은 탈퇴 불가(삭제만 가능), 파티장인 회원은 서비스 탈퇴 불가.
- 가입 검증 순서: 중복 가입 → 모집 중 → 정원(`countByPartyId` 실측) → 비밀번호(BCrypt). 파티는 비관적 락으로 조회.
- 정원 상태: `currentMembers` 필드 증감 + `isRecruiting` 자동 갱신(`currentMembers < maxMembers`).
- 비공개 파티: `isPublic=false`면 수정 시 비밀번호 설정, 공개 전환 시 비밀번호 제거.
- 인증: 하루 1회(파티원 기준). 검증 → S3 업로드 → 락 재조회 → 재검증 → 저장 → 경험치 +10.
- 월간 랭킹: 인증 횟수 내림차순, 동점 동일 순위(다음 순위는 건너뛰지 않는 dense rank), 동점 내 memberId 오름차순 정렬.

## 동시성 설계
- `findByIdForUpdate`(파티), `findByMemberIdAndPartyIdForUpdate`(파티원), `findByIdForUpdate`(회원) — PESSIMISTIC_WRITE, lock timeout 3초. 타임아웃은 GlobalExceptionHandler가 LOCK_TIMEOUT으로 변환.
- 가입 경쟁 시나리오: 파티 락을 먼저 잡으므로 정원 초과 경쟁은 직렬화됨. 단, 정원 검증은 count 기반이고 상태 갱신은 필드 기반(아래 R2).

## 알려진 이슈
- **R1 → 스펙으로 확정(2026-10-21 @nomellc)**: `PartyRepositoryImpl.searchParties`의 `isPublicParty()` 필터 주석 처리는 의도된 것 — 비공개 파티도 검색에 노출하고 **가입만 비밀번호로 제한**하는 제품 결정. 주석 처리된 필터(검색·카운트 쿼리 2곳)와 미사용 `isPublicParty()` 메서드는 정리 후보. Phase 2에서 "비공개 파티가 검색 결과에 포함된다"를 기대값으로 테스트해 스펙 고정.
- **R2 → 결정됨(2026-10-21 @nomellc): 현 구조 유지 + 일치 검증 테스트** — 정원의 이중 소스(검증은 `countByPartyId()`, 모집 상태는 `currentMembers` 필드)는 정상 경로에서 같은 트랜잭션으로 함께 갱신되므로 유지. 대신 Phase 2에서 가입/탈퇴/회원탈퇴 후 `count == currentMembers` 일치를 검증하는 테스트로 어긋남 감지 장치를 둔다. 단일화는 개선 후보로만 유지(테스트가 실제 불일치를 잡으면 재논의).
- **R3**: 인증 S3 업로드가 재검증 "전"에 수행 — 재검증 실패 시 S3 고아 객체. 파티 삭제는 트랜잭션 안에서 S3 delete 호출 — S3 장애 시 삭제 전체 롤백.
- **R5**: 카테고리 필터가 `cast(category as string) LIKE %NAME%` — 현재 enum(STUDY/CAREER/DAILY/LANGUAGE/FITNESS)은 상호 부분문자열이 아니라 오탐 없음을 확인. 단 enum 추가 시 깨질 수 있고 인덱스 불가.
- 검색은 offset 페이징 + count 쿼리 — 데이터 증가 시 Phase 5 측정 대상.
- 파티 수정은 이름 변경을 지원하지 않음(by design — `PartyUpdateRequestDto`에 name 필드 없음, `Party.updateParty`는 description/maxMembers/isPublic/password만 갱신).
- **R9(신규)**: `PartyUpdateRequestDto.isPublic`은 `Boolean`(nullable)인데 `Party.updateParty`에서 `boolean` 필드에 대입 — 요청 JSON에 isPublic이 없으면 **NPE → 500** 예상. 실행으로 검증하지 않았으므로 Phase 2 테스트 후보.
