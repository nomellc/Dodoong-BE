# chat — 파티 채팅

## 책임
파티별 실시간 채팅(STOMP), 채팅 내역 커서 페이징 조회, 내 채팅방 목록(마지막 메시지·인원수 포함).

## 구성
| 클래스 | 역할 |
|--------|------|
| `ChatMessageController` | `@MessageMapping("/parties/{partyId}/chat")` — SEND 처리. 오류는 `@SendToUser("/queue/errors")` |
| `ChatController` | REST: GET /api/parties/{id}/chats (커서, size 1~50 클램프), GET /api/chat-rooms |
| `ChatService` | 파티원 검증 → 저장 → `ChatMessageCreatedEvent` 발행 |
| `ChatMessagePublishListener` | `@TransactionalEventListener(AFTER_COMMIT)` → 브로드캐스트 |
| `ChatMessagePublisher` | `SimpMessagingTemplate.convertAndSend("/topic/parties/{id}", msg)` |
| `WebSocketConfig` | simple broker `/topic`, app prefix `/app`, 하트비트 10s/10s(전용 스케줄러 1스레드) |
| `StompHandshakeInterceptor` | 핸드셰이크에서 쿠키 JWT + 블랙리스트 검증, memberId를 attributes로 |
| `WebSocketHandshakeHandler` | attributes의 memberId로 `WebSocketPrincipal` 생성 |

## 흐름 요약
핸드셰이크(인증) → SEND(파티원 검증·저장) → 커밋 후 `/topic/parties/{partyId}` 브로드캐스트. 조회는 REST로 분리(id DESC 커서, 응답은 ASC 재정렬).

## 관찰·이슈
- **구독 권한 검증 부재(추정)**: SUBSCRIBE 시점 인터셉터가 없어, 인증된 회원이면 **파티원이 아니어도 `/topic/parties/{남의 파티}` 구독이 가능해 보임**. 전송(SEND)만 파티원 검증. 실행 검증 전이므로 Phase 2 통합 테스트 후보(잠재적 위험 R10).
- simple broker는 인메모리 — 서버 1대 전제. 스케일아웃 시 Redis pub/sub 등 외부 브로커 필요(README/계획의 Redis는 현재 토큰 저장 용도로만 사용).
- `findLatestMessagesByPartyIds`: 서브쿼리 MAX(id) GROUP BY — 파티 수가 적어 문제없음, Phase 5 측정 후보.
- 채팅 메시지는 회원 탈퇴 시 삭제되지 않음(member.md R8과 연결) — sender FK가 남음.
