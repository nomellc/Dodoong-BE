# global — 공통 인프라 계층

## config
| 클래스 | 내용 |
|--------|------|
| `SecurityConfig` | 세션리스, CSRF/formLogin/httpBasic 비활성, permitAll 목록, JWT 필터 등록, CORS(credentials 허용, 오리진은 `CorsProperties`) |
| `CorsProperties` / `JwtProperties` / `S3Properties` | `@ConfigurationProperties` record — 타입 세이프 설정 |
| `QuerydslConfig` | JPAQueryFactory 빈 |
| `S3Config` | S3Client/S3Presigner (리전·자격증명은 기본 체인) |
| `SwaggerConfig` | springdoc. **운영에서도 Swagger permitAll** — 노출 범위 재검토 후보 |
| `WebSocketConfig` | chat.md 참조 |

## response
- `BaseCode` 인터페이스 ← `SuccessCode`/`ErrorCode` enum (HTTP 상태 + 코드 문자열 + 메시지).
- `BaseResponse<T>` 정적 팩토리(ok/created/noContent/of) + `BaseResponseStatusAdvice`가 바디의 코드에 맞춰 실제 HTTP 상태 설정.

## exception
- `CustomException(BaseCode)` 단일 예외 + `GlobalExceptionHandler`.
- 처리 범위: 검증(MethodArgumentNotValid, ConstraintViolation), 파라미터/헤더 누락, 타입 미스매치, 바디 파싱, AccessDenied, **락 타임아웃(PessimisticLockingFailure/LockTimeout → LOCK_TIMEOUT)**, 그 외 500.
- 주의: import된 `AccessDeniedException`이 `java.nio.file` 패키지의 것 — Spring Security의 `org.springframework.security.access.AccessDeniedException`과 다름. Security 쪽은 `CustomAccessDeniedHandler`가 처리하므로 실해는 없으나 혼동 소지(코드 품질 이슈).

## storage
- `FileStorage` 인터페이스 ← `S3FileStorage`.
- 기능: multipart 직접 업로드(jpeg/png/webp만, UUID 키), Presigned PUT 발급, Presigned GET 발급, public URL 변환, 삭제, URL→key 추출.
- `toDownloadUrlOrNull`은 인터페이스 default 메서드(null/blank → null, 그 외 Presigned GET). 파티 이미지는 이 방식, 프로필 이미지는 `toPublicUrl`(public URL) — **같은 버킷인데 접근 방식 2종 혼재**(버킷 정책 확인 필요).

## util / websocket / converter
- `CookieUtil`: 쿠키 생성·삭제. secure/sameSite는 프로파일별 설정 주입.
- `CategoryListConverter`: List<PartyCategory> ↔ CSV 문자열. **DB에서 LIKE 검색하는 구조의 원인**(party.md R5).
- websocket 3종: chat.md 참조.

## 설정 파일 요점 (application*.yml)
- dev/prod 공통: `ddl-auto: update`(C2), JWT 만료 동일, actuator는 health/info만.
- prod 전용: open-in-view=false(**dev엔 없음 — 기본 true**, dev/prod 동작 차이), forward-headers-strategy(framework), 쿠키 secure/None, CORS에 Vercel + localhost(C3).
- dev 전용: show-sql=true.
- **open-in-view 불일치는 dev에서만 LazyInitializationException이 안 나는 식의 환경차 버그를 만들 수 있음** — 개선 후보.
