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
class CatalogHttpTest {

	@MockitoBean
	Transport transport;

	@MockitoBean
	SagaScheduler sagaScheduler;

	@Autowired
	MockMvc http;

	final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper()
		.findAndRegisterModules();

	org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
		var user = new com.postech.workshop_service.domain.entities.Usuario("catalog-admin", "admin@example.com",
				"hash", java.util.Set.of(com.postech.workshop_service.domain.enums.Role.ADMINISTRADOR), null);
		return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
			.user(com.postech.workshop_service.infrastructure.security.UsuarioAutenticadoPrincipal.fromDomain(user));
	}

	com.fasterxml.jackson.databind.JsonNode call(String method, String path, Object body) throws Exception {
		var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.request(org.springframework.http.HttpMethod.valueOf(method), path)
			.with(admin());
		if (body != null)
			request.contentType("application/json").content(json.writeValueAsString(body));
		String result = http.perform(request)
			.andExpect(status().is2xxSuccessful())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return result.isBlank() ? json.nullNode() : json.readTree(result);
	}

	java.util.Map<String, Object> customer() {
		return new java.util.HashMap<>(java.util.Map.of("nome", "Cliente de regressao", "documento", "12345678909",
				"email", "customer@example.com", "telefone", "11999999999"));
	}

	@Test
	@org.springframework.transaction.annotation.Transactional
	void customersRemainCreatableSearchableEditableAndRemovable() throws Exception {
		var payload = customer();
		String id = call("POST", "/api/v1/clientes", payload).path("id").asText();
		assertEquals("Cliente de regressao", call("GET", "/api/v1/clientes/" + id, null).path("nome").asText());
		call("GET", "/api/v1/clientes/documento/12345678909", null);
		call("GET", "/api/v1/clientes", null);
		payload.put("nome", "Nome atualizado");
		assertEquals("Nome atualizado", call("PUT", "/api/v1/clientes/" + id, payload).path("nome").asText());
		call("DELETE", "/api/v1/clientes/" + id, null);
	}

	@Test
	@org.springframework.transaction.annotation.Transactional
	void vehiclePreservesCustomerLinkAndPlateLookup() throws Exception {
		String customer = call("POST", "/api/v1/clientes", customer()).path("id").asText();
		var payload = new java.util.HashMap<String, Object>(java.util.Map.of("placa", "ABC1D23", "marca", "Toyota",
				"modelo", "Corolla", "ano", 2020, "clientesIds", java.util.List.of(customer)));
		String id = call("POST", "/api/v1/veiculos", payload).path("id").asText();
		call("GET", "/api/v1/veiculos/" + id, null);
		call("GET", "/api/v1/veiculos/placa/ABC1D23", null);
		call("GET", "/api/v1/veiculos/cliente/" + customer, null);
		call("GET", "/api/v1/veiculos", null);
		payload.put("modelo", "Corolla revisado");
		call("PUT", "/api/v1/veiculos/" + id, payload);
		call("DELETE", "/api/v1/veiculos/" + id, null);
	}

	@Test
	@org.springframework.transaction.annotation.Transactional
	void serviceCatalogPreservesSearchUpdateRemovalAndReactivation() throws Exception {
		var payload = new java.util.HashMap<String, Object>(
				java.util.Map.of("nome", "Revisao de teste", "descricao", "Inspecao completa", "valor", 180,
						"categoria", "PREVENTIVA", "nivelComplexidade", "BAIXA", "garantiaDias", 30));
		String id = call("POST", "/api/v1/servicos", payload).path("id").asText();
		call("GET", "/api/v1/servicos/" + id, null);
		call("GET", "/api/v1/servicos", null);
		call("GET", "/api/v1/servicos/categoria/PREVENTIVA", null);
		payload.put("valor", 200);
		call("PUT", "/api/v1/servicos/" + id, payload);
		call("DELETE", "/api/v1/servicos/" + id, null);
		call("POST", "/api/v1/servicos/" + id + "/reativar", java.util.Map.of());
	}

	@Test
	@org.springframework.transaction.annotation.Transactional
	void partsAndInventoryPreserveQuantityAndImmutableMovementHistory() throws Exception {
		var payload = new java.util.HashMap<String, Object>(
				java.util.Map.of("sku", "TEST-OLEO", "nome", "Oleo de teste", "valorUnitario", 42.5, "estoqueMinimo", 2,
						"unidadeMedida", "L", "tipoItem", "INSUMO"));
		String part = call("POST", "/api/v1/pecas", payload).path("id").asText();
		call("GET", "/api/v1/pecas/" + part, null);
		call("GET", "/api/v1/pecas/sku/TEST-OLEO", null);
		call("GET", "/api/v1/pecas", null);
		payload.put("nome", "Oleo atualizado");
		call("PUT", "/api/v1/pecas/" + part, payload);
		String stock = call("POST", "/api/v1/pecas/estoques",
				java.util.Map.of("pecaInsumoId", part, "localizacao", "A1", "quantidade", 10))
			.path("id")
			.asText();
		call("POST", "/api/v1/estoques/movimentacoes", java.util.Map.of("estoqueId", stock, "tipo", "SAIDA",
				"quantidade", 2.5, "motivo", "Teste de movimento"));
		assertEquals(0,
				call("GET", "/api/v1/estoques/" + stock, null).path("quantidade")
					.decimalValue()
					.compareTo(new java.math.BigDecimal("7.5")));
		call("GET", "/api/v1/estoques/peca/" + part, null);
		http.perform(get("/api/v1/estoques/movimentacoes/ordem-servico/" + java.util.UUID.randomUUID()).with(admin()))
			.andExpect(status().isNotFound());
	}

	@Test
	void validationAndNotFoundReturnClientErrors() throws Exception {
		http.perform(post("/api/v1/clientes").with(admin()).contentType("application/json").content("{}"))
			.andExpect(status().isBadRequest());
		http.perform(get("/api/v1/clientes/" + java.util.UUID.randomUUID()).with(admin()))
			.andExpect(status().isNotFound());
		http.perform(get("/api/v1/servicos/" + java.util.UUID.randomUUID()).with(admin()))
			.andExpect(status().isNotFound());
	}

}
