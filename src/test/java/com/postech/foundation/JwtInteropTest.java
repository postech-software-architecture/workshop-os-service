package com.postech.foundation;

import com.postech.workshop_service.infrastructure.security.*;
import com.postech.workshop_service.domain.entities.Usuario;
import com.postech.workshop_service.domain.enums.Role;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JwtInteropTest {

	final String secret = "01234567890123456789012345678901234567890123456789";

	JwtTokenService issuer() {
		var properties = new JwtSecurityProperties();
		properties.setSecret(secret);
		properties.setExpiracaoAccessSegundos(3600);
		properties.setExpiracaoRefreshDias(7);
		return new JwtTokenService(properties);
	}

	@Test
	void originalLoginTokensAreAcceptedByServiceDecoder() {
		var user = new Usuario("test-user", "user@example.com", "hash", Set.of(Role.ADMINISTRADOR), null);
		var jwt = new Security().decoder(secret).decode(issuer().gerarAccessToken(user));
		assertEquals(user.getId().toString(), jwt.getSubject());
	}

	@Test
	void legacyParserAlsoRejectsTokensWithoutExpiration() {
		var id = UUID.randomUUID();
		var key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		var token = io.jsonwebtoken.Jwts.builder()
			.subject(id.toString())
			.issuer("workshop-auth")
			.audience()
			.add("workshop-service")
			.and()
			.signWith(key, io.jsonwebtoken.Jwts.SIG.HS256)
			.compact();
		assertFalse(issuer().validarAccessToken(token, id));
		assertThrows(org.springframework.security.oauth2.jwt.JwtException.class,
				() -> new Security().decoder(secret).decode(token));
	}

}
