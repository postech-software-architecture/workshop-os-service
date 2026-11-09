package com.postech.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.postech.workshop_service.application.usecases.*;
import com.postech.workshop_service.domain.entities.*;
import com.postech.workshop_service.domain.repositories.OrdemServicoRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Orders implements Handler {

	private org.springframework.beans.factory.ObjectProvider<Saga> saga;

	@org.springframework.beans.factory.annotation.Autowired
	void saga(org.springframework.beans.factory.ObjectProvider<Saga> saga) {
		this.saga = saga;
	}

	private final JdbcTemplate db;

	private final ObjectMapper json;

	private final SqlMessages messages;

	private final CriarOrdemServicoUseCase create;

	private final AdicionarItemOrdemServicoUseCase add;

	private final OrdemServicoRepository orders;

	private final RegistrarHistoricoStatusOrdemServicoUseCase history;

	private final StockLedger stock;

	private final EntityManager entities;

	public Orders(JdbcTemplate db, ObjectMapper json, SqlMessages messages, CriarOrdemServicoUseCase create,
			AdicionarItemOrdemServicoUseCase add, OrdemServicoRepository orders,
			RegistrarHistoricoStatusOrdemServicoUseCase history, StockLedger stock, EntityManager entities) {
		this.db = db;
		this.json = json;
		this.messages = messages;
		this.create = create;
		this.add = add;
		this.orders = orders;
		this.history = history;
		this.stock = stock;
		this.entities = entities;
	}

	public record ItemRequest(UUID catalogoId, String tipo, BigDecimal quantidade) {
	}

	@Transactional
	public Map<String, Object> create(UUID key, DadosCriacaoOrdemServico request) {
		db.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?)) IS NULL", Boolean.class, key.toString());
		String body = encode(request);
		var existing = db.queryForList("SELECT os_id,request_body FROM os_workflow WHERE request_key=?", key);
		if (!existing.isEmpty()) {
			if (!body.equals(existing.getFirst().get("request_body")))
				throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
			return view((UUID) existing.getFirst().get("os_id"));
		}
		var order = create.executar(request).ordemServico();
		entities.flush();
		UUID attempt = UUID.randomUUID(), saga = UUID.randomUUID(), work = UUID.randomUUID(),
				correlation = UUID.randomUUID();
		db.update(
				"INSERT INTO os_workflow(os_id,attempt_id,saga_id,work_id,correlation_id,request_key,request_body,stage) VALUES(?,?,?,?,?,?,?,'RECEBIDO')",
				order.getId(), attempt, saga, work, correlation, key, body);
		var row = workflow(order.getId(), false);
		String description = order.getObservacoes() == null ? "Diagnostico da OS " + order.getNumero()
				: order.getObservacoes();
		if (description.length() > 500)
			throw new IllegalArgumentException("REQUEST_TOO_LONG");
		emit(row, "execucao.solicitar-diagnostico.v1", Map.of("trabalhoId", work, "solicitacao", description), null);
		return view(order.getId());
	}

	@Transactional(readOnly = true)
	public Map<String, Object> view(UUID id) {
		var row = workflow(id, false);
		var order = order(id);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id);
		result.put("numero", order.getNumero());
		result.put("clienteId", order.getIdCliente());
		result.put("veiculoId", order.getIdVeiculo());
		result.put("status", row.get("stage"));
		result.put("trabalhoId", row.get("work_id"));
		result.put("orcamentoId", row.get("budget_id"));
		result.put("orcamentoVersao", row.get("budget_version"));
		result.put("attemptId", row.get("attempt_id"));
		result.put("diagnostico", row.get("diagnostic"));
		result.put("itens",
				db.query(
						"SELECT snapshot FROM composition_snapshot WHERE os_id=? AND budget_version=? ORDER BY item_id",
						(r, n) -> decodeItem(r.getString(1)), id, number(row, "budget_version")));
		result.put("versao", row.get("version"));
		result.put("financeiro", db
			.queryForList("SELECT payment_id,financial_state,compensation_reason FROM saga_details WHERE os_id=?", id));
		return result;
	}

	@Transactional
	public Map<String, Object> addItem(UUID id, UUID key, ItemRequest request) {
		var row = workflow(id, true);
		String body = encode(request);
		var old = db.queryForList("SELECT request_body FROM os_item_requests WHERE os_id=? AND request_key=?", id, key);
		if (!old.isEmpty()) {
			if (!body.equals(old.getFirst().get("request_body")))
				throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
			return view(id);
		}
		requireStage(row, "EM_COMPOSICAO");
		BigDecimal quantity = request.quantidade();
		if (request.catalogoId() == null || quantity == null || quantity.signum() <= 0 || quantity.scale() > 3
				|| quantity.compareTo(new BigDecimal("9999999.999")) > 0)
			throw new IllegalArgumentException("INVALID_QUANTITY");
		OrdemServico order;
		if ("SERVICO".equals(request.tipo()))
			order = add.executarServico(id, request.catalogoId(), quantity);
		else if ("PECA".equals(request.tipo()) || "INSUMO".equals(request.tipo()))
			order = add.executarPeca(id, request.catalogoId(), quantity);
		else
			throw new IllegalArgumentException("INVALID_ITEM_TYPE");
		var item = order.getItensComposicao().getLast();
		if (!item.getTipo().name().equals(request.tipo()))
			throw new IllegalArgumentException("CATALOG_TYPE_MISMATCH");
		long unit = item.getValor().divide(quantity).movePointRight(2).longValueExact();
		long total = item.getValor().movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
		var snapshot = new BudgetSnapshot.Item(item.getId(), item.getTipo().name(), item.getDescricao(),
				quantity.toPlainString(), unit, total);
		db.update("INSERT INTO composition_snapshot(item_id,os_id,budget_version,snapshot) VALUES(?,?,?,?)",
				item.getId(), id, number(row, "budget_version"), encode(snapshot));
		db.update("INSERT INTO os_item_requests(os_id,request_key,request_body,item_id) VALUES(?,?,?,?)", id, key, body,
				item.getId());
		stock.track(id, (UUID) row.get("attempt_id"), number(row, "budget_version"));
		return view(id);
	}

	@Transactional
	public Map<String, Object> closeComposition(UUID id) {
		var row = workflow(id, true);
		if (row.get("budget_id") != null)
			return view(id);
		requireStage(row, "EM_COMPOSICAO");
		var order = order(id);
		if (!order.possuiItensComposicao())
			throw new IllegalArgumentException("EMPTY_COMPOSITION");
		var items = db.query(
				"SELECT snapshot FROM composition_snapshot WHERE os_id=? AND budget_version=? ORDER BY item_id",
				(r, n) -> decodeItem(r.getString(1)), id, number(row, "budget_version"));
		UUID budget = UUID.randomUUID();
		long total = items.stream().mapToLong(BudgetSnapshot.Item::totalCentavos).reduce(0, Math::addExact);
		var snapshot = new BudgetSnapshot(budget, number(row, "budget_version"), order.getIdCliente(), items, total,
				"BRL");
		db.update("UPDATE os_workflow SET budget_id=? WHERE os_id=?", budget, id);
		transition(row, order, StatusOrdemServico.AGUARDANDO_ORCAMENTO);
		emit(workflow(id, false), "billing.gerar-orcamento.v1", snapshot, null);
		return view(id);
	}

	@Transactional
	public Map<String, Object> deliver(UUID id) {
		var row = workflow(id, true);
		if ("ENTREGUE".equals(row.get("stage")))
			return view(id);
		requireStage(row, "FINALIZADA");
		var order = order(id);
		order.entregar();
		orders.salvar(order);
		history.executar(id, StatusOrdemServico.FINALIZADA, StatusOrdemServico.ENTREGUE);
		db.update("UPDATE os_workflow SET stage='ENTREGUE',version=version+1,updated_at=now() WHERE os_id=?", id);
		return view(id);
	}

	@Transactional
	public void accept(Envelope e) {
		e.validate("os");
		if (db.update("INSERT INTO inbox(message_id) VALUES(?) ON CONFLICT DO NOTHING", e.messageId()) == 0)
			return;
		var row = workflow(e.osId(), true);
		if (!e.attemptId().equals(row.get("attempt_id")))
			return;
		if (!e.sagaId().equals(row.get("saga_id")))
			throw new IllegalArgumentException("SAGA_MISMATCH");
		var order = order(e.osId());
		AtorSistemaContext.executarComo(e.producer(), () -> {
			process(e, row, order);
			return null;
		});
	}

	private void process(Envelope e, Map<String, Object> row, OrdemServico order) {
		if (saga != null && saga.getObject().accept(e, row, order))
			return;
		String type = e.messageType(), stage = (String) row.get("stage");
		if (type.startsWith("execucao.")) {
			if (!e.payload().path("trabalhoId").asText().equals(row.get("work_id").toString()))
				throw new IllegalArgumentException("WORK_MISMATCH");
			if (e.aggregateVersion() <= ((Number) row.get("work_version")).longValue())
				return;
			if (type.equals("execucao.diagnostico-iniciado.v1") && stage.equals("RECEBIDO"))
				transition(row, order, StatusOrdemServico.EM_DIAGNOSTICO);
			if (type.equals("execucao.diagnostico-concluido.v1")
					&& Set.of("RECEBIDO", "EM_DIAGNOSTICO").contains(stage)) {
				if (stage.equals("RECEBIDO"))
					transition(row, order, StatusOrdemServico.EM_DIAGNOSTICO);
				transition(row, order, StatusOrdemServico.EM_COMPOSICAO);
				db.update("UPDATE os_workflow SET next_retry_at=NULL,last_command_type=NULL WHERE os_id=?",
						order.getId());
				db.update("UPDATE os_workflow SET diagnostic=? WHERE os_id=?", e.payload().path("diagnostico").asText(),
						e.osId());
			}
			// Financial authorization and repair admission belong to phase 4; no approval
			// shortcut.
			if (type.equals("execucao.reparo-iniciado.v1") && stage.equals("AGUARDANDO_EXECUCAO"))
				transition(row, order, StatusOrdemServico.EM_EXECUCAO);
			if (type.equals("execucao.reparo-concluido.v1")
					&& Set.of("AGUARDANDO_EXECUCAO", "EM_EXECUCAO").contains(stage)) {
				if (stage.equals("AGUARDANDO_EXECUCAO"))
					transition(row, order, StatusOrdemServico.EM_EXECUCAO);
				transition(row, order, StatusOrdemServico.FINALIZADA);
			}
			db.update("UPDATE os_workflow SET work_version=? WHERE os_id=?", e.aggregateVersion(), e.osId());
			return;
		}
		if (!e.payload().path("orcamentoId").asText().equals(String.valueOf(row.get("budget_id")))
				|| e.payload().path("orcamentoVersao").asInt() != number(row, "budget_version"))
			return;
		if (!Set.of("AGUARDANDO_ORCAMENTO", "AGUARDANDO_APROVACAO").contains(stage))
			return;
		switch (type) {
			case "billing.orcamento-gerado.v1" -> {
				if (stage.equals("AGUARDANDO_ORCAMENTO"))
					transition(row, order, StatusOrdemServico.AGUARDANDO_APROVACAO);
				db.update("UPDATE os_workflow SET next_retry_at=NULL,last_command_type=NULL WHERE os_id=?",
						order.getId());
			}
			case "billing.orcamento-aprovado.v1" -> {
				stock.settle(e.osId(), e.attemptId(), number(row, "budget_version"), (UUID) row.get("budget_id"), true);
				transition(row, order, StatusOrdemServico.AGUARDANDO_PAGAMENTO);
				if (saga != null)
					saga.getObject().approved(row, order, e.messageId());
			}
			case "billing.orcamento-rejeitado.v1", "billing.orcamento-cancelado.v1" -> {
				stock.settle(e.osId(), e.attemptId(), number(row, "budget_version"), (UUID) row.get("budget_id"),
						false);
				if (type.equals("billing.orcamento-rejeitado.v1")) {
					order.limparComposicaoRejeitada();
					transition(row, order, StatusOrdemServico.EM_COMPOSICAO);
					db.update(
							"UPDATE os_workflow SET budget_id=NULL,budget_version=budget_version+1,next_retry_at=NULL,last_command_type=NULL WHERE os_id=?",
							e.osId());
				}
				else {
					transition(row, order, StatusOrdemServico.CANCELADA);
					emit(row, "execucao.cancelar-trabalho.v1", Map.of("trabalhoId", row.get("work_id")), e.messageId());
				}
			}
			default -> throw new IllegalArgumentException("UNSUPPORTED_EVENT");
		}
	}

	private void transition(Map<String, Object> row, OrdemServico order, StatusOrdemServico next) {
		StatusOrdemServico previous = order.getStatus();
		if (previous == next)
			return;
		order.projetarEstadoDistribuido(next);
		orders.salvar(order);
		history.executar(order.getId(), previous, next);
		db.update("UPDATE os_workflow SET stage=?,version=version+1,updated_at=now() WHERE os_id=?", next.name(),
				order.getId());
		row.put("stage", next.name());
	}

	private void emit(Map<String, Object> row, String type, Object payload, UUID cause) {
		db.update(
				"UPDATE os_workflow SET last_command_type=?,last_command_payload=?,retry_count=0,next_retry_at=now()+interval '1 minute',deadline=now()+interval '20 minutes' WHERE os_id=?",
				type, json.valueToTree(payload).toString(), row.get("os_id"));
		messages.emit(new Envelope(UUID.randomUUID(), type, 1, Instant.now(), "os-service", (UUID) row.get("os_id"),
				(UUID) row.get("saga_id"), (UUID) row.get("attempt_id"), (UUID) row.get("correlation_id"), cause,
				(UUID) row.get("os_id"), ((Number) row.get("version")).longValue(), json.valueToTree(payload)), true);
	}

	private Map<String, Object> workflow(UUID id, boolean lock) {
		var rows = db.queryForList("SELECT * FROM os_workflow WHERE os_id=?" + (lock ? " FOR UPDATE" : ""), id);
		if (rows.isEmpty())
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		return rows.getFirst();
	}

	private OrdemServico order(UUID id) {
		return orders.buscarPorId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	private static void requireStage(Map<String, Object> row, String state) {
		if (!state.equals(row.get("stage")))
			throw new IllegalArgumentException("INVALID_ORDER_STATE");
	}

	private static int number(Map<String, Object> row, String name) {
		return ((Number) row.get(name)).intValue();
	}

	private String encode(Object value) {
		try {
			return json.writeValueAsString(value);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	private BudgetSnapshot.Item decodeItem(String value) {
		try {
			return json.readValue(value, BudgetSnapshot.Item.class);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

}
