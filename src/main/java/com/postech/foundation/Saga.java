package com.postech.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.postech.workshop_service.domain.entities.*;
import com.postech.workshop_service.domain.repositories.OrdemServicoRepository;
import com.postech.workshop_service.application.usecases.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persistent orchestration. All effects remain in the participant that owns them. */
@Service
public class Saga {

	private final JdbcTemplate db;

	private final ObjectMapper json;

	private final SqlMessages messages;

	private final OrdemServicoRepository orders;

	private final StockLedger stock;

	private final RegistrarHistoricoStatusOrdemServicoUseCase history;

	public Saga(JdbcTemplate db, ObjectMapper json, SqlMessages messages, OrdemServicoRepository orders,
			StockLedger stock, RegistrarHistoricoStatusOrdemServicoUseCase history) {
		this.db = db;
		this.json = json;
		this.messages = messages;
		this.orders = orders;
		this.stock = stock;
		this.history = history;
	}

	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public void approved(Map<String, Object> row, OrdemServico order, UUID cause) {
		UUID payment = UUID.randomUUID();
		long total = db
			.query("SELECT snapshot FROM composition_snapshot WHERE os_id=? AND budget_version=?",
					(r, n) -> read(r.getString(1)).path("totalCentavos").asLong(), order.getId(),
					num(row, "budget_version"))
			.stream()
			.mapToLong(Long::longValue)
			.reduce(0, Math::addExact);
		if (total <= 0)
			throw new IllegalArgumentException("POSITIVE_PAYMENT_REQUIRED");
		db.update(
				"INSERT INTO saga_details(os_id,payment_id,total_centavos,financial_state) VALUES(?,?,?,'REQUESTED') ON CONFLICT(os_id) DO UPDATE SET payment_id=EXCLUDED.payment_id,total_centavos=EXCLUDED.total_centavos,financial_state='REQUESTED' WHERE saga_details.payment_id IS NULL",
				order.getId(), payment, total);
		var details = details(order.getId());
		emit(row, "billing.criar-cobranca.v1",
				Map.of("orcamentoId", row.get("budget_id"), "orcamentoVersao", num(row, "budget_version"),
						"operationId", details.get("payment_id"), "totalCentavos", details.get("total_centavos"),
						"moeda", "BRL"),
				cause);
	}

	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public boolean accept(Envelope e, Map<String, Object> row, OrdemServico order) {
		String type = e.messageType();
		boolean financial = Set
			.of("billing.pagamento-confirmado.v1", "billing.pagamento-recusado.v1", "billing.pagamento-expirado.v1",
					"billing.cobranca-cancelada.v1", "billing.cobranca-nao-criada.v1", "billing.pagamento-estornado.v1",
					"billing.compensacao-financeira-pendente.v1")
			.contains(type);
		boolean technical = Set
			.of("execucao.reparo-enfileirado.v1", "execucao.reparo-iniciado.v1", "execucao.reparo-concluido.v1",
					"execucao.enfileiramento-recusado.v1", "execucao.trabalho-cancelado.v1",
					"execucao.cancelamento-recusado.v1")
			.contains(type);
		if (!financial && !technical)
			return false;
		ensure(order.getId());
		var d = details(order.getId());
		if (financial) {
			if (e.aggregateVersion() <= ((Number) d.getOrDefault("financial_version", 0L)).longValue())
				return true;
			if (!Objects.toString(d.get("payment_id"), "").equals(e.payload().path("pagamentoId").asText()))
				return true;
			if (e.payload().has("orcamentoId")
					&& (!Objects.toString(row.get("budget_id"), "").equals(e.payload().path("orcamentoId").asText())
							|| num(row, "budget_version") != e.payload().path("orcamentoVersao").asInt()))
				return true;
			if (e.payload().has("totalCentavos")
					&& (((Number) d.get("total_centavos")).longValue() != e.payload().path("totalCentavos").asLong()
							|| !"BRL".equals(e.payload().path("moeda").asText())
							|| !("workshop_" + d.get("payment_id").toString().replace("-", ""))
								.equals(e.payload().path("externalReference").asText())))
				throw new IllegalArgumentException("PAYMENT_PROJECTION_MISMATCH");
			db.update("UPDATE saga_details SET financial_version=? WHERE os_id=?", e.aggregateVersion(), order.getId());
			audit(order.getId(), type, e.messageId(), null);
			switch (type) {
				case "billing.pagamento-confirmado.v1" -> {
					db.update("UPDATE saga_details SET financial_state='CONFIRMADO',financial_done=false WHERE os_id=?",
							order.getId());
					String stage = order.getStatus().name();
					if (Set.of("COMPENSANDO", "COMPENSADA").contains(stage)) {
						if (stage.equals("COMPENSADA"))
							transition(row, order, StatusOrdemServico.COMPENSANDO);
						if (Boolean.TRUE.equals(d.get("work_cancelled")))
							financialCompensation(row, details(order.getId()), e.messageId());
					}
					else if (stage.equals("AGUARDANDO_PAGAMENTO")) {
						transition(row, order, StatusOrdemServico.AGUARDANDO_ADMISSAO_REPARO);
						var items = db.query(
								"SELECT snapshot FROM composition_snapshot WHERE os_id=? AND budget_version=?",
								(r, n) -> {
									var item = read(r.getString(1));
									return Map.of("itemId", item.path("itemId").asText(), "tipo",
											item.path("tipo").asText(), "descricao", item.path("descricao").asText());
								}, order.getId(), num(row, "budget_version"));
						emit(row, "execucao.enfileirar-reparo.v1",
								Map.of("trabalhoId", row.get("work_id"), "orcamentoId", row.get("budget_id"),
										"orcamentoVersao", num(row, "budget_version"), "pagamentoId",
										d.get("payment_id"), "itensAutorizados", items),
								e.messageId());
					}
				}
				case "billing.pagamento-recusado.v1", "billing.pagamento-expirado.v1" -> {
					db.update("UPDATE saga_details SET financial_state=?,financial_done=true WHERE os_id=?",
							type.contains("recusado") ? "RECUSADO" : "EXPIRADO", order.getId());
					compensate(row, order, type, e.messageId());
				}
				case "billing.cobranca-cancelada.v1", "billing.cobranca-nao-criada.v1",
						"billing.pagamento-estornado.v1" -> {
					db.update("UPDATE saga_details SET financial_state=?,financial_done=true WHERE os_id=?",
							type.contains("estornado") ? "ESTORNADO" : "CANCELADO", order.getId());
					finish(row, order);
				}
				case "billing.compensacao-financeira-pendente.v1" -> {
					transition(row, order, StatusOrdemServico.RECUPERACAO_PENDENTE);
					clearRetry(order.getId());
				}
				default -> throw new IllegalArgumentException("UNSUPPORTED_FINANCIAL_EVENT");
			}
			return true;
		}
		if (!e.payload().path("trabalhoId").asText().equals(row.get("work_id").toString()))
			throw new IllegalArgumentException("WORK_CONTEXT_MISMATCH");
		audit(order.getId(), type, e.messageId(), null);
		if (type.equals("execucao.cancelamento-recusado.v1")) {
			if (Set.of(StatusOrdemServico.FINALIZADA, StatusOrdemServico.ENTREGUE).contains(order.getStatus()))
				return true;
			db.update("UPDATE saga_details SET compensation_reason='REPARO_JA_INICIADO' WHERE os_id=?", order.getId());
			transition(row, order, StatusOrdemServico.RECUPERACAO_PENDENTE);
			clearRetry(order.getId());
			return true;
		}
		if (type.equals("execucao.trabalho-cancelado.v1")) {
			db.update("UPDATE saga_details SET work_cancelled=true WHERE os_id=?", order.getId());
			if (Set.of(StatusOrdemServico.COMPENSANDO, StatusOrdemServico.RECUPERACAO_PENDENTE)
				.contains(order.getStatus())) {
				if (order.getStatus() == StatusOrdemServico.RECUPERACAO_PENDENTE)
					transition(row, order, StatusOrdemServico.COMPENSANDO);
				financialCompensation(row, details(order.getId()), e.messageId());
				finish(row, order);
			}
			else
				clearRetry(order.getId());
			return true;
		}
		if (type.equals("execucao.enfileiramento-recusado.v1")) {
			compensate(row, order, type, e.messageId());
			return true;
		}
		if (Set.of("execucao.reparo-iniciado.v1", "execucao.reparo-concluido.v1").contains(type)
				&& Set.of(StatusOrdemServico.COMPENSANDO, StatusOrdemServico.RECUPERACAO_PENDENTE)
					.contains(order.getStatus())
				&& "CONFIRMADO".equals(d.get("financial_state")) && !Boolean.TRUE.equals(d.get("work_cancelled"))) {
			transition(row, order, StatusOrdemServico.RECUPERACAO_PENDENTE);
			transition(row, order, StatusOrdemServico.EM_EXECUCAO);
		}
		if (!"CONFIRMADO".equals(d.get("financial_state")) || !Set
			.of(StatusOrdemServico.AGUARDANDO_ADMISSAO_REPARO, StatusOrdemServico.AGUARDANDO_EXECUCAO,
					StatusOrdemServico.EM_EXECUCAO)
			.contains(order.getStatus()))
			return true;
		if (e.aggregateVersion() <= ((Number) row.get("work_version")).longValue())
			return true;
		if (order.getStatus() == StatusOrdemServico.AGUARDANDO_ADMISSAO_REPARO)
			transition(row, order, StatusOrdemServico.AGUARDANDO_EXECUCAO);
		if (type.equals("execucao.reparo-iniciado.v1") || type.equals("execucao.reparo-concluido.v1")) {
			if (order.getStatus() == StatusOrdemServico.AGUARDANDO_EXECUCAO)
				transition(row, order, StatusOrdemServico.EM_EXECUCAO);
			if (type.equals("execucao.reparo-concluido.v1"))
				transition(row, order, StatusOrdemServico.FINALIZADA);
		}
		db.update("UPDATE os_workflow SET work_version=? WHERE os_id=?", e.aggregateVersion(), order.getId());
		clearRetry(order.getId());
		return true;
	}

