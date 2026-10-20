# streak — 연속 달성

## 책임
"하루 1개 이상 퀘스트 완료"의 연속 일수를 추적하고 7일마다 코인 보상. 조회 API 1개(GET /api/streaks)와 배치 평가.

## 설계
- **평가는 실시간이 아니라 배치**: 매일 00:10 `StreakScheduler`가 "전날" 달성자를 모아 `StreakService.evaluateDailyStreak(memberId, 전날)` 호출.
- 달성 소스는 `QuestAchievementSource` 인터페이스(List 주입)로 추상화:
  - `countCompletedQuests(memberId, date)` — 개인별 평가용
  - `findAchievedMemberIds(date)` — 배치 대상 추출용
  - 현재 구현체는 `DailyQuestAchievementSource` 1개. **파티 인증은 스트릭에 반영되지 않음** — StreakService 주석("일일퀘스트 또는 파티 퀘스트를 합하여")과 실제 구현이 불일치. PartyVerification용 소스를 추가하면 자동 합산되는 구조(확장 포인트).
- 스트릭 레코드는 "끊기면 새로 시작" — 과거 스트릭 이력이 행으로 누적되고, 조회는 `lastCheckedDate` 최신 1건.

## 규칙 상세 (Streak 엔티티)
- `isAlreadyReflected`: 같은 날 중복 반영 방지.
- `canContinue`: lastCheckedDate + 1일 == 달성일.
- 7일 간격 보상: `consecutiveDays % 7 == 0`일 때 코인 +10.

## 관찰·이슈
- **배치 미실행 보정 없음(R4)**: 00:10에 서버가 죽어 있으면 그날 스트릭 평가가 영구 누락 → 다음날 연속성 끊김. 사용자 입장에선 억울한 스트릭 리셋.
- 평가 루프가 회원별 개별 트랜잭션(`evaluateDailyStreak`에 @Transactional) — 전체 롤백은 없으나, 회원 수에 비례한 순차 처리.
- `StreakScheduler`의 보상 코인 지급은 `member.earnCoin` — Member를 락 없이 조회(findById) 후 변경. 동시 요청(퀘스트 체크의 락 조회)과 겹치면 lost update 가능성 — 새벽 시간대라 실측 위험은 낮음. Phase 2에서 시나리오화할지 판단.
