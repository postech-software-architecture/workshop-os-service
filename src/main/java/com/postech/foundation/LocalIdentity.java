package com.postech.foundation;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.stereotype.Component;

@Component
public class LocalIdentity implements Identity {

	private final JdbcTemplate db;

	public LocalIdentity(JdbcTemplate db) {
		this.db = db;
	}

	public record Details(String username, List<String> roles, UUID clienteId) {
		public Details(String username, List<String> roles) {
			this(username, roles, null);
		}
	}

	public Details details(Jwt jwt) {
		var rows = db.query(
				"SELECT u.username,string_agg(r.role,',' ORDER BY r.role),u.cliente_id FROM usuarios u JOIN usuarios_roles r ON r.usuario_id=u.id LEFT JOIN clientes c ON c.id=u.cliente_id WHERE u.id=? AND u.ativo=true AND u.bloqueado=false AND u.data_remocao IS NULL AND (u.cliente_id IS NULL OR (c.ativo=true AND c.data_remocao IS NULL)) GROUP BY u.id,u.username,u.cliente_id",
				(r, n) -> new Details(r.getString(1), Arrays.asList(r.getString(2).split(",")),
						r.getObject(3, UUID.class)),
				UUID.fromString(jwt.getSubject()));
		if (rows.isEmpty())
			throw new OAuth2AuthenticationException("invalid_token");
		return rows.getFirst();
	}

	public List<String> roles(Jwt jwt) {
		return details(jwt).roles();
	}

}
