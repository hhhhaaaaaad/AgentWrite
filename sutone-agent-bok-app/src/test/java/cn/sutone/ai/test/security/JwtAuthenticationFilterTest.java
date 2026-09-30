package cn.sutone.ai.test.security;

import cn.sutone.ai.trigger.security.JwtAuthenticationFilter;
import cn.sutone.ai.trigger.security.JwtTokenProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link JwtAuthenticationFilter} 的凭据提取测试。
 *
 * <p>覆盖两条通道及其优先级。这个过滤器此前<b>只读 Cookie</b>，导致评测平台
 * （服务端进程，按标准发 {@code Authorization: Bearer}）即便持有合法 token
 * 也一律被判匿名 → 403，跨进程联调完全无法闭环。本测试把「两条通道都通」
 * 钉死，防止将来重构时又把 header 分支删掉。</p>
 *
 * <p>用 Mockito mock 掉 {@link JwtTokenProvider}，因此<b>不依赖真实签名密钥</b>，
 * 也不需要 Spring 上下文——测的是「凭据提取与优先级」这一件事。</p>
 */
@DisplayName("JwtAuthenticationFilter 凭据提取")
class JwtAuthenticationFilterTest {

    private static final String TOKEN = "header.payload.signature";
    private static final long USER_ID = 9_000_000_042L;

    private JwtTokenProvider jwtTokenProvider;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = mock(JwtTokenProvider.class);
        filter = new JwtAuthenticationFilter();
        ReflectionTestUtils.setField(filter, "jwtTokenProvider", jwtTokenProvider);
    }

    @AfterEach
    void clearContext() {
        // SecurityContextHolder 默认是 ThreadLocal，不清理会污染同线程的后续测试。
        SecurityContextHolder.clearContext();
    }

    /** 让 provider 认可任意 token，并返回带角色的声明。 */
    private void acceptTokenAs(String role) {
        when(jwtTokenProvider.validateToken(anyString())).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken(anyString())).thenReturn(USER_ID);
        when(jwtTokenProvider.getRoleFromToken(anyString())).thenReturn(role);
    }

    private Authentication runFilter(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Nested
    @DisplayName("Authorization: Bearer 通道（机器调用方）")
    class BearerChannel {

        @Test
        @DisplayName("合法 Bearer token 注入认证，角色映射为 ROLE_ 前缀")
        void bearerTokenAuthenticates() throws Exception {
            acceptTokenAs("EVAL");
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);

            Authentication auth = runFilter(request);

            assertEquals(USER_ID, auth.getPrincipal(), "principal 应为 token 里的 userId");
            assertTrue(
                    auth.getAuthorities().stream().map(Object::toString).toList()
                            .contains("ROLE_EVAL"),
                    "role=EVAL 应映射为 ROLE_EVAL，否则 hasRole(\"EVAL\") 会拒绝");
        }

        @Test
        @DisplayName("scheme 大小写不敏感（RFC 6750）")
        void bearerSchemeIsCaseInsensitive() throws Exception {
            acceptTokenAs("EVAL");
            for (String header : List.of("bearer " + TOKEN, "BEARER " + TOKEN, "BeArEr " + TOKEN)) {
                SecurityContextHolder.clearContext();
                MockHttpServletRequest request = new MockHttpServletRequest();
                request.addHeader(HttpHeaders.AUTHORIZATION, header);

                assertTrue(runFilter(request) != null, "应接受: " + header);
            }
        }

        @Test
        @DisplayName("token 校验失败时不注入认证")
        void invalidTokenIsAnonymous() throws Exception {
            when(jwtTokenProvider.validateToken(anyString())).thenReturn(false);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);

            assertNull(runFilter(request), "签名/过期校验不通过应保持匿名");
        }

        @Test
        @DisplayName("畸形 header 一律按无凭据处理，且不抛异常")
        void malformedHeaderIsAnonymous() throws Exception {
            // 这是对外暴露的 header，任何人都能构造畸形值。
            // 过滤器必须安静地按「无凭据」处理（最终由 Spring Security 给 403），
            // 而不是抛异常——抛异常会把一个 403 变成 500，还会成为日志灌水入口。
            acceptTokenAs("EVAL");
            for (String header : List.of(
                    "",
                    "Bearer",
                    "Bearer ",
                    "Bearer   ",
                    "Basic " + TOKEN,
                    TOKEN
            )) {
                SecurityContextHolder.clearContext();
                MockHttpServletRequest request = new MockHttpServletRequest();
                request.addHeader(HttpHeaders.AUTHORIZATION, header);

                Authentication auth = assertDoesNotThrow(
                        () -> runFilter(request), "畸形 header 不应抛异常: [" + header + "]");
                assertNull(auth, "畸形 header 不应注入认证: [" + header + "]");
            }
        }
    }

    @Nested
    @DisplayName("Cookie 通道（浏览器，原有行为）")
    class CookieChannel {

        @Test
        @DisplayName("token Cookie 仍然生效（回归）")
        void cookieTokenStillAuthenticates() throws Exception {
            acceptTokenAs(null);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie("token", TOKEN));

            assertEquals(USER_ID, runFilter(request).getPrincipal());
        }

        @Test
        @DisplayName("Cookie 优先于 Bearer（两者同时存在时）")
        void cookieTakesPrecedenceOverBearer() throws Exception {
            // 两条通道带上不同 token 是没有正常场景的畸形输入。
            // 此时保持「浏览器通道行为完全不变」，避免引入只在畸形请求下才触发的分支。
            acceptTokenAs("EVAL");
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie("token", "cookie-token"));
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer header-token");

            runFilter(request);

            org.mockito.Mockito.verify(jwtTokenProvider).validateToken("cookie-token");
            org.mockito.Mockito.verify(jwtTokenProvider, org.mockito.Mockito.never())
                    .validateToken("header-token");
        }

        @Test
        @DisplayName("Cookie 为空串时回退到 Bearer")
        void blankCookieFallsBackToBearer() throws Exception {
            acceptTokenAs("EVAL");
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie("token", ""));
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);

            assertEquals(USER_ID, runFilter(request).getPrincipal());
        }

        @Test
        @DisplayName("Cookie 名不匹配时回退到 Bearer")
        void unrelatedCookieFallsBackToBearer() throws Exception {
            acceptTokenAs("EVAL");
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie("other", TOKEN));
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);

            assertEquals(USER_ID, runFilter(request).getPrincipal());
        }
    }

    @Test
    @DisplayName("无任何凭据时保持匿名")
    void noCredentialsIsAnonymous() throws Exception {
        assertNull(runFilter(new MockHttpServletRequest()));
    }
}
