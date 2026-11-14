package com.postech.foundation;

import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo")
public class DemoData implements ApplicationRunner {

	private final JdbcTemplate db;

	private final PasswordEncoder encoder;

	private final String password;

	public DemoData(JdbcTemplate db, PasswordEncoder encoder, @Value("${DEMO_PASSWORD}") String password) {
		this.db = db;
		this.encoder = encoder;
		this.password = password;
	}

	@Transactional
	public void run(ApplicationArguments args) throws java.io.IOException {
		if (password.length() < 16)
			throw new IllegalArgumentException("DEMO_PASSWORD_TOO_SHORT");
		String script = new ClassPathResource("demo/catalog.sql").getContentAsString(StandardCharsets.UTF_8);
		for (String statement : script.split(";"))
			if (!statement.isBlank())
				db.execute(statement);
		user("60000000-0000-0000-0000-000000000001", "demo-admin", null, "ADMINISTRADOR");
		user("60000000-0000-0000-0000-000000000002", "demo-mecanico", null, "MECANICO");
		user("60000000-0000-0000-0000-000000000003", "demo-cliente", "10000000-0000-0000-0000-000000000001", "CLIENTE");
	}

	private void user(String id, String username, String customer, String role) {
		UUID user = UUID.fromString(id);
		int inserted = db.update(
				"INSERT INTO usuarios(id,username,senha_hash,cliente_id,ativo,bloqueado,data_criacao,data_ultima_atualizacao) VALUES(?,?,?,?,true,false,now(),now()) ON CONFLICT DO NOTHING",
				user, username, encoder.encode(password), customer == null ? null : UUID.fromString(customer));
		if (inserted == 1)
			db.update("INSERT INTO usuarios_roles(usuario_id,role) VALUES(?,?)", user, role);
	}

}
