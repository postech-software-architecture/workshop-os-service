package com.postech.foundation;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdentityApiTest {

	@Test
	void onlyTechnicalCallersReceiveCurrentIdentity() {
		var decoder = mock(JwtDecoder.class);
		var identity = mock(LocalIdentity.class);
		var api = new IdentityApi(decoder, identity, "billing-key", "execution-key");
		var jwt = Jwt.withTokenValue("test-token")
			.header("alg", "HS256")
			.subject("11111111-1111-4111-8111-111111111111")
			.build();
		when(decoder.decode("test-token")).thenReturn(jwt);
		when(identity.details(jwt)).thenReturn(new LocalIdentity.Details("demo", List.of("MECANICO")));
		var result = api.introspect("billing-key", new IdentityApi.Request("test-token"));
		assertEquals(true, result.get("active"));
		assertEquals("demo", result.get("username"));
		assertTrue(result.containsKey("clienteId"));
		assertNull(result.get("clienteId"));
		assertEquals(List.of("MECANICO"), result.get("roles"));
		assertThrows(ResponseStatusException.class,
				() -> api.introspect("wrong-key", new IdentityApi.Request("test-token")));
	}

	@Test
	void invalidTokenDoesNotExposeIdentity() {
		var decoder = mock(JwtDecoder.class);
		var identity = mock(LocalIdentity.class);
		when(decoder.decode("bad")).thenThrow(new JwtException("invalid"));
		var result = new IdentityApi(decoder, identity, "billing-key", "execution-key").introspect("execution-key",
				new IdentityApi.Request("bad"));
		assertEquals(java.util.Map.of("active", false), result);
		verifyNoInteractions(identity);
	}

}
