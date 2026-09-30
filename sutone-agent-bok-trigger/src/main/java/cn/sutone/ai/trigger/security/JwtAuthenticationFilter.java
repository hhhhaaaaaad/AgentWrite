package cn.sutone.ai.trigger.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器 —— 提取 token 并注入 SecurityContext。
 *
 * <p>支持两条通道，<b>Cookie 优先</b>：</p>
 * <ol>
 *   <li>{@code token} httpOnly Cookie —— 浏览器通道，也是本过滤器最早支持的唯一通道；</li>
 *   <li>{@code Authorization: Bearer <jwt>} —— 机器调用方通道。</li>
 * </ol>
 *
 * <p><b>为什么必须支持 Bearer</b>：评测平台（eval-platform）是服务端进程，不是浏览器。
 * 它按服务间调用的标准形态发 {@code Authorization: Bearer}，而本过滤器原先只读 Cookie，
 * 于是即便 token 合法且带 {@code role=EVAL}，请求仍被 Spring Security 判为匿名 → 403。
 * 这个缺口让「评测平台调用 AgentWrite」在真实联调时完全无法闭环，且因为
 * Cookie 通道在浏览器里一直是通的，问题只在跨进程调用时暴露。</p>
 *
 * <p><b>为什么是「新增」而不是「替换」</b>：浏览器通道的行为逐字不变（Cookie 优先，
 * 无 Cookie 时才回退到 header），httpOnly 对 XSS 的防护不受影响——两条通道校验的是
 * 同一个 token、同一套签名与过期规则，不存在第二条更弱的认证路径。</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String TOKEN_COOKIE = "token";

    /** RFC 6750 要求 scheme 大小写不敏感（Bearer / bearer / BEARER 均合法）。 */
    private static final String BEARER_PREFIX = "Bearer ";

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String token = extractToken(request);

        if (token != null && jwtTokenProvider.validateToken(token)) {
            Long userId = jwtTokenProvider.getUserIdFromToken(token);
            String role = jwtTokenProvider.getRoleFromToken(token);
            List<GrantedAuthority> authorities = role != null && !role.isBlank()
                    ? List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    : List.of();
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        chain.doFilter(request, response);
    }

    /**
     * 提取 token：Cookie 优先，无 Cookie 时回退到 {@code Authorization: Bearer}。
     *
     * <p>Cookie 优先是刻意的：两条通道同时带上不同 token 是没有正常场景的异常输入，
     * 此时保持「浏览器通道的行为完全不变」，避免引入一个只在畸形请求下才触发的分支。</p>
     */
    private String extractToken(HttpServletRequest request) {
        String fromCookie = extractTokenFromCookie(request);
        if (fromCookie != null && !fromCookie.isBlank()) {
            return fromCookie;
        }
        return extractTokenFromAuthorizationHeader(request);
    }

    private String extractTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (TOKEN_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * 解析 {@code Authorization: Bearer <jwt>}。
     *
     * <p>不符合 Bearer 形态（缺失、scheme 不对、token 为空）一律返回 {@code null}——
     * 由调用方按「无凭据」处理，最终由 Spring Security 给出 403。
     * <b>不抛异常、不记 WARN</b>：这是一个对外暴露的 header，任何人都能构造畸形值，
     * 为它打日志等于给攻击者一个低成本的日志灌水入口。</p>
     */
    private String extractTokenFromAuthorizationHeader(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.length() <= BEARER_PREFIX.length()) {
            return null;
        }
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
