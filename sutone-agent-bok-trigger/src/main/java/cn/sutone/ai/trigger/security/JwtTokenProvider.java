package cn.sutone.ai.trigger.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT Token 签发与校验
 */
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    /**
     * 用显式密钥构造实例，供 <b>Spring 容器之外</b>的运维工具使用
     * （当前唯一调用方：{@link EvalTokenCli} 签发评测凭据）。
     *
     * <p><b>为什么要有这个入口</b>：容器外需要签 token 时，若另起一套 HS256 实现，
     * 一旦密钥编码、算法或 claim 结构有一处不一致，症状是「签出来的 token 一律 403」——
     * 排查成本很高且极易被误判成权限配置问题。复用本类的 {@code generateToken}
     * 可以从结构上排除这类漂移。</p>
     *
     * <p>刻意用静态工厂而不是加构造器：本类不声明任何构造器，隐式无参构造器供 Spring
     * 注入 {@code @Value} 字段使用；一旦显式声明了带参构造器，隐式无参构造器就消失，
     * 必须再补一个，平白增加一处出错面。这里直接给字段赋值，Spring 侧行为逐字不变。</p>
     */
    public static JwtTokenProvider forSecret(String secret, long expirationMillis) {
        JwtTokenProvider provider = new JwtTokenProvider();
        provider.secret = secret;
        provider.expiration = expirationMillis;
        return provider;
    }

    public String generateToken(Long userId, String username) {
        return generateToken(userId, username, null);
    }

    /** 签发带角色声明的 token（role 为空时与旧版等价） */
    public String generateToken(Long userId, String username, String role) {
        Date now = new Date();
        var builder = Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expiration));
        if (role != null && !role.isBlank()) {
            builder.claim("role", role);
        }
        return builder.signWith(SignatureAlgorithm.HS256, secret.getBytes(StandardCharsets.UTF_8))
                .compact();
    }

    public Long getUserIdFromToken(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    /** 读取角色声明，无角色时返回 null */
    public String getRoleFromToken(String token) {
        return (String) parseClaims(token).get("role");
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .setSigningKey(secret.getBytes(StandardCharsets.UTF_8))
                .parseClaimsJws(token)
                .getBody();
    }
}
