package com.postech.foundation;

import com.postech.workshop_service.domain.entities.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DistributedOrderTest {

	OrdemServico awaitingBudget() {
		var order = new OrdemServico(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		order.projetarEstadoDistribuido(StatusOrdemServico.EM_DIAGNOSTICO);
		order.projetarEstadoDistribuido(StatusOrdemServico.EM_COMPOSICAO);
		order.projetarEstadoDistribuido(StatusOrdemServico.AGUARDANDO_ORCAMENTO);
		return order;
	}

	@Test
	void approvalMustNotStartRepair() {
		var order = awaitingBudget();
		order.projetarEstadoDistribuido(StatusOrdemServico.AGUARDANDO_PAGAMENTO);
		assertThrows(RuntimeException.class, order::iniciarExecucao);
		assertThrows(RuntimeException.class, () -> order.projetarEstadoDistribuido(StatusOrdemServico.EM_EXECUCAO));
	}

	@Test
	void decisionMayArriveBeforeGeneratedProjection() {
		var order = awaitingBudget();
		order.projetarEstadoDistribuido(StatusOrdemServico.AGUARDANDO_PAGAMENTO);
		assertEquals(StatusOrdemServico.AGUARDANDO_PAGAMENTO, order.getStatus());
	}

	@Test
	void rejectedCompositionIsExplicitlyCleared() {
		var order = awaitingBudget();
		order.limparComposicaoRejeitada();
		order.projetarEstadoDistribuido(StatusOrdemServico.EM_COMPOSICAO);
		assertTrue(order.getItensComposicao().isEmpty());
		assertThrows(RuntimeException.class, order::limparComposicaoRejeitada);
	}

	@Test
	void cancelledOrderCannotResume() {
		var order = awaitingBudget();
		order.projetarEstadoDistribuido(StatusOrdemServico.CANCELADA);
		assertThrows(RuntimeException.class, () -> order.projetarEstadoDistribuido(StatusOrdemServico.EM_COMPOSICAO));
	}

}