	@Transactional
	public void cancel(UUID id, String reason) {
		var row = workflow(id);
		var order = orders.buscarPorId(id).orElseThrow();
		AtorSistemaContext.executarComo("saga.cancelamento", () -> {
			compensate(row, order, reason, null);
			return null;
		});
	}

	@Transactional
	public void resume(UUID id) {
		var row = workflow(id);
		var order = orders.buscarPorId(id).orElseThrow();
		if (order.getStatus() != StatusOrdemServico.RECUPERACAO_PENDENTE)
			throw new IllegalArgumentException("SAGA_NOT_PENDING_RECOVERY");
		audit(id, "MANUAL_RESUME", null, "Retomada autorizada de compensacao");
		transition(row, order, StatusOrdemServico.COMPENSANDO);
		var d = details(id);
		if (Boolean.TRUE.equals(d.get("work_cancelled"))) {
			financialCompensation(row, d, null);
			finish(row, order);
		}
		else
			emit(row, "execucao.cancelar-trabalho.v1", Map.of("trabalhoId", row.get("work_id")), null);
	}

	private void compensate(Map<String, Object> row, OrdemServico order, String reason, UUID cause) {
		if (Set
			.of(StatusOrdemServico.FINALIZADA, StatusOrdemServico.ENTREGUE, StatusOrdemServico.COMPENSADA,
					StatusOrdemServico.CANCELADA)
			.contains(order.getStatus()))
			return;
		ensure(order.getId());
		db.update("UPDATE saga_details SET compensation_reason=? WHERE os_id=?", reason, order.getId());
		if (order.getStatus() == StatusOrdemServico.EM_EXECUCAO) {
			transition(row, order, StatusOrdemServico.RECUPERACAO_PENDENTE);
			clearRetry(order.getId());
			return;
		}
		transition(row, order, StatusOrdemServico.COMPENSANDO);
		emit(row, "execucao.cancelar-trabalho.v1", Map.of("trabalhoId", row.get("work_id")), cause);
	}

