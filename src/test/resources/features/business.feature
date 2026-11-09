Feature: Regras de negocio do servico
  Scenario: Comportamento protegido e repetivel
    Given uma OS aprovada aguardando pagamento
    When um operador tenta iniciar o reparo antes do pagamento
    Then o reparo fica bloqueado e a OS continua aguardando pagamento
