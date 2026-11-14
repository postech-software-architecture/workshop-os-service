package com.postech.foundation.bdd;

import io.cucumber.java.en.*;

public class BehaviorSteps {

	private com.postech.workshop_service.domain.entities.OrdemServico order;

	private boolean blocked;

	@Given("uma OS aprovada aguardando pagamento")
	public void given() {
		var id = java.util.UUID.randomUUID();
		order = new com.postech.workshop_service.domain.entities.OrdemServico(id, id, id);
		order.iniciarDiagnostico();
		order.encerrarDiagnostico();
		order.projetarEstadoDistribuido(
				com.postech.workshop_service.domain.entities.StatusOrdemServico.AGUARDANDO_ORCAMENTO);
		order.projetarEstadoDistribuido(
				com.postech.workshop_service.domain.entities.StatusOrdemServico.AGUARDANDO_PAGAMENTO);
	}

	@When("um operador tenta iniciar o reparo antes do pagamento")
	public void when() {
		try {
			order.iniciarExecucao();
		}
		catch (RuntimeException expected) {
			blocked = true;
		}
	}

	@Then("o reparo fica bloqueado e a OS continua aguardando pagamento")
	public void then() {
		org.junit.jupiter.api.Assertions.assertTrue(blocked);
		org.junit.jupiter.api.Assertions.assertEquals(
				com.postech.workshop_service.domain.entities.StatusOrdemServico.AGUARDANDO_PAGAMENTO,
				order.getStatus());
	}

}
