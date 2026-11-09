package com.postech.foundation;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AuthIdentityApi {

	private final JdbcTemplate db;

	private final String token;

	public AuthIdentityApi(JdbcTemplate db, @Value("${AUTH_SERVICE_TOKEN:}") String token) {
		this.db = db;
		this.token = token;
	}

	public record Request(String cpf) {
	}

	public record Identity(UUID id, String username, boolean usuarioAtivo, boolean bloqueado, boolean usuarioRemovido,
			boolean clienteAtivo, boolean clienteRemovido, Set<String> roles) {
	}

	@PostMapping("/internal/v1/identidade/por-cpf")
	public Identity lookup(@RequestHeader("X-Service-Token") String credential, @RequestBody Request request) {
		if (token.isBlank() || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
				credential.getBytes(StandardCharsets.UTF_8)))
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		if (request.cpf() == null || !request.cpf().matches("[0-9]{11}"))
			throw new IllegalArgumentException("INVALID_CPF");
		var rows = db.query(
				"SELECT u.id,u.username,string_agg(r.role,',' ORDER BY r.role) roles FROM usuarios u JOIN clientes c ON c.id=u.cliente_id JOIN usuarios_roles r ON r.usuario_id=u.id WHERE c.documento=? AND u.ativo=true AND u.bloqueado=false AND u.data_remocao IS NULL AND c.ativo=true AND c.data_remocao IS NULL AND EXISTS(SELECT 1 FROM usuarios_roles allowed WHERE allowed.usuario_id=u.id AND allowed.role='CLIENTE') GROUP BY u.id,u.username",
				(r, n) -> new Identity(r.getObject("id", UUID.class), r.getString("username"), true, false, false, true,
						false, new LinkedHashSet<>(Arrays.asList(r.getString("roles").split(",")))),
				request.cpf());
		if (rows.size() != 1)
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		return rows.getFirst();
	}

}
