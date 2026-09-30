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
