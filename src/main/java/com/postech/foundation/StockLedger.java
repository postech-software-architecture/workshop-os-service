package com.postech.foundation;

import java.util.UUID;
import com.postech.workshop_service.domain.repositories.EstoqueRepository;
import com.postech.workshop_service.domain.repositories.MovimentacaoEstoqueRepository;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** Each reservation has one terminal movement. The original ledger remains immutable. */
@Service
public class StockLedger {

	private final JdbcTemplate db;

	private final EntityManager entities;

	private final EstoqueRepository stocks;

	private final MovimentacaoEstoqueRepository movements;

	public StockLedger(JdbcTemplate db, EntityManager entities, EstoqueRepository stocks,
			MovimentacaoEstoqueRepository movements) {
		this.db = db;
		this.entities = entities;
		this.stocks = stocks;
		this.movements = movements;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void track(UUID osId, UUID attempt, int budgetVersion) {
		entities.flush();
		db.update(
				"INSERT INTO stock_reservations(reservation_id,os_id,attempt_id,budget_version,state) SELECT id,ordem_servico_id,?,?,'RESERVADO' FROM movimentacoes_estoque m WHERE ordem_servico_id=? AND tipo='RESERVA' AND NOT EXISTS(SELECT 1 FROM stock_reservations s WHERE s.reservation_id=m.id)",
				attempt, budgetVersion, osId);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void settle(UUID osId, UUID attempt, int budgetVersion, UUID budgetId, boolean consume) {
		var ids = db.queryForList(
				"SELECT reservation_id FROM stock_reservations WHERE os_id=? AND attempt_id=? AND budget_version=? AND state='RESERVADO' ORDER BY reservation_id FOR UPDATE",
				UUID.class, osId, attempt, budgetVersion);
		for (UUID id : ids) {
			var reservation = movements.buscarPorId(id).orElseThrow();
			var stock = stocks.buscarPorId(reservation.getEstoqueId(), true).orElseThrow();
			var result = consume
					? stock.consumirReserva(reservation.getQuantidade(), "Consumo da reserva " + id, osId, budgetId)
					: stock.liberarReserva(reservation.getQuantidade(), "Liberacao da reserva " + id, osId, budgetId);
			if (!consume)
				stocks.salvar(stock);
			var saved = movements.salvar(result);
			entities.flush();
			db.update("UPDATE stock_reservations SET state=?,result_movement_id=? WHERE reservation_id=?",
					consume ? "CONSUMIDO" : "LIBERADO", saved.getId(), id);
		}
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void reverse(UUID osId, UUID attempt, int budgetVersion, UUID budgetId) {
		var rows = db.queryForList(
				"SELECT reservation_id,state FROM stock_reservations WHERE os_id=? AND attempt_id=? AND budget_version=? AND state IN ('RESERVADO','CONSUMIDO') ORDER BY reservation_id FOR UPDATE",
				osId, attempt, budgetVersion);
		for (var row : rows) {
			UUID id = (UUID) row.get("reservation_id");
			var reservation = movements.buscarPorId(id).orElseThrow();
			var value = stocks.buscarPorId(reservation.getEstoqueId(), true).orElseThrow();
			var reversal = "CONSUMIDO".equals(row.get("state"))
					? value.registrarEntrada(reservation.getQuantidade(), "Reversao compensatoria da reserva " + id,
							osId, budgetId)
					: value.liberarReserva(reservation.getQuantidade(), "Liberacao compensatoria da reserva " + id,
							osId, budgetId);
			stocks.salvar(value);
			var saved = movements.salvar(reversal);
			entities.flush();
			db.update("UPDATE stock_reservations SET state='REVERTIDO',reversal_movement_id=? WHERE reservation_id=?",
					saved.getId(), id);
		}
	}

}
