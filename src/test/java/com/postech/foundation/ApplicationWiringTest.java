package com.postech.foundation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.ApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = { "spring.datasource.url=jdbc:h2:mem:wiring;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop", "spring.rabbitmq.host=localhost", "spring.rabbitmq.username=test",
		"spring.rabbitmq.password=test", "spring.rabbitmq.listener.simple.auto-startup=false",
		"foundation.jwt-secret=01234567890123456789012345678901",
		"seguranca.jwt.secret=01234567890123456789012345678901", "foundation.service-token=test",
		"BILLING_SERVICE_TOKEN=billing-test", "EXECUTION_SERVICE_TOKEN=execution-test",
		"management.tracing.enabled=false", "management.otlp.metrics.export.enabled=false" })
@AutoConfigureMockMvc
class ApplicationWiringTest {

	@MockitoBean
	SagaScheduler sagaScheduler;

	@MockitoBean
	Transport transport;

	@Autowired
	ApplicationContext context;

	@Autowired
	MockMvc http;

	@Test
	void domainAndSingleSecurityChainLoad() {
		assertEquals(1, context.getBeansOfType(org.springframework.security.web.SecurityFilterChain.class).size());
		assertEquals(1, context.getBeansOfType(Handler.class).size());
		assertNotNull(context.getBean(com.postech.workshop_service.domain.repositories.OrdemServicoRepository.class));
		assertNotNull(context.getBean(com.postech.workshop_service.domain.repositories.EstoqueRepository.class));
	}

	@Test
	void privateApisRequireAuthentication() throws Exception {
		http.perform(get("/api/v1/ordens-servico")).andExpect(status().isUnauthorized());
	}

	@Test
	void generatedApiKeepsCatalogsAndRemovesDuplicateWriters() throws Exception {
		String api = http.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		var paths = new com.fasterxml.jackson.databind.ObjectMapper().readTree(api).path("paths");
		assertTrue(paths.has("/api/v1/ordens-servico"));
		assertFalse(paths.has("/api/v1/fundacao/ordens-servico"));
		assertFalse(paths.has("/api/v1/orcamentos"));
		java.nio.file.Files.writeString(java.nio.file.Path.of("target/openapi-phase3.json"), api);
		var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		var generated = mapper.readTree(api);
		var published = mapper
			.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("contracts/openapi.json")));
		var alias = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("openapi.json")));
		assertEquals(published, alias, "Root OpenAPI must match canonical contracts/openapi.json");
		assertEquals(generated.path("paths"), published.path("paths"), "Published routes diverged; regenerate OpenAPI");
		assertEquals(generated.path("components"), published.path("components"),
				"Published schemas diverged; regenerate OpenAPI");

	}

	@Autowired
	org.springframework.jdbc.core.JdbcTemplate localDb;

	@Autowired
	org.springframework.transaction.PlatformTransactionManager transactions;

	@Autowired
	SqlMessages messages;

	@Autowired
	LocalIdentity localIdentity;

	@Test
	@org.springframework.transaction.annotation.Transactional
	void introspectionUsesCurrentRolesAndCustomerEligibility() {
		var user = java.util.UUID.randomUUID();
		var customer = java.util.UUID.randomUUID();
		localDb.update(
				"INSERT INTO clientes(id,nome,documento,ativo,data_criacao,data_ultima_atualizacao) VALUES(?,?,?,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
				customer, "Test customer", user.toString().substring(0, 20));
		localDb.update(
				"INSERT INTO usuarios(id,username,senha_hash,cliente_id,ativo,bloqueado,data_criacao,data_ultima_atualizacao) VALUES(?,?,?,?,true,false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
				user, user.toString(), "not-a-login-secret", customer);
		localDb.update("INSERT INTO usuarios_roles(usuario_id,role) VALUES(?,'CLIENTE')", user);
		var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("unit-test")
			.header("alg", "HS256")
			.subject(user.toString())
			.build();
		assertEquals(customer, localIdentity.details(jwt).clienteId());
		assertEquals(java.util.List.of("CLIENTE"), localIdentity.roles(jwt));
		localDb.update("UPDATE usuarios_roles SET role='ATENDENTE' WHERE usuario_id=?", user);
		assertEquals(java.util.List.of("ATENDENTE"), localIdentity.roles(jwt));
		localDb.update("UPDATE clientes SET ativo=false WHERE id=?", customer);
		assertThrows(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,
				() -> localIdentity.details(jwt));
		localDb.update("UPDATE clientes SET ativo=true WHERE id=?", customer);
		localDb.update("UPDATE usuarios SET bloqueado=true WHERE id=?", user);
		assertThrows(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,
				() -> localIdentity.details(jwt));
	}

	@Test
	void businessAndOutboxShareLocalCommitAndRollback() {
		localDb.execute("CREATE TABLE IF NOT EXISTS transaction_probe(id UUID PRIMARY KEY)");
		localDb.execute(
				"CREATE TABLE IF NOT EXISTS outbox(id UUID PRIMARY KEY,body TEXT,exchange_name TEXT,routing_key TEXT,traceparent TEXT)");
		var id = java.util.UUID.randomUUID();
		var event = new Envelope(id, "execucao.diagnostico-admitido.v1", 1, java.time.Instant.now(), "execucao-service",
				id, id, id, id, null, id, 1,
				new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(java.util.Map.of("trabalhoId", id)));
		var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
		assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
			localDb.update("INSERT INTO transaction_probe(id) VALUES(?)", id);
			messages.emit(event, false);
			throw new IllegalStateException("fail before commit");
		}));
		assertEquals(0, localDb.queryForObject("SELECT count(*) FROM transaction_probe WHERE id=?", Integer.class, id));
		assertEquals(0, localDb.queryForObject("SELECT count(*) FROM outbox WHERE id=?", Integer.class, id));
		transaction.executeWithoutResult(status -> {
			localDb.update("INSERT INTO transaction_probe(id) VALUES(?)", id);
			messages.emit(event, false);
		});
		assertEquals(1, localDb.queryForObject("SELECT count(*) FROM transaction_probe WHERE id=?", Integer.class, id));
		assertEquals(1, localDb.queryForObject("SELECT count(*) FROM outbox WHERE id=?", Integer.class, id));
	}

}
