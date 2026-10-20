# 핵심 플로우 시퀀스 다이어그램

> 실제 코드 기준. 각 다이어그램 하단에 근거 파일을 명시.

## 1. 회원가입 → 토큰 발급

```mermaid
sequenceDiagram
    participant FE
    participant AuthController
    participant AuthService
    participant MemberRepo as MemberRepository
    participant CharRepo as CharacterItem/MemberCharacterRepository
    participant JWT as JwtTokenProvider
    participant Redis as RefreshTokenRepository(Redis)

    FE->>AuthController: POST /api/auth/signup {loginId, password}
    AuthController->>AuthService: signup(request)
    AuthService->>MemberRepo: existsByLoginId → 중복 시 DUPLICATE_LOGIN_ID
    AuthService->>MemberRepo: save(Member) (BCrypt 해시)
    AuthService->>CharRepo: findById(1L) 기본 캐릭터<br/>없으면 CHARACTER_NOT_FOUND ⚠ 시드 필수
    AuthService->>CharRepo: save(MemberCharacter, equip=true)
    AuthService->>JWT: issueTokens(member) → access(30분)/refresh(14일)
    AuthService->>Redis: save(memberId, SHA-256(refreshToken)) TTL 14일
    AuthController->>FE: 201 + Set-Cookie(accessToken, refreshToken; httpOnly)
```

근거: `AuthController`, `AuthService.signup`, `JwtTokenProvider`, `RefreshTokenRepository`, `CookieUtil`

## 2. 요청 인증 (JWT 필터) · 로그아웃

```mermaid
sequenceDiagram
    participant FE
    participant Filter as JwtAuthenticationFilter
    participant Blacklist as AccessTokenBlacklist(Redis)
    participant JWT as JwtTokenProvider

    FE->>Filter: 요청 (Cookie: accessToken)
    Filter->>Blacklist: exists(SHA-256(token))? — 매 요청 Redis 조회
    alt 블랙리스트 아님
        Filter->>JWT: extractAccessTokenPrincipal (서명·만료·tokenType=ACCESS 검증)
        JWT-->>Filter: CustomUserPrincipal(memberId, loginId)
        Filter->>Filter: SecurityContext 설정
    else 블랙리스트 or 무효
        Filter->>Filter: 인증 미설정 → EntryPoint가 401
    end

    Note over FE,JWT: 로그아웃: access를 남은 TTL만큼 블랙리스트에 저장,<br/>refresh는 소유자·Redis 일치 검증 후 삭제, 쿠키 만료
```

근거: `JwtAuthenticationFilter`, `AuthService.logout/blacklistAccessToken`

## 3. 루틴 퀘스트 생성 → 자정 스케줄러 전개

```mermaid
sequenceDiagram
    participant FE
    participant QS as DailyQuestService
    participant RS as RoutineService
    participant Sched as DailyQuestScheduler (00:00 KST)
    participant DB as MySQL

    FE->>QS: POST /api/daily-quests {isRoutine=true, repeatDays, endDate}
    QS->>RS: createRoutine(...)
    QS->>QS: routine.findNextQuestDate(today)
    alt 오늘이 반복 요일
        QS->>DB: DailyQuest 저장 (routine 연결)
    end

    Note over Sched: 매일 00:00
    Sched->>DB: findActiveRoutinesForDay(today, 요일)
    Sched->>DB: findExistingRoutineIds (사전 중복 체크)
    loop 각 루틴 (개별 트랜잭션)
        Sched->>DB: DailyQuest 저장
        Note right of DB: unique(routine_id, quest_date)<br/>위반 시 skip (레이스 방어)
    end
```

근거: `DailyQuestService.createRoutineQuests`, `DailyQuestScheduler`, `Routine`

## 4. 퀘스트 체크 → 경험치/레벨 → 다음날 스트릭 평가

```mermaid
sequenceDiagram
    participant FE
    participant QS as DailyQuestService
    participant M as Member(엔티티)
    participant SS as StreakScheduler (00:10 KST)
    participant StS as StreakService

    FE->>QS: PATCH /api/daily-quests/{id}/check {isChecked:true}
    QS->>QS: memberRepository.findByIdForUpdate (비관적 락)
    QS->>M: addExperience(10)
    alt 경험치 ≥ (level+1)*100
        M->>M: level+1, 경험치 이월, 코인 +level*10
    end
    QS-->>FE: 레벨/경험치/levelUp 여부

    Note over SS: 다음날 00:10
    SS->>StS: 전날 1개 이상 완료한 회원마다 evaluateDailyStreak
    StS->>StS: 최신 Streak 조회
    alt lastCheckedDate+1 == 전날
        StS->>StS: consecutiveDays+1, 7의 배수면 코인 +10
    else 연속 아님
        StS->>StS: 새 Streak 시작(1일)
    end
```

근거: `DailyQuestService.checkDailyQuest`, `Member.addExperience`, `StreakScheduler`, `StreakService`

## 5. 파티 인증 (이미지 업로드 포함)

```mermaid
sequenceDiagram
    participant FE
    participant PS as PartyService
    participant S3
    participant DB as MySQL

    FE->>PS: POST /api/parties/{id}/verifications (multipart image)
    PS->>DB: 파티·파티원 조회, 오늘 인증 존재? (1차 체크)
    PS->>S3: upload(image) ⚠ 트랜잭션 내 외부 I/O<br/>이후 실패 시 S3 고아 객체 (R3)
    PS->>DB: 파티원 비관적 락 재조회 + 인증 존재 재확인 (2차 체크)
    PS->>DB: Member 비관적 락 조회
    PS->>DB: PartyVerification 저장 (unique가 최종 방어)
    PS->>PS: member.addExperience(10) → 레벨업 판정
    PS-->>FE: 인증 결과 + 레벨/경험치
```

근거: `PartyService.createPartyVerification`

## 6. 실시간 채팅

```mermaid
sequenceDiagram
    participant FE
    participant HS as StompHandshakeInterceptor
    participant CC as ChatMessageController
    participant CS as ChatService
    participant L as ChatMessagePublishListener
    participant Broker as SimpleBroker

    FE->>HS: WS 핸드셰이크 /ws (Cookie: accessToken)
    HS->>HS: 블랙리스트·JWT 검증 → memberId를 Principal로
    FE->>CC: SEND /app/parties/{partyId}/chat {content}
    CC->>CS: sendMessage (파티원 검증 → ChatMessage 저장)
    CS->>CS: ChatMessageCreatedEvent 발행
    Note over L: AFTER_COMMIT에만 실행 (커밋 전 브로드캐스트 방지)
    L->>Broker: /topic/parties/{partyId} 브로드캐스트
    Broker-->>FE: 파티 구독자 전원 수신
    Note over FE,Broker: 조회는 REST: GET /api/parties/{id}/chats (커서 페이징, id DESC)
```

근거: `WebSocketConfig`, `StompHandshakeInterceptor`, `ChatMessageController`, `ChatService`, `ChatMessagePublishListener`

## 관찰 요약

- 구독(`SUBSCRIBE /topic/parties/{id}`) 시점에 파티원 권한을 재검증하는 인터셉터는 **없음** — 핸드셰이크 시 회원 인증만 수행. 파티원이 아니어도 토픽 구독이 가능한지는 Phase 2 통합 테스트에서 검증할 것(잠재적 위험 후보).
- 스케줄러 2개는 단일 인스턴스 가정(분산 락 없음), 미실행 시 보정 로직 없음(R4).
