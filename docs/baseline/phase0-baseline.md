# Phase 0 — Baseline Measurement

- 측정일: 2026-10-21 (KST)
- 측정 환경: Apple Silicon macOS (Darwin 25.2.0), JDK 17.0.10 (toolchain), Gradle 8.14.5, Docker 29.4.3
- 대상 커밋: `3369b18` (develop, fork: nomellc/Dodoong-BE)
- 측정 주체: Claude Code (사람 검토 전 자동 수집)

## 1. 빌드·테스트

| 항목 | 값 | 비고 |
|------|-----|------|
| `./gradlew clean build` (테스트 포함) | **1분 13초** | mysql:8.4 이미지 캐시된 상태, Gradle 데몬 기동 포함 |
| `./gradlew test jacocoTestReport` (증분) | 46초 | 컴파일 UP-TO-DATE 상태 |
| 테스트 결과 | 전부 통과 | 테스트 클래스 2개 (PartyServiceTest 16건 + 컨텍스트 로드 1건) |
| 앱 기동 시간 (dev 프로파일) | 3.526초 | `Started DodoongApplication in 3.526 seconds` |

관찰: 빌드 종료 시 Testcontainers MySQL 종료 순서로 인한 HikariPool 커넥션 경고 다수 발생(빌드 실패는 아님). Phase 2에서 테스트 인프라 정비 시 함께 점검.

## 2. 커버리지 (JaCoCo, 최초 측정)

| 지표 | 값 |
|------|-----|
| Line | **24.0%** (461/1,924) |
| Branch | **15.3%** (62/406) |
| Method | 21.4% (110/514) |
| Class | 31.8% (42/132) |

서비스 계층 라인 커버리지:

| 패키지 | Line |
|--------|------|
| party/service | 73.9% |
| auth/service | 1.5% |
| member/service | 1.5% |
| quest/service | 0.7% |
| chat/service | 0.0% |
| character/service | 0.0% |
| routine/service | 0.0% |
| streak/service | 0.0% |

> 참고: 전체 24%는 설정 클래스·DTO가 테스트 부수 효과로 로드된 수치 포함. 실질 비즈니스 로직 커버리지는 party 도메인을 제외하면 0%대.

## 3. CI 실행 시간 (GitHub Actions, upstream 기준)

- 최근 성공 25회: **평균 120초, 최소 92초, 최대 163초**
- fork(nomellc)에는 Actions 실행 이력·secrets 없음 → deploy 브랜치 push로도 실제 배포 불가(안전 확인됨)

## 4. API 응답 시간 (로컬, 소량 데이터)

조건: 로컬 dev 프로파일, MySQL/Redis 컨테이너(세션 전용), 단일 사용자, 일일퀘스트 10건 시드, 요청 20회(로그인은 10회) 순차 측정.

| 엔드포인트 | min | p50 | p95 | max |
|-----------|-----|-----|-----|-----|
| GET /api/daily-quests?date=오늘 | 7ms | 10ms | 25ms | 27ms |
| GET /api/daily-quests/calendar | 11ms | 12ms | 20ms | 35ms |
| GET /api/daily-quests/quadrants | 6ms | 8ms | 11ms | 20ms |
| GET /api/parties/search | 6ms | 9ms | 11ms | 45ms |
| GET /api/streaks | 6ms | 8ms | 11ms | 11ms |
| GET /api/characters | 8ms | 10ms | 13ms | 16ms |
| POST /api/auth/login | 72ms | 74ms | — | 78ms |

**한계(중요):** 데이터가 수십 건 수준이라 절대값은 의미가 제한적. 이 수치는 "동일 조건 재측정"의 기준점이며, Phase 5 부하 테스트에서는 볼륨 데이터 시드 후 k6로 재측정한다. 로그인의 ~74ms는 대부분 BCrypt 비용으로 추정되나 프로파일링으로 검증하지 않았음(가설).

## 5. 환경·인프라 현황 확인

- **운영 서버(api.dodoong.p-e.kr): 접속 불가(타임아웃)** — EC2 중지 상태로 추정. 실험이 운영에 영향을 줄 경로 없음.
- 호스트 포트 충돌: 3307(사용자 mariadbd), 6379(사용자 redis-server) 점유 → 세션 전용 컨테이너를 3308/6380으로 격리 기동 (`dodoong-ai-mysql`, `dodoong-ai-redis`).
- 기존 `dodoong-mysql` 컨테이너(옛 체크아웃 소유, 2개월 전 종료)는 보존.
- 모니터링: Actuator health/info만 노출. Micrometer 레지스트리·Prometheus 미구성 (build.gradle에 레지스트리 의존성 없음).
- 재현성 이슈 확인: 신규 DB에서 회원가입은 `character_items(id=1)` 시드 없이는 실패 → 본 측정에서 수동 INSERT로 해결. 시드 스크립트 필요(Phase 4 후보, C5).

## 6. 이번 Phase에서 변경한 것

- `build.gradle`: JaCoCo 플러그인 + `jacocoTestReport`(xml/html) 추가 — 사전 승인됨. **이 외 소스 변경 없음.**
- `.env` 생성(gitignored, 로컬 전용 랜덤 자격증명), 측정 스크립트는 세션 스크래치패드에만 존재.

## 7. 재현 방법

```bash
# 1. 컨테이너 기동 (포트 충돌 시 override로 3308/6380 매핑)
docker compose --env-file .env up -d
# 2. 빌드+테스트+커버리지
JWT_SECRET=<base64 32bytes> ./gradlew clean build jacocoTestReport
# 3. 커버리지 리포트: build/reports/jacoco/test/html/index.html
# 4. 앱 기동 후 character_items(id=1) INSERT → 측정 스크립트 실행
```
