# 세션 인수인계 — Dodoong AI-Assisted Engineering

> 새 세션(Claude Code / Codex)이 이 문서 하나로 프로젝트 맥락을 이어받을 수 있도록 작성. 최종 갱신: 2026-10-21 (Phase 1 완료 시점).

## 1. 프로젝트 정체성

- 기존 Spring Boot 서비스(Dodoong: 게이미피케이션 투두 앱 백엔드)에 **AI 코딩 에이전트를 실제 개발 프로세스로 도입**하고 효과·한계를 측정하는 프로젝트. 코드 양산이 아니라 **위임→검증→기록**이 목적.
- 최종 의사결정자는 사람(@nomellc). AI 산출물을 사람이 이해하지 못한 채 채택하는 것 금지.
- Phase 0(베이스라인)~7(포트폴리오) 구성. 현재 **Phase 3(확정 결함 수정)까지 완료**(2026-10-21: R7·R8·R9 수정 + Flyway 도입으로 C2·C5 해결, 테스트 126건 그린·skipped 0 — docs/worklog/ 참조). Phase 2는 서비스 계층 커버리지 24%대→80%대 달성.
- **운영 방식(2026-10-21 변경)**: Claude Code가 주력 도구, Codex 역할 분담은 필수에서 제외. 모든 Phase에서 작업 전(문제·범위·대안·판단 사항 제시 → 승인) / 작업 후(수행 내용·검증 근거·한계 보고 → 최종 승인) 절차 적용, 결정은 한 번에 하나씩. Phase 1 검토는 docs/phase-reports/phase1-review.md.

## 2. 저장소·환경 상태

- **저장소**: fork `nomellc/Dodoong-BE` (origin). `upstream`(Team-Samdasu)은 **읽기 전용 — 절대 push 금지**.
- **브랜치 전략(전환 중)**: 지금까지 Claude worktree 브랜치 `claude/dodoong-ai-engineering-analysis-d8eee4`에서 작업. 이후 **`ai-engineering` 브랜치를 기준으로 일반 폴더**(`/Users/yujin/Documents/dodoong/Dodoong-BE`)에서 작업 예정. fork에는 GitHub Actions secrets가 없어 deploy 브랜치를 건드려도 실배포되지 않음(확인됨).
- **운영 서버(api.dodoong.p-e.kr)는 중지 상태**(2026-10-21 확인) — 실험이 운영에 영향을 줄 경로 없음.
- **로컬 환경**:
  - JDK 17.0.10(`/Library/Java/JavaVirtualMachines/jdk-17.jdk`), Gradle 8.14.5 wrapper, Docker Desktop.
  - 호스트 포트 선점 주의: **3307(사용자 mariadbd), 6379(사용자 redis-server)** — 건드리지 말 것. 과거 체크아웃의 `dodoong-mysql` 컨테이너(2개월 전 종료)도 보존할 것.
  - 세션 전용 컨테이너: `dodoong-ai-mysql`(127.0.0.1:**3308**), `dodoong-ai-redis`(127.0.0.1:**6380**) — compose override로 이름·포트 변경해 기동.
  - `.env`(gitignored)는 worktree에 생성돼 있음(로컬 전용 랜덤 자격증명). 일반 폴더로 이전 시 **다시 만들어야 함** — `.env.example` 참조, DB URL 포트는 3308, REDIS_PORT 6380.
  - **신규 DB에는 `character_items(id=1)` 시드가 필수** — 없으면 회원가입이 CHARACTER_NOT_FOUND로 실패(C5). 빌드/테스트에는 `JWT_SECRET` 환경변수 필요(base64, 32바이트 이상).

## 3. 작업 원칙 (전 세션에서 합의된 것 — 반드시 준수)

**워크플로(작업당)**: 문제 정의 → 조사 → 요구사항·완료 기준 → 대안 제시 → **사람 승인** → AI 구현 → 자동 검증 → 독립 리뷰 → **사람 최종 승인** → 기록.

**검증 규칙**:
- 테스트 통과 ≠ 품질 보장. AI 리뷰 지적은 **재현 가능한 근거로 검증 전에는 결함으로 확정하지 않는다.**
- AI가 테스트 기대값을 임의로 수정하지 않는다. 기존 테스트 변경은 이유·영향 설명 후 승인.
- 성능 주장은 동일 조건 측정 없이는 금지. 실행 안 한 것을 실행했다고 보고하지 않는다.
- 문제가 확인되지 않으면 억지로 만들지 않는다.

**안전 규칙**: 운영 변경·비용 발생 작업은 사전 승인. upstream 변경 금지. 비밀값 노출 금지(문서·채팅에 자격증명 쓰지 않기 — `.env`에만).

