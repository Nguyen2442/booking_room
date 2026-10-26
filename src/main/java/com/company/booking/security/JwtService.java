package com.company.booking.security;

import com.company.booking.common.AppProperties;
import com.company.booking.user.Role;
import com.company.booking.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    /** A verified token: who it claims to be and the account version it was issued under. */
    public record ParsedToken(AuthUser user, int version) {}

    private final SecretKey key;
    private final Duration ttl;
    private final String issuer;
    private final Clock clock;

    public JwtService(AppProperties props, Clock clock) {
        this.key = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.ttl = props.jwt().ttl();
        this.issuer = props.jwt().issuer();
        this.clock = clock;
    }

    public Duration ttl() {
        return ttl;
    }

    public String issue(User user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(issuer)
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("ver", user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** Returns empty for any invalid token (bad signature, expired, malformed, wrong issuer) - never throws. */
    public Optional<ParsedToken> parse(String token) {
        try {
            Claims c = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Integer ver = c.get("ver", Integer.class);
            return Optional.of(new ParsedToken(new AuthUser(
                    UUID.fromString(c.getSubject()), c.get("email", String.class), Role.valueOf(c.get("role", String.class))),
                    ver == null ? 0 : ver));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