	private void financialCompensation(Map<String, Object> row, Map<String, Object> d, UUID cause) {
		if (Boolean.TRUE.equals(d.get("financial_done")))
			return;
		if (d.get("payment_id") == null
				|| Set.of("NONE", "RECUSADO", "EXPIRADO").contains((String) d.get("financial_state"))) {
			db.update("UPDATE saga_details SET financial_done=true WHERE os_id=?", row.get("os_id"));
			return;
		}
		boolean refund = "CONFIRMADO".equals(d.get("financial_state"));
		UUID operation = UUID.nameUUIDFromBytes((d.get("payment_id") + (refund ? ":refund" : ":cancel"))
			.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		emit(row, refund ? "billing.estornar-pagamento.v1" : "billing.cancelar-cobranca.v1",
				Map.of("operationId", operation, "pagamentoId", d.get("payment_id")), cause);
	}

	private void finish(Map<String, Object> row, OrdemServico order) {
		var d = details(order.getId());
		if (!Boolean.TRUE.equals(d.get("work_cancelled")) || !Boolean.TRUE.equals(d.get("financial_done"))
				|| !Set.of(StatusOrdemServico.COMPENSANDO, StatusOrdemServico.RECUPERACAO_PENDENTE)
					.contains(order.getStatus()))
			return;
		stock.reverse(order.getId(), (UUID) row.get("attempt_id"), num(row, "budget_version"),
				(UUID) row.get("budget_id"));
		db.update("UPDATE saga_details SET stock_reversed=true WHERE os_id=?", order.getId());
		transition(row, order, StatusOrdemServico.COMPENSADA);
		clearRetry(order.getId());
	}

	@Transactional
	public void retryOne() {
		var ids = db.queryForList(
				"SELECT os_id FROM os_workflow WHERE next_retry_at<=now() AND last_command_type IS NOT NULL ORDER BY next_retry_at FOR UPDATE SKIP LOCKED LIMIT 1",
				UUID.class);
		if (ids.isEmpty())
			return;
		var row = workflow(ids.getFirst());
		var order = orders.buscarPorId(ids.getFirst()).orElseThrow();
		AtorSistemaContext.executarComo("saga.prazo", () -> {
			if (Set
				.of(StatusOrdemServico.CANCELADA, StatusOrdemServico.COMPENSADA, StatusOrdemServico.ENTREGUE,
						StatusOrdemServico.FINALIZADA, StatusOrdemServico.RECUPERACAO_PENDENTE)
				.contains(order.getStatus())) {
				clearRetry(order.getId());
				return null;
			}
			boolean expired = row.get("deadline") != null
					&& ((java.sql.Timestamp) row.get("deadline")).toInstant().isBefore(Instant.now());
			if (expired || num(row, "retry_count") >= 20) {
				if (order.getStatus() == StatusOrdemServico.COMPENSANDO) {
					transition(row, order, StatusOrdemServico.RECUPERACAO_PENDENTE);
					clearRetry(order.getId());
				}
				else
					compensate(row, order, "PARTICIPANT_DEADLINE", null);
			}
			else {
				Envelope event = envelope(row, (String) row.get("last_command_type"),
						read((String) row.get("last_command_payload")), null);
				messages.emit(event, true);
				db.update(
						"UPDATE os_workflow SET retry_count=retry_count+1,next_retry_at=now()+interval '1 minute' WHERE os_id=?",
						order.getId());
				audit(order.getId(), "RETRY", event.messageId(), event.messageType());
			}
			return null;
		});
	}

	private void transition(Map<String, Object> row, OrdemServico order, StatusOrdemServico next) {
		if (order.getStatus() == next)
			return;
		var previous = order.getStatus();
		order.projetarEstadoDistribuido(next);
		orders.salvar(order);
		history.executar(order.getId(), previous, next);
		db.update("UPDATE os_workflow SET stage=?,version=version+1,updated_at=now() WHERE os_id=?", next.name(),
				order.getId());
		row.put("stage", next.name());
		audit(order.getId(), "TRANSITION", null, previous + "->" + next);
	}

	private void emit(Map<String, Object> row, String type, Object payload, UUID cause) {
		var data = json.valueToTree(payload);
		messages.emit(envelope(row, type, data, cause), true);
		db.update(
				"UPDATE os_workflow SET last_command_type=?,last_command_payload=?,retry_count=0,next_retry_at=now()+interval '1 minute',deadline=now()+interval '20 minutes' WHERE os_id=?",
				type, data.toString(), row.get("os_id"));
	}

	private Envelope envelope(Map<String, Object> row, String type, com.fasterxml.jackson.databind.JsonNode data,
			UUID cause) {
		return new Envelope(UUID.randomUUID(), type, 1, Instant.now(), "os-service", (UUID) row.get("os_id"),
				(UUID) row.get("saga_id"), (UUID) row.get("attempt_id"), (UUID) row.get("correlation_id"), cause,
				(UUID) row.get("os_id"), ((Number) row.get("version")).longValue(), data);
	}

	private void clearRetry(UUID id) {
		db.update(
				"UPDATE os_workflow SET next_retry_at=NULL,last_command_type=NULL,last_command_payload=NULL,deadline=NULL WHERE os_id=?",
				id);
	}

	private void ensure(UUID id) {
		db.update("INSERT INTO saga_details(os_id) VALUES(?) ON CONFLICT DO NOTHING", id);
	}

	private Map<String, Object> details(UUID id) {
		return db.queryForMap("SELECT * FROM saga_details WHERE os_id=?", id);
	}

	private Map<String, Object> workflow(UUID id) {
		return db.queryForMap("SELECT * FROM os_workflow WHERE os_id=? FOR UPDATE", id);
	}

	private void audit(UUID id, String type, UUID message, String detail) {
		db.update("INSERT INTO saga_audit(id,os_id,event_type,message_id,detail) VALUES(?,?,?,?,?)", UUID.randomUUID(),
				id, type, message, detail);
	}

	private com.fasterxml.jackson.databind.JsonNode read(String text) {
		try {
			return json.readTree(text);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	private static int num(Map<String, Object> row, String key) {
		return ((Number) row.get(key)).intValue();
	}

}