**역할 분담(유연)**: Claude Code=분석·설계·리뷰 중심, Codex=구현·테스트 중심이되 작업 특성 따라 선택. 구현한 도구가 자기 코드를 리뷰하지 않는다. PR 리뷰에는 기존 CodeRabbit(.coderabbit.yaml)이 있으므로 Phase 4는 신규 구축 대신 비교 실험.

**기록**: 작업마다 목적/AI 사용 이유/지시·자료/산출물/최초안 vs 채택안/사람 판단/실측 결과/성과·한계/투입 시간을 docs/worklog/에 남긴다(포트폴리오 원천).

## 4. 지금까지의 산출물 지도

```
docs/
├── baseline/phase0-baseline.md     # 빌드 1m13s, 커버리지 L24.0%/B15.3%, CI 평균 120s, API 응답
├── analysis/                       # Phase 1: 00-overview, 01-erd(README 대조), 02-sequences,
│   ├── domains/ (8개 도메인 문서)  #          99-analysis-log(AI 오류 교정 기록)
├── phase-reports/phase1-report.md  # Phase 1 요약·다음 계획
└── HANDOFF.md                      # 본 문서
```

커밋 이력(develop 이후): `d6a1384` JaCoCo 도입 + Phase 0 베이스라인.

## 5. 개선 후보 전체 목록 (ID는 전 문서 공통)

**확인된 문제(C)**: ~~C1~~ 해결(Phase 2: 서비스 계층 80%+) · ~~C2~~ 해결(Phase 3: Flyway 도입, ddl-auto=validate — db/migration/V1~V3) · C3 prod CORS에 localhost 잔존 · C4 미사용 쿼리 2건(content 문자열 매칭 설계 흔적) · ~~C5~~ 해결(Phase 3: V2 시드 마이그레이션) · C6 CI 커버리지 보고·배포 헬스체크 없음

**잠재적 위험(R — 재현 전 확정 금지)**: ~~R1~~ **스펙으로 확정(2026-10-21 @nomellc)**: 비공개 파티도 검색에 노출되고 가입만 비밀번호로 제한 — 위험 아님, Phase 2에서 이 동작을 기대값으로 하는 테스트로 고정 · ~~R2~~ **결정됨(2026-10-21 @nomellc)**: currentMembers vs count 이중 소스는 현 구조 유지, Phase 2에서 가입/탈퇴 시 두 값 일치 검증 테스트 추가(감지 장치) — 단일화는 개선 후보로만 유지 · R3 파티 인증 S3 업로드 순서(고아 객체)/삭제 롤백 · R4 스케줄러 분산 락·미실행 보정 없음 · R5 카테고리 LIKE 검색(enum 추가 시 취약) · R6 비관 락 경합 미측정 · ~~R7·R8·R9~~ **수정 완료(2026-10-21 Phase 3, phase3-plan.md·worklog 참조)**: R7은 레벨 다운 폐지(체크 해제 시 경험치만 차감), R8은 채팅 보존+익명화(sender NULL)·인증은 파티원 삭제 시 삭제, R9는 isPublic `@NotNull`(400). 증거 테스트 전부 활성화·그린 — 회귀 방지 장치로 전환. **FE 공지 필요**: 파티 수정에 isPublic 필수, 채팅 응답 senderId/닉네임 null 가능(탈퇴자) · R10 채팅 토픽 구독 권한 검증 부재

**개선 기회(O)**: O1 PartyService(636줄) 분리 · O2 쿼리/N+1 측정 · O3 Micrometer+Prometheus(로컬 우선) · O4 LLM 퀘스트 등록(Phase 6) · O5 로컬 재현성(시드 스크립트+실행 문서) · O6 dev/prod open-in-view 불일치 · O7 잘못된 AccessDeniedException import · O8 ZoneId 하드코딩 5곳 · O9 presigned 미사용 객체 정리 없음

## 6. 사람 결정 대기 중인 질문

1. ~~R1 · R2~~ — 둘 다 해소(2026-10-21, §5 참조): R1은 스펙 확정, R2는 현 구조 유지 + 일치 테스트
2. Phase 6 LLM 제공자(Claude vs OpenAI)와 월 예산 상한
3. CLAUDE.md / AGENTS.md 작성 시점(계획상 "계획 확정 후" — 아직 미작성, 본 문서가 임시 역할)

## 7. 새 세션 시작 체크리스트

1. `ai-engineering` 브랜치에서 작업 중인지 확인 (`git branch --show-current`)
2. `.env` 존재 확인(없으면 §2대로 생성), Docker 데몬 기동, 세션 전용 컨테이너 up
3. `JWT_SECRET=<base64> ./gradlew test jacocoTestReport`로 그린 상태 확인 (예상: 테스트 17건 통과, 커버리지 L≈24%)
4. 본 문서 §3 원칙과 §5 ID 체계를 따른 뒤 Phase 2 착수: [phase-reports/phase1-report.md](phase-reports/phase1-report.md) §3·§4의 우선순위 사용
