package com.postech.foundation;

import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ContactApi {

	private final JdbcTemplate db;

	private final String billingToken;

	public ContactApi(JdbcTemplate db, @Value("${BILLING_SERVICE_TOKEN}") String billingToken) {
		this.db = db;
		this.billingToken = billingToken;
	}

	public record Contact(String email) {
	}

	@GetMapping("/internal/v1/clientes/{id}/contato")
	public Contact get(@PathVariable UUID id, @RequestHeader("X-Service-Token") String credential) {
		if (!MessageDigest.isEqual(credential.getBytes(StandardCharsets.UTF_8),
				billingToken.getBytes(StandardCharsets.UTF_8)))
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		var rows = db.query("SELECT email FROM clientes WHERE id=? AND ativo=true AND data_remocao IS NULL",
				(r, n) -> new Contact(r.getString(1)), id);
		if (rows.isEmpty())
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		return rows.getFirst();
	}

}
