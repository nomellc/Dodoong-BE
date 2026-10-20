package com.samdasu.dodoong.domain.auth.service;

import com.samdasu.dodoong.domain.auth.dto.AuthResult;
import com.samdasu.dodoong.domain.auth.dto.request.LoginRequest;
import com.samdasu.dodoong.domain.auth.dto.request.SignupRequest;
import com.samdasu.dodoong.domain.auth.dto.response.TokenResponse;
import com.samdasu.dodoong.domain.auth.repository.AccessTokenBlacklistRepository;
import com.samdasu.dodoong.domain.auth.repository.RefreshTokenRepository;
import com.samdasu.dodoong.domain.auth.security.JwtTokenProvider;
import com.samdasu.dodoong.domain.character.entity.CharacterItem;
import com.samdasu.dodoong.domain.character.entity.MemberCharacter;
import com.samdasu.dodoong.domain.character.repository.CharacterItemRepository;
import com.samdasu.dodoong.domain.character.repository.MemberCharacterRepository;
import com.samdasu.dodoong.domain.member.entity.Member;
import com.samdasu.dodoong.domain.member.repository.MemberRepository;
import com.samdasu.dodoong.global.exception.CustomException;
import com.samdasu.dodoong.global.response.code.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AuthService 단위 테스트 (Phase 2).
 * 규칙 출처: docs/analysis/domains/auth.md — 가입 시 기본 캐릭터(id=1) 지급·장착,
 * 로그인 실패는 원인 구분 없이 INVALID_CREDENTIALS(계정 열거 방지),
 * 로그아웃은 refresh 문제와 무관하게 access 블랙리스트만은 완료, 재발급은 rotation.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private MemberRepository memberRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private CharacterItemRepository characterItemRepository;
    @Mock private MemberCharacterRepository memberCharacterRepository;
    @Mock private AccessTokenBlacklistRepository accessTokenBlacklistRepository;

    @InjectMocks
    private AuthService authService;

    private Member createMember(Long id, String loginId) {
        Member member = Member.builder()
                .loginId(loginId)
                .encodedPassword("encodedPassword")
                .build();
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    @Nested
    @DisplayName("signup — 회원가입")
    class Signup {

        @Test
        @DisplayName("가입하면 기본 캐릭터(id=1)를 장착 상태로 지급하고 refresh token을 저장한다")
        void signupGrantsDefaultCharacterAndSavesRefreshToken() {
            SignupRequest request = new SignupRequest("dodoong", "password1!");
            Member savedMember = createMember(7L, "dodoong");
            CharacterItem defaultCharacter = mock(CharacterItem.class);

            when(memberRepository.existsByLoginId("dodoong")).thenReturn(false);
            when(passwordEncoder.encode("password1!")).thenReturn("encodedPassword");
            when(memberRepository.save(any(Member.class))).thenReturn(savedMember);
            when(characterItemRepository.findById(1L)).thenReturn(Optional.of(defaultCharacter));
            when(jwtTokenProvider.issueTokens(savedMember))
                    .thenReturn(new TokenResponse("access-token", "refresh-token"));

            AuthResult result = authService.signup(request);

            assertThat(result.id()).isEqualTo(7L);
            assertThat(result.loginId()).isEqualTo("dodoong");
            assertThat(result.accessToken()).isEqualTo("access-token");
            assertThat(result.refreshToken()).isEqualTo("refresh-token");

            ArgumentCaptor<MemberCharacter> captor = ArgumentCaptor.forClass(MemberCharacter.class);
            verify(memberCharacterRepository).save(captor.capture());
            assertThat(captor.getValue().isEquipped()).isTrue();
            verify(refreshTokenRepository).save(7L, "refresh-token");
        }

        @Test
        @DisplayName("중복 loginId면 DUPLICATE_LOGIN_ID 예외가 발생하고 아무것도 저장하지 않는다")
        void signupRejectsDuplicateLoginId() {
            when(memberRepository.existsByLoginId("dodoong")).thenReturn(true);

            assertThatThrownBy(() -> authService.signup(new SignupRequest("dodoong", "password1!")))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.DUPLICATE_LOGIN_ID);

            verify(memberRepository, never()).save(any());
            verifyNoInteractions(refreshTokenRepository, memberCharacterRepository);
        }

        @Test
        @DisplayName("[C5 문서화] 기본 캐릭터 시드(id=1)가 없으면 가입이 CHARACTER_NOT_FOUND로 실패한다")
        void signupFailsWithoutDefaultCharacterSeed() {
            when(memberRepository.existsByLoginId("dodoong")).thenReturn(false);
            when(passwordEncoder.encode(any())).thenReturn("encodedPassword");
            when(memberRepository.save(any(Member.class))).thenReturn(createMember(7L, "dodoong"));
            when(characterItemRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.signup(new SignupRequest("dodoong", "password1!")))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.CHARACTER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("login — 로그인")
    class Login {

        @Test
        @DisplayName("자격증명이 맞으면 토큰을 발급하고 refresh token을 저장한다")
        void loginIssuesTokens() {
            Member member = createMember(7L, "dodoong");
            when(memberRepository.findByLoginId("dodoong")).thenReturn(Optional.of(member));
            when(passwordEncoder.matches("password1!", "encodedPassword")).thenReturn(true);
            when(jwtTokenProvider.issueTokens(member))
                    .thenReturn(new TokenResponse("access-token", "refresh-token"));

            AuthResult result = authService.login(new LoginRequest("dodoong", "password1!"));

            assertThat(result.accessToken()).isEqualTo("access-token");
            verify(refreshTokenRepository).save(7L, "refresh-token");
        }

        @Test
        @DisplayName("존재하지 않는 loginId도, 비밀번호 불일치도 같은 INVALID_CREDENTIALS다 (계정 열거 방지)")
        void loginFailsWithSameErrorForUnknownIdAndWrongPassword() {
            when(memberRepository.findByLoginId("unknown")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(new LoginRequest("unknown", "password1!")))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

            Member member = createMember(7L, "dodoong");
            when(memberRepository.findByLoginId("dodoong")).thenReturn(Optional.of(member));
            when(passwordEncoder.matches("wrong", "encodedPassword")).thenReturn(false);

            assertThatThrownBy(() -> authService.login(new LoginRequest("dodoong", "wrong")))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

            verify(jwtTokenProvider, never()).issueTokens(any());
        }
    }

    @Nested
    @DisplayName("logout — 로그아웃")
    class Logout {

        @Test
        @DisplayName("유효한 access·refresh 토큰이면 블랙리스트 등록 후 refresh token을 삭제한다")
        void logoutBlacklistsAccessAndDeletesRefresh() {
            when(jwtTokenProvider.getRemainingExpiration("access-token"))
                    .thenReturn(Duration.ofMinutes(10));
            when(jwtTokenProvider.validateRefreshToken("refresh-token")).thenReturn(true);
            when(jwtTokenProvider.getMemberId("refresh-token")).thenReturn(7L);
            when(refreshTokenRepository.matches(7L, "refresh-token")).thenReturn(true);

            authService.logout(7L, "access-token", "refresh-token");

            verify(accessTokenBlacklistRepository).save("access-token", Duration.ofMinutes(10));
            verify(refreshTokenRepository).deleteByMemberId(7L);
        }

        @Test
        @DisplayName("이미 만료된 access token은 블랙리스트에 넣지 않는다")
        void logoutSkipsBlacklistForExpiredAccessToken() {
            when(jwtTokenProvider.getRemainingExpiration("expired-token")).thenReturn(Duration.ZERO);
            when(jwtTokenProvider.validateRefreshToken(any())).thenReturn(false);

            authService.logout(7L, "expired-token", "refresh-token");

            verify(accessTokenBlacklistRepository, never()).save(any(), any());
        }

        @Test
        @DisplayName("refresh token이 없어도 access token 블랙리스트는 완료된다")
        void logoutCompletesAccessBlacklistWithoutRefreshToken() {
            when(jwtTokenProvider.getRemainingExpiration("access-token"))
                    .thenReturn(Duration.ofMinutes(10));

            authService.logout(7L, "access-token", null);

            verify(accessTokenBlacklistRepository).save("access-token", Duration.ofMinutes(10));
            verify(refreshTokenRepository, never()).deleteByMemberId(any());
        }

        @Test
        @DisplayName("refresh token의 주인이 요청자와 다르면 refresh token을 삭제하지 않는다")
        void logoutDoesNotDeleteRefreshTokenOwnedByAnotherMember() {
            when(jwtTokenProvider.getRemainingExpiration("access-token"))
                    .thenReturn(Duration.ofMinutes(10));
            when(jwtTokenProvider.validateRefreshToken("refresh-token")).thenReturn(true);
            when(jwtTokenProvider.getMemberId("refresh-token")).thenReturn(99L);

            authService.logout(7L, "access-token", "refresh-token");

            verify(refreshTokenRepository, never()).deleteByMemberId(any());
        }

        @Test
        @DisplayName("Redis에 저장된 refresh token과 다르면 삭제하지 않는다")
        void logoutDoesNotDeleteWhenStoredTokenDiffers() {
            when(jwtTokenProvider.getRemainingExpiration("access-token"))
                    .thenReturn(Duration.ofMinutes(10));
            when(jwtTokenProvider.validateRefreshToken("refresh-token")).thenReturn(true);
            when(jwtTokenProvider.getMemberId("refresh-token")).thenReturn(7L);
            when(refreshTokenRepository.matches(7L, "refresh-token")).thenReturn(false);

            authService.logout(7L, "access-token", "refresh-token");

            verify(refreshTokenRepository, never()).deleteByMemberId(any());
        }
    }

    @Nested
    @DisplayName("reissue — 토큰 재발급(rotation)")
    class Reissue {

        @Test
        @DisplayName("유효한 refresh token이면 access·refresh를 모두 재발급하고 저장소를 교체한다")
        void reissueRotatesBothTokens() {
            Member member = createMember(7L, "dodoong");
            when(jwtTokenProvider.validateRefreshToken("old-refresh")).thenReturn(true);
            when(jwtTokenProvider.getMemberId("old-refresh")).thenReturn(7L);
            when(refreshTokenRepository.matches(7L, "old-refresh")).thenReturn(true);
            when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
            when(jwtTokenProvider.issueTokens(member))
                    .thenReturn(new TokenResponse("new-access", "new-refresh"));

            TokenResponse response = authService.reissue("old-refresh");

            assertThat(response.accessToken()).isEqualTo("new-access");
            assertThat(response.refreshToken()).isEqualTo("new-refresh");
            verify(refreshTokenRepository).save(7L, "new-refresh");
        }

        @Test
        @DisplayName("null·빈 문자열·서명이 깨진 refresh token은 모두 INVALID_REFRESH_TOKEN이다")
        void reissueRejectsMissingOrInvalidToken() {
            assertReissueFails(null);
            assertReissueFails(" ");

            when(jwtTokenProvider.validateRefreshToken("broken")).thenReturn(false);
            assertReissueFails("broken");
        }

        @Test
        @DisplayName("Redis의 토큰과 다르면(탈취·이미 rotation됨) 재발급을 거부한다")
        void reissueRejectsWhenStoredTokenDiffers() {
            when(jwtTokenProvider.validateRefreshToken("stale-refresh")).thenReturn(true);
            when(jwtTokenProvider.getMemberId("stale-refresh")).thenReturn(7L);
            when(refreshTokenRepository.matches(7L, "stale-refresh")).thenReturn(false);

            assertReissueFails("stale-refresh");
        }

        @Test
        @DisplayName("토큰은 유효하나 회원이 탈퇴해 없으면 재발급을 거부한다")
        void reissueRejectsWhenMemberNoLongerExists() {
            when(jwtTokenProvider.validateRefreshToken("orphan-refresh")).thenReturn(true);
            when(jwtTokenProvider.getMemberId("orphan-refresh")).thenReturn(7L);
            when(refreshTokenRepository.matches(7L, "orphan-refresh")).thenReturn(true);
            when(memberRepository.findById(7L)).thenReturn(Optional.empty());

            assertReissueFails("orphan-refresh");
        }

        private void assertReissueFails(String refreshToken) {
            assertThatThrownBy(() -> authService.reissue(refreshToken))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getBaseCode())
                    .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

    @Nested
    @DisplayName("invalidateTokens — 탈퇴 등 강제 토큰 정리")
    class InvalidateTokens {

        @Test
        @DisplayName("access token을 블랙리스트에 넣고 refresh token을 삭제한다")
        void invalidatesBothTokens() {
            when(jwtTokenProvider.getRemainingExpiration("access-token"))
                    .thenReturn(Duration.ofMinutes(5));

            authService.invalidateTokens(7L, "access-token");

            verify(accessTokenBlacklistRepository).save("access-token", Duration.ofMinutes(5));
            verify(refreshTokenRepository).deleteByMemberId(7L);
        }
    }
}
