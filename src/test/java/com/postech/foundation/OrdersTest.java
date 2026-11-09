package com.postech.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.postech.workshop_service.application.usecases.*;
import com.postech.workshop_service.domain.entities.*;
import com.postech.workshop_service.domain.repositories.OrdemServicoRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrdersTest {

	final UUID id = UUID.randomUUID(), attempt = UUID.randomUUID(), saga = UUID.randomUUID(), work = UUID.randomUUID(),
			budget = UUID.randomUUID();

	final JdbcTemplate db = mock(JdbcTemplate.class);

	final StockLedger stock = mock(StockLedger.class);

	final SqlMessages messages = mock(SqlMessages.class);

	final RegistrarHistoricoStatusOrdemServicoUseCase history = mock(RegistrarHistoricoStatusOrdemServicoUseCase.class);

	final OrdemServicoRepository repository = mock(OrdemServicoRepository.class);

	final OrdemServico order = new OrdemServico(id, UUID.randomUUID(), UUID.randomUUID());

	final Map<String, Object> row = new HashMap<>();

	final ObjectMapper json = new ObjectMapper();

	final Orders service;

	final CriarOrdemServicoUseCase creator = mock(CriarOrdemServicoUseCase.class);

	final AdicionarItemOrdemServicoUseCase additions = mock(AdicionarItemOrdemServicoUseCase.class);

	OrdersTest() {
		row.put("os_id", id);
		row.put("attempt_id", attempt);
		row.put("saga_id", saga);
		row.put("work_id", work);
		row.put("correlation_id", id);
		row.put("budget_id", budget);
		row.put("budget_version", 1);
		row.put("work_version", 0L);
		row.put("version", 1L);
		row.put("stage", "RECEBIDO");
		when(db.queryForList(startsWith("SELECT * FROM os_workflow"), eq(id))).thenReturn(List.of(row));
		when(repository.buscarPorId(id)).thenReturn(Optional.of(order));
		when(repository.salvar(any())).thenAnswer(i -> i.getArgument(0));
		when(db.update(startsWith("INSERT INTO inbox"), any(UUID.class))).thenReturn(1);
		service = new Orders(db, json, messages, creator, additions, repository, history, stock,
				mock(EntityManager.class));
	}

	Envelope event(String type, UUID reference, int revision, long aggregateVersion, UUID eventAttempt) {
		boolean execution = type.startsWith("execucao.");
		Map<String, Object> payload = new HashMap<>();
		if (execution) {
			payload.put("trabalhoId", reference);
			if (type.contains("concluido"))
				payload.put("diagnostico", "Trocar oleo");
		}
		else {
			payload.put("orcamentoId", reference);
			payload.put("orcamentoVersao", revision);
		}
		return new Envelope(UUID.randomUUID(), type, 1, Instant.now(),
				execution ? "execucao-service" : "billing-service", id, saga, eventAttempt, id, null, reference,
				aggregateVersion, json.valueToTree(payload));
	}

	void awaitingBudget() {
		order.projetarEstadoDistribuido(StatusOrdemServico.EM_DIAGNOSTICO);
		order.projetarEstadoDistribuido(StatusOrdemServico.EM_COMPOSICAO);
		order.projetarEstadoDistribuido(StatusOrdemServico.AGUARDANDO_ORCAMENTO);
		row.put("stage", "AGUARDANDO_ORCAMENTO");
	}

	void composition() {
		order.iniciarDiagnostico();
		order.encerrarDiagnostico();
		row.put("stage", "EM_COMPOSICAO");
		row.put("budget_id", null);
	}

	@Test
	void validOpeningPersistsWorkflowAndDiagnosticCommand() {
		var request = new DadosCriacaoOrdemServico("12345678909", "ABC1D23", null, null, null, "Revisao");
		when(creator.executar(request)).thenReturn(new ResultadoCriacaoOrdemServico(order, null, null, null));
		assertEquals(id, service.create(UUID.randomUUID(), request).get("id"));
		verify(messages).emit(argThat(e -> {
			e.validate("execution");
			return e.messageType().equals("execucao.solicitar-diagnostico.v1");
		}), eq(true));
	}

	@Test
	void repeatedOpeningKeepsBusinessIdentityAndConflictingBodyFails() throws Exception {
		UUID key = UUID.randomUUID();
		var request = new DadosCriacaoOrdemServico("12345678909", "ABC1D23", null, null, null, "Revisao");
		when(db.queryForList(startsWith("SELECT os_id,request_body"), eq(key)))
			.thenReturn(List.of(Map.of("os_id", id, "request_body", json.writeValueAsString(request))));
		assertEquals(id, service.create(key, request).get("id"));
		verifyNoInteractions(creator, messages);
		assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.create(key,
				new DadosCriacaoOrdemServico("12345678909", "ABC1D23", null, null, null, "Outro relato")));
	}

	@Test
	@SuppressWarnings("unchecked")
	void compositionProducesImmutableBudgetCommandAndDoesNotRegenerate() {
		composition();
		var item = new ItemComposicaoTecnica("Revisao", new java.math.BigDecimal("100"),
				TipoItemComposicaoTecnica.SERVICO);
		order.adicionarItem(item);
		var snap = new BudgetSnapshot.Item(item.getId(), "SERVICO", "Revisao", "1", 10000, 10000);
		when(db.query(startsWith("SELECT snapshot"), any(org.springframework.jdbc.core.RowMapper.class), eq(id), eq(1)))
			.thenReturn(List.of(snap));
		service.closeComposition(id);
		assertEquals(StatusOrdemServico.AGUARDANDO_ORCAMENTO, order.getStatus());
		verify(messages).emit(argThat(e -> {
			e.validate("billing");
			return e.payload().path("totalCentavos").asLong() == 10000;
		}), eq(true));
		row.put("budget_id", budget);
		service.closeComposition(id);
		verify(messages, times(1)).emit(any(), eq(true));
	}

	@Test
	void addingServicePreservesDecimalSnapshotAndRepeatedKeyDoesNotAddAgain() throws Exception {
		composition();
		UUID catalog = UUID.randomUUID(), key = UUID.randomUUID();
		var quantity = new java.math.BigDecimal("1.5");
		var request = new Orders.ItemRequest(catalog, "SERVICO", quantity);
		order.adicionarItem(new ItemComposicaoTecnica("Servico", new java.math.BigDecimal("15"),
				TipoItemComposicaoTecnica.SERVICO));
		when(additions.executarServico(id, catalog, quantity)).thenReturn(order);
		service.addItem(id, key, request);
		verify(stock).track(id, attempt, 1);
		when(db.queryForList(startsWith("SELECT request_body FROM os_item_requests"), eq(id), eq(key)))
			.thenReturn(List.of(Map.of("request_body", json.writeValueAsString(request))));
		service.addItem(id, key, request);
		verify(additions, times(1)).executarServico(id, catalog, quantity);
	}

	@Test
	void addingConsumableUsesReservationPathAndRejectsBadQuantity() {
		composition();
		UUID catalog = UUID.randomUUID();
		var quantity = new java.math.BigDecimal("2.5");
		order.adicionarItem(new ItemComposicaoTecnica("Oleo", new java.math.BigDecimal("106.25"),
				TipoItemComposicaoTecnica.INSUMO, catalog));
		when(additions.executarPeca(id, catalog, quantity)).thenReturn(order);
		service.addItem(id, UUID.randomUUID(), new Orders.ItemRequest(catalog, "INSUMO", quantity));
		verify(stock).track(id, attempt, 1);
		assertThrows(IllegalArgumentException.class, () -> service.addItem(id, UUID.randomUUID(),
				new Orders.ItemRequest(catalog, "INSUMO", java.math.BigDecimal.ZERO)));
	}

	@Test
	void deliveryRequiresTechnicalCompletionAndRecordsOneTransition() {
		awaitingBudget();
		order.projetarEstadoDistribuido(StatusOrdemServico.AGUARDANDO_PAGAMENTO);
		order.projetarEstadoDistribuido(StatusOrdemServico.AGUARDANDO_EXECUCAO);
		order.projetarEstadoDistribuido(StatusOrdemServico.EM_EXECUCAO);
		order.projetarEstadoDistribuido(StatusOrdemServico.FINALIZADA);
		row.put("stage", "FINALIZADA");
		service.deliver(id);
		assertEquals(StatusOrdemServico.ENTREGUE, order.getStatus());
		verify(history).executar(id, StatusOrdemServico.FINALIZADA, StatusOrdemServico.ENTREGUE);
		row.put("stage", "ENTREGUE");
		service.deliver(id);
		verifyNoMoreInteractions(history);
	}

	@Test
	void completedDiagnosticBeforeStartedEventStillProjectsCorrectly() {
		service.accept(event("execucao.diagnostico-concluido.v1", work, 1, 3, attempt));
		assertEquals(StatusOrdemServico.EM_COMPOSICAO, order.getStatus());
		service.accept(event("execucao.diagnostico-iniciado.v1", work, 1, 2, attempt));
		assertEquals(StatusOrdemServico.EM_COMPOSICAO, order.getStatus());
		verify(history, times(2)).executar(eq(id), any(), any());
	}

	@Test
	void approvalBeforeGeneratedConsumesOnceAndWaitsForPayment() {
		awaitingBudget();
		service.accept(event("billing.orcamento-aprovado.v1", budget, 1, 2, attempt));
		service.accept(event("billing.orcamento-aprovado.v1", budget, 1, 2, attempt));
		service.accept(event("billing.orcamento-gerado.v1", budget, 1, 1, attempt));
		assertEquals(StatusOrdemServico.AGUARDANDO_PAGAMENTO, order.getStatus());
		verify(stock, times(1)).settle(id, attempt, 1, budget, true);
		verifyNoInteractions(messages);
	}

	@Test
	void staleBudgetAndDifferentAttemptHaveNoEffects() {
		awaitingBudget();
		service.accept(event("billing.orcamento-aprovado.v1", UUID.randomUUID(), 1, 2, attempt));
		service.accept(event("billing.orcamento-aprovado.v1", budget, 2, 2, attempt));
		service.accept(event("billing.orcamento-aprovado.v1", budget, 1, 2, UUID.randomUUID()));
		verifyNoInteractions(stock, history, messages);
	}

	@Test
	void rejectionReleasesOnlyCurrentReservationsAndAdvancesRevision() {
		awaitingBudget();
		service.accept(event("billing.orcamento-rejeitado.v1", budget, 1, 2, attempt));
		assertEquals(StatusOrdemServico.EM_COMPOSICAO, order.getStatus());
		verify(stock).settle(id, attempt, 1, budget, false);
		verify(db).update(
				"UPDATE os_workflow SET budget_id=NULL,budget_version=budget_version+1,next_retry_at=NULL,last_command_type=NULL WHERE os_id=?",
				id);
	}

	@Test
	void duplicateMessageDoesNotTouchDomain() {
		when(db.update(startsWith("INSERT INTO inbox"), any(UUID.class))).thenReturn(0);
		service.accept(event("execucao.diagnostico-iniciado.v1", work, 1, 2, attempt));
		verifyNoInteractions(repository, stock, history);
	}

	@Test
	void emptyCompositionCannotPublishBudget() {
		row.put("budget_id", null);
		row.put("stage", "EM_COMPOSICAO");
		assertThrows(IllegalArgumentException.class, () -> service.closeComposition(id));
		verifyNoInteractions(messages);
	}

}
