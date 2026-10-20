# auth — 인증·인가

## 책임
회원가입/로그인/로그아웃/토큰 재발급, JWT 발급·검증, Spring Security 연동(필터·EntryPoint·AccessDeniedHandler), Redis 토큰 저장소.

## 주요 클래스
| 클래스 | 역할 |
|--------|------|
| `AuthController` | 4개 엔드포인트. 토큰을 응답 바디가 아닌 **httpOnly 쿠키**로 내려줌 (`CookieUtil`) |
| `AuthService` | 가입(기본 캐릭터 지급 포함)·로그인·로그아웃·재발급 비즈니스 로직 |
| `JwtTokenProvider` | HS256 JWT 생성/파싱. subject=memberId, claim: loginId, tokenType(ACCESS/REFRESH) |
| `JwtAuthenticationFilter` | 쿠키에서 access token 추출 → 블랙리스트 확인 → SecurityContext 설정 |
| `RefreshTokenRepository` | Redis `refresh-token:{memberId}` = SHA-256(token), TTL 14일. 상수 시간 비교(`MessageDigest.isEqual`) |
| `AccessTokenBlacklistRepository` | Redis `blacklist:access-token:{sha256}`, 로그아웃 시 남은 TTL만큼 저장 |
| `CustomUserPrincipal` | record(memberId, loginId). `@AuthenticationPrincipal`로 주입 |

## 토큰 정책 (application.yml)
- Access 30분(1,800,000ms), Refresh 14일(1,209,600,000ms). 쿠키 maxAge도 동일.
- 쿠키: httpOnly, dev는 `secure=false/Lax`, prod는 `secure=true/None`(크로스 사이트 FE 대응).
- 재발급: refresh 유효성 → Redis 해시 일치 → 회원 존재 확인 후 **두 토큰 모두 재발급**(rotation).

## 보안 특성
- 좋은 점: 토큰 해시 저장(Redis 유출 대비), rotation, 블랙리스트, 상수 시간 비교, 로그인 실패 시 동일한 INVALID_CREDENTIALS(계정 열거 방지).
- 트레이드오프: 매 요청 Redis `EXISTS` 1회(블랙리스트) — 장애 시 전체 인증 불가(Redis가 SPOF). 권한(Role) 개념 없음 — authorities 빈 리스트.
- CSRF: 쿠키 기반 인증 + `csrf().disable()` 조합. SameSite(Lax/None)와 CORS allowlist가 사실상의 방어 — **prod의 SameSite=None 상황에서 CORS allowlist가 유일한 방어**이므로 C3(localhost 잔존)과 묶어 볼 것.

## 알려진 이슈 연결
- C5: 가입이 `character_items(id=1)` 시드에 의존 — 새 환경에서 즉시 실패.
- C3: prod CORS에 localhost:5173 잔존.
