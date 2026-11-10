package com.postech.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.postech.workshop_service.domain.entities.*;
import com.postech.workshop_service.domain.repositories.OrdemServicoRepository;
import com.postech.workshop_service.application.usecases.RegistrarHistoricoStatusOrdemServicoUseCase;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SagaTest {

	@Test
	void staleConfirmationCannotReopenAlreadyCompensatedAttempt() {
		order.projetarEstadoDistribuido(StatusOrdemServico.COMPENSANDO);
		order.projetarEstadoDistribuido(StatusOrdemServico.COMPENSADA);
		details.put("financial_state", "ESTORNADO");
		details.put("financial_version", 8L);
		details.put("financial_done", true);
		details.put("work_cancelled", true);
		saga.accept(financial("billing.pagamento-confirmado.v1"), row, order);
		assertEquals(StatusOrdemServico.COMPENSADA, order.getStatus());
		verifyNoInteractions(messages, stock);
	}

	@Test
	void genuineTechnicalCompletionDuringCancellationKeepsPhysicalAndFinancialReality() {
		details.put("financial_state", "CONFIRMADO");
		saga.cancel(id, "cancel requested");
		saga.accept(technical("execucao.reparo-concluido.v1"), row, order);
		assertEquals(StatusOrdemServico.FINALIZADA, order.getStatus());
		verifyNoInteractions(stock);
		verify(messages, never()).emit(argThat(e -> e.messageType().equals("billing.estornar-pagamento.v1")),
				anyBoolean());
	}

	final UUID id = UUID.randomUUID(), attempt = UUID.randomUUID(), sagaId = UUID.randomUUID(),
			work = UUID.randomUUID(), budget = UUID.randomUUID(), payment = UUID.randomUUID();

	final ObjectMapper json = new ObjectMapper();

	final JdbcTemplate db = mock(JdbcTemplate.class);

	final SqlMessages messages = mock(SqlMessages.class);

	final OrdemServicoRepository orders = mock(OrdemServicoRepository.class);

	final StockLedger stock = mock(StockLedger.class);

	final Map<String, Object> row = new HashMap<>(), details = new HashMap<>();

	final OrdemServico order = new OrdemServico(id, UUID.randomUUID(), UUID.randomUUID());

	final Saga saga = new Saga(db, json, messages, orders, stock,
			mock(RegistrarHistoricoStatusOrdemServicoUseCase.class));

	SagaTest() {
		row.put("os_id", id);
		row.put("attempt_id", attempt);
		row.put("saga_id", sagaId);
		row.put("work_id", work);
		row.put("budget_id", budget);
		row.put("budget_version", 1);
		row.put("version", 1L);
		row.put("work_version", 0L);
		row.put("correlation_id", id);
		details.put("os_id", id);
		details.put("payment_id", payment);
		details.put("total_centavos", 10000L);
		details.put("financial_state", "REQUESTED");
		details.put("work_cancelled", false);
		details.put("financial_done", false);
		order.projetarEstadoDistribuido(StatusOrdemServico.EM_DIAGNOSTICO);
		order.projetarEstadoDistribuido(StatusOrdemServico.EM_COMPOSICAO);
		order.projetarEstadoDistribuido(StatusOrdemServico.AGUARDANDO_ORCAMENTO);
		order.projetarEstadoDistribuido(StatusOrdemServico.AGUARDANDO_PAGAMENTO);
		when(db.queryForMap("SELECT * FROM saga_details WHERE os_id=?", id)).thenReturn(details);
		when(db.queryForMap("SELECT * FROM os_workflow WHERE os_id=? FOR UPDATE", id)).thenReturn(row);
		when(orders.buscarPorId(id)).thenReturn(Optional.of(order));
		when(db.update(anyString(), any(Object[].class))).thenAnswer(call -> {
			String sql = call.getArgument(0);
			if (sql.startsWith("UPDATE saga_details SET financial_version"))
				details.put("financial_version", call.getArgument(1));
			if (sql.contains("financial_state='CONFIRMADO'"))
				details.put("financial_state", "CONFIRMADO");
			if (sql.contains("financial_done=true"))
				details.put("financial_done", true);
			if (sql.contains("financial_done=false"))
				details.put("financial_done", false);
			if (sql.contains("work_cancelled=true"))
				details.put("work_cancelled", true);
			return 1;
		});
	}

	Envelope financial(String type) {
		Map<String, Object> body = new HashMap<>(Map.of("orcamentoId", budget, "orcamentoVersao", 1, "pagamentoId",
				payment, "providerOrderId", "ORD1", "externalReference",
				"workshop_" + payment.toString().replace("-", ""), "totalCentavos", 10000, "moeda", "BRL"));
		return event(type, "billing-service", payment, body);
	}

	Envelope technical(String type) {
		return event(type, "execucao-service", work, Map.of("trabalhoId", work));
	}

	Envelope event(String type, String producer, UUID aggregate, Map<String, Object> body) {
		return new Envelope(UUID.randomUUID(), type, 1, Instant.now(), producer, id, sagaId, attempt, id, null,
				aggregate, 4, json.valueToTree(body));
	}

	@Test
	void confirmedPaymentQueuesOnlyCurrentAuthorizedSnapshotOnce() {
		saga.accept(financial("billing.pagamento-confirmado.v1"), row, order);
		assertEquals(StatusOrdemServico.AGUARDANDO_ADMISSAO_REPARO, order.getStatus());
		saga.accept(financial("billing.pagamento-confirmado.v1"), row, order);
		verify(messages, times(1)).emit(argThat(e -> e.messageType().equals("execucao.enfileirar-reparo.v1")
				&& e.payload().path("pagamentoId").asText().equals(payment.toString())), eq(true));
	}

	@Test
	void mismatchedAmountCannotReleaseRepair() {
		var e = financial("billing.pagamento-confirmado.v1");
		((com.fasterxml.jackson.databind.node.ObjectNode) e.payload()).put("totalCentavos", 1);
		assertThrows(IllegalArgumentException.class, () -> saga.accept(e, row, order));
		verifyNoInteractions(messages, stock);
	}

	@Test
	void compensationWaitsForTechnicalThenFinancialConfirmation() {
		details.put("financial_state", "CONFIRMADO");
		saga.cancel(id, "admission failed");
		verifyNoInteractions(stock);
		verify(messages).emit(argThat(e -> e.messageType().equals("execucao.cancelar-trabalho.v1")), eq(true));
		saga.accept(technical("execucao.trabalho-cancelado.v1"), row, order);
		verify(messages).emit(argThat(e -> e.messageType().equals("billing.estornar-pagamento.v1")), eq(true));
		verifyNoInteractions(stock);
		assertEquals(StatusOrdemServico.COMPENSANDO, order.getStatus());
		saga.accept(financial("billing.pagamento-estornado.v1"), row, order);
		assertEquals(StatusOrdemServico.COMPENSADA, order.getStatus());
		verify(stock).reverse(id, attempt, 1, budget);
	}

	@Test
	void startedRepairRefusesAutomaticStockOrMoneyReversal() {
		details.put("financial_state", "CONFIRMADO");
		saga.cancel(id, "cancel");
		saga.accept(technical("execucao.cancelamento-recusado.v1"), row, order);
		assertEquals(StatusOrdemServico.RECUPERACAO_PENDENTE, order.getStatus());
		verifyNoInteractions(stock);
		verify(messages, never()).emit(argThat(e -> e.messageType().contains("estornar")), anyBoolean());
	}

	@Test
	void lateConfirmationAfterTechnicalCancellationRequestsRefundNotRepair() {
		saga.cancel(id, "timeout");
		details.put("work_cancelled", true);
		saga.accept(financial("billing.pagamento-confirmado.v1"), row, order);
		verify(messages).emit(argThat(e -> e.messageType().equals("billing.estornar-pagamento.v1")), eq(true));
		verify(messages, never()).emit(argThat(e -> e.messageType().equals("execucao.enfileirar-reparo.v1")),
				anyBoolean());
	}

	@Test
	void completedRepairBeforeStartedEventConvergesThroughValidStates() {
		details.put("financial_state", "CONFIRMADO");
		order.projetarEstadoDistribuido(StatusOrdemServico.AGUARDANDO_ADMISSAO_REPARO);
		saga.accept(technical("execucao.reparo-concluido.v1"), row, order);
		assertEquals(StatusOrdemServico.FINALIZADA, order.getStatus());
	}

}
