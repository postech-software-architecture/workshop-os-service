package com.postech.foundation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
public class IdentityApi {

	private final JwtDecoder decoder;

	private final LocalIdentity identity;

	private final String billing;

	private final String execution;

	public IdentityApi(JwtDecoder decoder, LocalIdentity identity, @Value("${BILLING_SERVICE_TOKEN}") String billing,
			@Value("${EXECUTION_SERVICE_TOKEN}") String execution) {
		this.decoder = decoder;
		this.identity = identity;
		this.billing = billing;
		this.execution = execution;
	}

	public record Request(String accessToken) {
	}

	@PostMapping("/internal/v1/identidade/introspectar")
	public Map<String, Object> introspect(@RequestHeader("X-Service-Token") String credential,
			@RequestBody Request request) {
		byte[] bytes = credential.getBytes(StandardCharsets.UTF_8);
		if (!MessageDigest.isEqual(bytes, billing.getBytes(StandardCharsets.UTF_8))
				&& !MessageDigest.isEqual(bytes, execution.getBytes(StandardCharsets.UTF_8)))
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		try {
			var jwt = decoder.decode(request.accessToken());
			var details = identity.details(jwt);
			Map<String, Object> result = new java.util.LinkedHashMap<>();
			result.put("active", true);
			result.put("usuarioId", jwt.getSubject());
			result.put("clienteId", details.clienteId());
			result.put("username", details.username());
			result.put("roles", details.roles());
			return result;
		}
		catch (org.springframework.security.core.AuthenticationException
				| org.springframework.security.oauth2.jwt.JwtException invalid) {
			return Map.of("active", false);
		}
	}

}
