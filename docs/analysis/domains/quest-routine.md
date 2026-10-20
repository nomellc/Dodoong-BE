# quest · routine — 일일 퀘스트와 반복 루틴

## 책임
- quest: 일일 퀘스트 CRUD, 체크(경험치 연동), 미루기, 캘린더 집계, 아이젠하워 사분면 조회.
- routine: 반복 규칙(요일 집합 + 종료일) 저장, 루틴 삭제 시 연관 퀘스트 정리.

## 엔드포인트
quest 9개(`/api/daily-quests...`), routine 1개(DELETE `/api/routines/{id}`). 루틴 "생성"은 별도 API가 아니라 **퀘스트 생성 요청의 `isRoutine=true`로 수행**됨 — API 설계상 비대칭(생성은 quest, 삭제는 routine).

## 핵심 설계: 루틴의 "가상 전개(virtual expansion)"
미래 날짜의 루틴 퀘스트는 DB에 미리 만들지 않는다.
- **오늘 이전**: `daily_quests` 실데이터 집계.
- **미래**: 조회 시점에 `Routine.expandOccurrences(from, to)`로 반복 요일을 계산해 가상 퀘스트로 응답. 실제 레코드는 매일 00:00 `DailyQuestScheduler`가 당일 분만 생성.
- 결과적으로 캘린더 응답 = 실데이터(오늘까지) + 루틴 가상 전개(미래) + 미래 날짜로 미뤄진 단일 퀘스트.

### 스케줄러 멱등성 (3중 방어)
1. 사전 체크: `findExistingRoutineIds(today, ...)`
2. 루틴별 개별 트랜잭션(`TransactionTemplate`) — 1건 실패가 전체를 막지 않음
3. DB unique(routine_id, quest_date) 위반 시 `DataIntegrityViolationException` 잡아서 skip

### 도메인 규칙 (DailyQuest 엔티티)
- 미루기: 루틴 퀘스트 불가, 체크된 퀘스트 불가, +1일.
- 삭제: 체크된 퀘스트 삭제 불가.
- 체크 멱등: `changeChecked`가 상태 변화 없으면 false → 경험치 중복 지급 방지.
- 체크/해제 시 `member.addExperience(10)` / `subtractExperience(10)` (member.md 참조).

### 루틴 삭제 정책
미체크 퀘스트는 삭제(`deleteUncheckedByRoutineId`), 체크된 퀘스트는 `routine_id`를 null로 끊어 기록 보존(`detachCheckedByRoutineId`).

## 성능 관찰 (Phase 5 후보)
- `findIncompleteQuests`(사분면): member의 미완료 전체를 날짜 제한 없이 조회 — 데이터 누적 시 증가.
- 캘린더: 집계 쿼리 2회 + 루틴 전개(메모리) — 월 단위라 부담 낮음.
- `daily_quests`에 (member_id, quest_date) 명시 인덱스 없음 — FK 인덱스(member_id)만 존재할 것. 실행 계획 확인 필요.

## 미사용 코드 (C4)
`DailyQuestRepository.existsCheckedPartyQuest`, `countMonthlyPartyParticipations` — 호출부 없음. content 문자열 동등 비교로 파티 퀘스트를 매칭하는 설계 흔적(현재 파티 인증은 이미지 업로드 방식으로 대체된 것으로 추정).
