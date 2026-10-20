# Phase 1 보고서 — AI Codebase Analysis

- 기간: 2026-10-20 ~ 2026-10-21
- 수행: Claude Code 분석 + 사람 검토(진행 중)
- 기준 커밋: `d6a1384` / 브랜치 `claude/dodoong-ai-engineering-analysis-d8eee4`

## 1. 수행한 작업

| 작업 | 산출물 | 상태 |
|------|--------|------|
| 전체 도메인·의존성 분석 | [analysis/00-overview.md](../analysis/00-overview.md) | 완료 |
| ERD 작성(코드 기준) + README ERD 대조 | [analysis/01-erd.md](../analysis/01-erd.md) | 완료 — 차이 10건 기록 |
| 핵심 플로우 시퀀스 다이어그램 6종 | [analysis/02-sequences.md](../analysis/02-sequences.md) | 완료 |
| 모듈별 기술 문서 8건 | [analysis/domains/](../analysis/domains/) | 완료 |
| AI 분석 오류·검증 기록 | [analysis/99-analysis-log.md](../analysis/99-analysis-log.md) | 완료 — AI 오류 3건 교정 포함 |

방법: 메인 소스 전 파일 원문 독해(요약 추론 금지), Phase 0 실측·기존 테스트 기대값과 교차 검증, README ERD는 원본 PNG 판독.

## 2. 주요 발견

1. **README ERD는 구버전** — 관계 구조는 일치하나 `routine.endDate`, `party.currentMembers` 등 핵심 컬럼 누락 포함 10건 불일치. 실제 코드 기준 ERD를 새로 작성함.
2. **신규 잠재 위험 4건(R7~R10)**: 음수 코인 가능성, 탈퇴 시 FK 위반 가능성, 파티 수정 NPE, 채팅 토픽 구독 권한 부재. 전부 코드 추론 단계 — 실행 재현 전에는 결함으로 확정하지 않음.
3. 주석·구현 불일치: StreakService는 주석과 달리 일일 퀘스트만 스트릭에 반영(파티 인증 미반영).
4. 설계 강점도 기록: 채팅의 AFTER_COMMIT 브로드캐스트, 스케줄러 3중 멱등 방어, refresh token 해시 저장·rotation, 프로필 이미지 key prefix 검증.

## 3. 검증이 필요한 사항 (Phase 2~3 입력)

| 항목 | 검증 방법 | 우선순위 |
|------|----------|---------|
| R8 탈퇴 FK 위반 | 채팅 남긴 회원 탈퇴 통합 테스트(Testcontainers) | 1 |
| R9 isPublic NPE | 파티 수정 요청에 isPublic 생략 테스트 | 1 |
| R7 음수 코인 | 레벨업→코인 소비→체크 해제 시나리오 단위 테스트 | 2 |
| R10 구독 권한 | 비파티원 STOMP SUBSCRIBE 통합 테스트 | 2 |
| R1 비공개 파티 검색 노출, R2 currentMembers 이중 관리 | **스펙 의도 확인(사람 판단)** 후 테스트 | 사람 결정 대기 |

## 4. 다음 Phase 계획

- **Phase 2 (다음)**: AI 테스트 엔지니어링. 우선순위 — ①Member 엔티티(경계값 풍부) ②AuthService ③DailyQuestService ④위 검증 항목의 재현 테스트. 목표: 서비스 계층 라인 커버리지 70%+(베이스라인 24.0%), PIT는 member/quest/party 한정.
- Phase 3: R/C 항목 중 확정된 결함의 수정(Issue·AC 기반).
- 작업 환경 전환: worktree → `ai-engineering` 브랜치 기반 일반 폴더 작업 (인수인계 문서 참조).
