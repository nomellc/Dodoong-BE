# ERD — 실제 코드 기준

> 엔티티 클래스와 로컬 MySQL(ddl-auto가 생성한 스키마, Phase 0에서 확인)을 기준으로 작성.
> README의 ERD 이미지와의 차이는 하단 대조표 참조.

```mermaid
erDiagram
    members {
        bigint member_id PK
        varchar login_id UK "(50) NOT NULL"
        varchar password "(255) NOT NULL, BCrypt"
        varchar nickname UK "(50) NULL 허용"
        varchar profile_image_key "(255) S3 key 저장"
        varchar introduction "(255)"
        int level "NOT NULL, 기본 1"
        int experience "NOT NULL, 기본 0"
        int coin "NOT NULL, 기본 0"
        datetime created_at
        datetime updated_at
    }

    character_items {
        bigint character_id PK
        varchar name "(20) NOT NULL"
        varchar summary "(500) NOT NULL"
        text description "NOT NULL"
        int price "NOT NULL"
    }

    member_character {
        bigint member_character_id PK
        bit is_equipped "NOT NULL"
        bigint member_id FK
        bigint character_id FK
    }

    daily_quests {
        bigint id PK
        varchar quest_category "(30) ENUM STRING"
        varchar content "(100) NOT NULL"
        date quest_date "NOT NULL"
        bit is_checked "NOT NULL"
        bigint member_id FK "NOT NULL"
        bigint routine_id FK "NULL = 단일 퀘스트"
        datetime created_at
        datetime updated_at
    }

    routines {
        bigint id PK
        varchar quest_category "(30)"
        varchar content "(100) NOT NULL"
        date end_date "NOT NULL"
        bigint member_id FK "NOT NULL"
        datetime created_at
        datetime updated_at
    }

    repeat_days {
        bigint routine_id FK
        varchar day_of_week "(10) ElementCollection"
    }

    streaks {
        bigint id PK
        date start_date "NOT NULL"
        date last_checked_date "NOT NULL"
        int consecutive_days "NOT NULL"
        bigint member_id FK "NOT NULL"
        datetime created_at
        datetime updated_at
    }

    parties {
        bigint id PK
        varchar name "(255) NOT NULL"
        varchar description "(255)"
        text category "CSV 직렬화 (converter)"
        varchar image_url "(255) 실제론 S3 key"
        int current_members "NOT NULL"
        int max_members "NOT NULL"
        bit is_recruiting "NOT NULL"
        bit is_public "NOT NULL"
        varchar party_password "(255) BCrypt, NULL=없음"
        varchar quest_content "(255) NOT NULL"
        datetime created_at
        datetime updated_at
    }

    party_members {
        bigint party_member_id PK
        varchar role "(255) LEADER | MEMBER"
        bigint member_id FK "NOT NULL"
        bigint party_id FK "NOT NULL"
        datetime created_at
        datetime updated_at
    }

    party_verifications {
        bigint party_verification_id PK
        bigint party_id FK "NOT NULL"
        bigint party_member_id FK "NOT NULL"
        varchar image_url "(255) NOT NULL"
        bit verified "NOT NULL"
        date verification_date "NOT NULL"
        datetime created_at
        datetime updated_at
    }

    chat_messages {
        bigint chat_message_id PK
        varchar content "(500) NOT NULL"
        bigint party_id FK "NOT NULL"
        bigint member_id FK "sender, NOT NULL"
        datetime created_at
        datetime updated_at
    }

    members ||--o{ member_character : "보유"
    character_items ||--o{ member_character : ""
    members ||--o{ daily_quests : ""
    routines |o--o{ daily_quests : "루틴 생성분"
    members ||--o{ routines : ""
    routines ||--o{ repeat_days : "반복 요일"
    members ||--o{ streaks : ""
    members ||--o{ party_members : ""
    parties ||--o{ party_members : ""
    parties ||--o{ party_verifications : ""
    party_members ||--o{ party_verifications : ""
    parties ||--o{ chat_messages : ""
    members ||--o{ chat_messages : "sender"
```

## 고유 제약 (코드에 명시된 것)

| 테이블 | 제약 | 의미 |
|--------|------|------|
| members | `login_id` unique, `nickname` unique | 로그인 ID·닉네임 중복 방지 |
| daily_quests | unique(`routine_id`, `quest_date`) | 같은 루틴의 같은 날 퀘스트 1개 — 스케줄러 중복 실행 방어 |
| party_members | unique(`member_id`, `party_id`) | 중복 가입 방지 |
| party_verifications | unique(`party_member_id`, `verification_date`) | 하루 1회 인증 — 서비스 이중 체크의 최종 방어선 |
| member_character | unique(`member_id`, `character_id`) | 캐릭터 중복 보유 방지 |

## README ERD 이미지와 실제 구현의 차이

README의 ERD 이미지(2346×1219 PNG)를 판독해 전체 테이블·컬럼을 대조한 결과입니다.
**실제 코드를 기준으로 삼고, README ERD는 설계 당시 스냅샷으로 간주합니다.**

| # | 항목 | README ERD | 실제 코드 | 판정 |
|---|------|-----------|----------|------|
| 1 | routine의 `endDate` | **없음** | `end_date NOT NULL` — 루틴 전개·가상 캘린더의 핵심 컬럼 | **ERD 누락(중요)** |
| 2 | party의 `currentMembers` | **없음** | `current_members NOT NULL` — 가입/탈퇴 시 증감, 모집 상태 갱신에 사용 | **ERD 누락(중요)** — 사전 분석 R2와 직결 |
| 3 | repeatDays 표현 | routine의 `SET<DayOfWeek>` 단일 컬럼 | 별도 `repeat_days` 테이블(@ElementCollection) | 구조 다름 |
| 4 | party_verifications 컬럼 | id, createdAt, imageUrl, partyMemberId만 | + `party_id`, `verified`, `verification_date`, unique 제약 | ERD 누락 |
| 5 | members 프로필 이미지 | `profileImageUrl VARCHAR(500)` | `profile_image_key` — URL이 아니라 **S3 key** 저장 | 이름·의미 다름 |
| 6 | 테이블명 | daily_quest, routine, party, chat, party_quest_verification (단수) | daily_quests, routines, parties, chat_messages, party_verifications (복수) | 명명 불일치 |
| 7 | 길이 제약 | daily_quest.content 500, routine.content 500, nickname 30, introduction 500 | content 100, content 100, nickname 50, introduction 255 | 수치 불일치 |
| 8 | party.category | `SET<ENUM>` | `TEXT` + `CategoryListConverter`(CSV 문자열) | 구현 방식 다름 |
| 9 | 감사 컬럼 | chat·verification에만 createdAt 표기 | `BaseTimeEntity`로 대부분 테이블에 created_at/updated_at 존재 (CharacterItem, MemberCharacter 제외) | ERD 미표기 |
| 10 | party.name 길이 | VARCHAR(50) | 길이 미지정 → varchar(255) | 수치 불일치 |

결론: 관계 구조(FK 방향, 카디널리티)는 README ERD와 실제 코드가 일치하나, **컬럼 수준에서는 ERD가 구버전**입니다. 특히 #1, #2는 비즈니스 로직을 이해하는 데 필요한 컬럼이므로 본 문서의 ERD를 기준으로 사용해야 합니다.
