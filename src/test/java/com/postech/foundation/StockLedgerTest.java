package com.postech.foundation;

import java.util.*;
import java.math.BigDecimal;
import com.postech.workshop_service.domain.entities.*;
import com.postech.workshop_service.domain.repositories.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StockLedgerTest {

	@Test
	void compensationOfConsumptionUsesAnAuditedEntryAndCannotRepeat() {
		when(db.queryForList(startsWith("SELECT reservation_id,state"), eq(os), eq(attempt), eq(1)))
			.thenReturn(List.of(Map.of("reservation_id", reservation.getId(), "state", "CONSUMIDO")))
			.thenReturn(List.of());
		ledger.reverse(os, attempt, 1, budget);
		ledger.reverse(os, attempt, 1, budget);
		assertEquals(0, stock.getQuantidade().compareTo(new BigDecimal("10")));
		verify(movements, times(1)).salvar(
				argThat(m -> m.getTipo() == com.postech.workshop_service.domain.valueobjects.TipoMovimentacao.ENTRADA
						&& m.getMotivo().contains(reservation.getId().toString())));
	}

	@Test
	void compensationOfUnconsumedReservationUsesRelease() {
		when(db.queryForList(startsWith("SELECT reservation_id,state"), eq(os), eq(attempt), eq(1)))
			.thenReturn(List.of(Map.of("reservation_id", reservation.getId(), "state", "RESERVADO")));
		ledger.reverse(os, attempt, 1, budget);
		assertEquals(0, stock.getQuantidade().compareTo(new BigDecimal("10")));
		verify(movements).salvar(argThat(
				m -> m.getTipo() == com.postech.workshop_service.domain.valueobjects.TipoMovimentacao.LIBERACAO));
	}

	final JdbcTemplate db = mock(JdbcTemplate.class);

	final EstoqueRepository stocks = mock(EstoqueRepository.class);

	final MovimentacaoEstoqueRepository movements = mock(MovimentacaoEstoqueRepository.class);

	final StockLedger ledger = new StockLedger(db, mock(EntityManager.class), stocks, movements);

	final UUID os = UUID.randomUUID(), attempt = UUID.randomUUID(), budget = UUID.randomUUID();

	final Estoque stock = new Estoque(UUID.randomUUID(), UUID.randomUUID(), "A1", new BigDecimal("10"));

	final MovimentacaoEstoque reservation = stock.reservar(new BigDecimal("2.5"), "Reserva", os, null);

	StockLedgerTest() {
		when(db.queryForList(startsWith("SELECT reservation_id"), eq(UUID.class), eq(os), eq(attempt), eq(1)))
			.thenReturn(List.of(reservation.getId()))
			.thenReturn(List.of());
		when(stocks.buscarPorId(stock.getId(), true)).thenReturn(Optional.of(stock));
		when(movements.buscarPorId(reservation.getId())).thenReturn(Optional.of(reservation));
		when(movements.salvar(any())).thenAnswer(i -> i.getArgument(0));
	}

	@Test
	void consumptionDoesNotSubtractReservedStockAgain() {
		ledger.settle(os, attempt, 1, budget, true);
		ledger.settle(os, attempt, 1, budget, true);
		assertEquals(0, stock.getQuantidade().compareTo(new BigDecimal("7.5")));
		verify(stocks, never()).salvar(any());
		verify(movements, times(1)).salvar(any());
		verify(db).update(startsWith("UPDATE stock_reservations"), eq("CONSUMIDO"), any(UUID.class),
				eq(reservation.getId()));
	}

	@Test
	void rejectionReleasesExactlyTheOriginalQuantityOnce() {
		ledger.settle(os, attempt, 1, budget, false);
		ledger.settle(os, attempt, 1, budget, false);
		assertEquals(0, stock.getQuantidade().compareTo(new BigDecimal("10")));
		verify(stocks, times(1)).salvar(stock);
		verify(movements, times(1)).salvar(any());
		verify(db).update(startsWith("UPDATE stock_reservations"), eq("LIBERADO"), any(UUID.class),
				eq(reservation.getId()));
	}

}
