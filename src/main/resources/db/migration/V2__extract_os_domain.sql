-- Source: V0.20260424213700__create_table_clientes.sql
CREATE TABLE clientes (
    id UUID PRIMARY KEY,
    nome VARCHAR(255) NOT NULL,
    documento VARCHAR(20) NOT NULL UNIQUE,
    email VARCHAR(255),
    telefone VARCHAR(20),
    data_nascimento_fundacao DATE,
    observacoes TEXT,
    data_criacao TIMESTAMP NOT NULL,
    data_ultima_atualizacao TIMESTAMP NOT NULL
);

COMMENT ON TABLE clientes IS 'Tabela que armazena os clientes da oficina (Pessoa Física ou Jurídica).';
COMMENT ON COLUMN clientes.id IS 'Identificador único do cliente UUID.';
COMMENT ON COLUMN clientes.nome IS 'Nome completo ou Razão Social.';
COMMENT ON COLUMN clientes.documento IS 'CPF ou CNPJ apenas em formato numérico.';
COMMENT ON COLUMN clientes.email IS 'Endereço de e-mail (opcional, desde que tenha telefone).';
COMMENT ON COLUMN clientes.telefone IS 'Telefone de contato (opcional, desde que tenha email).';
COMMENT ON COLUMN clientes.data_nascimento_fundacao IS 'Data de nascimento para PF ou fundação para PJ.';
COMMENT ON COLUMN clientes.observacoes IS 'Observações gerais sobre o cliente.';
COMMENT ON COLUMN clientes.data_criacao IS 'Data e hora da criação do registro.';
COMMENT ON COLUMN clientes.data_ultima_atualizacao IS 'Data e hora da última modificação do registro.';


-- Source: V0.20260426131500__create_table_enderecos.sql
CREATE TABLE enderecos (
    id UUID PRIMARY KEY,
    cliente_id UUID NOT NULL UNIQUE,
    logradouro VARCHAR(255) NOT NULL,
    numero VARCHAR(20),
    complemento VARCHAR(100),
    bairro VARCHAR(100),
    cidade VARCHAR(100) NOT NULL,
    estado VARCHAR(50) NOT NULL,
    cep VARCHAR(10),
    CONSTRAINT fk_endereco_cliente FOREIGN KEY (cliente_id) REFERENCES clientes(id) ON DELETE CASCADE
);

COMMENT ON TABLE enderecos IS 'Tabela que armazena os endereços dos clientes da oficina.';
COMMENT ON COLUMN enderecos.id IS 'ID único do endereço.';
COMMENT ON COLUMN enderecos.cliente_id IS 'ID do cliente associado (FK).';
COMMENT ON COLUMN enderecos.logradouro IS 'Nome da rua/avenida.';
COMMENT ON COLUMN enderecos.numero IS 'Número do endereço.';
COMMENT ON COLUMN enderecos.complemento IS 'Complemento do endereço.';
COMMENT ON COLUMN enderecos.bairro IS 'Bairro.';
COMMENT ON COLUMN enderecos.cidade IS 'Cidade.';
COMMENT ON COLUMN enderecos.estado IS 'Estado (UF).';
COMMENT ON COLUMN enderecos.cep IS 'CEP (apenas números).';


-- Source: V0.20260426140000__create_table_veiculos.sql
CREATE TABLE veiculos (
    id UUID PRIMARY KEY,
    placa VARCHAR(7) NOT NULL,
    marca VARCHAR(60) NOT NULL,
    modelo VARCHAR(80) NOT NULL,
    ano INTEGER NOT NULL,
    cor VARCHAR(30),
    observacoes TEXT,
    ativo BOOLEAN NOT NULL,
    data_remocao TIMESTAMP,
    data_criacao TIMESTAMP NOT NULL,
    data_ultima_atualizacao TIMESTAMP NOT NULL
);

CREATE TABLE veiculos_clientes (
    veiculo_id UUID NOT NULL,
    cliente_id UUID NOT NULL,
    data_criacao TIMESTAMP NOT NULL,
    data_ultima_atualizacao TIMESTAMP NOT NULL,
    CONSTRAINT pk_veiculos_clientes PRIMARY KEY (veiculo_id, cliente_id),
    CONSTRAINT fk_veiculos_clientes_veiculos FOREIGN KEY (veiculo_id) REFERENCES veiculos(id),
    CONSTRAINT fk_veiculos_clientes_clientes FOREIGN KEY (cliente_id) REFERENCES clientes(id)
);

CREATE UNIQUE INDEX ux_veiculos_placa_ativa ON veiculos (placa) WHERE ativo = TRUE;
CREATE INDEX ix_veiculos_ativo ON veiculos (ativo);
CREATE INDEX ix_veiculos_clientes_cliente_id ON veiculos_clientes (cliente_id);

COMMENT ON TABLE veiculos IS 'Tabela que armazena os veiculos atendidos pela oficina.';
COMMENT ON COLUMN veiculos.id IS 'Identificador unico do veiculo em formato UUID.';
COMMENT ON COLUMN veiculos.placa IS 'Placa normalizada do veiculo, sem separadores e em caixa alta.';
COMMENT ON COLUMN veiculos.marca IS 'Marca comercial do veiculo.';
COMMENT ON COLUMN veiculos.modelo IS 'Modelo comercial do veiculo.';
COMMENT ON COLUMN veiculos.ano IS 'Ano do veiculo validado dentro da faixa operacional da oficina.';
COMMENT ON COLUMN veiculos.cor IS 'Cor predominante do veiculo.';
COMMENT ON COLUMN veiculos.observacoes IS 'Observacoes operacionais livres sobre o veiculo.';
COMMENT ON COLUMN veiculos.ativo IS 'Indica se o veiculo esta ativo nas consultas operacionais padrao.';
COMMENT ON COLUMN veiculos.data_remocao IS 'Data e hora em que o veiculo foi removido logicamente.';
COMMENT ON COLUMN veiculos.data_criacao IS 'Data e hora de criacao do registro.';
COMMENT ON COLUMN veiculos.data_ultima_atualizacao IS 'Data e hora da ultima atualizacao do registro.';

COMMENT ON TABLE veiculos_clientes IS 'Tabela de associacao entre veiculos e clientes vinculados.';
COMMENT ON COLUMN veiculos_clientes.veiculo_id IS 'Identificador do veiculo associado.';
COMMENT ON COLUMN veiculos_clientes.cliente_id IS 'Identificador do cliente associado.';
COMMENT ON COLUMN veiculos_clientes.data_criacao IS 'Data e hora de criacao do vinculo.';
COMMENT ON COLUMN veiculos_clientes.data_ultima_atualizacao IS 'Data e hora da ultima atualizacao do vinculo.';


-- Source: V0.20260427220000__add_audit_columns_to_clientes_and_enderecos.sql
-- Adiciona coluna de remocao logica na tabela clientes
ALTER TABLE clientes ADD COLUMN IF NOT EXISTS data_remocao TIMESTAMP;

COMMENT ON COLUMN clientes.data_remocao IS 'Data e hora em que o cliente foi removido logicamente.';

-- Adiciona colunas de auditoria na tabela enderecos
ALTER TABLE enderecos ADD COLUMN IF NOT EXISTS data_criacao TIMESTAMP NOT NULL DEFAULT NOW();
ALTER TABLE enderecos ADD COLUMN IF NOT EXISTS data_ultima_atualizacao TIMESTAMP NOT NULL DEFAULT NOW();
ALTER TABLE enderecos ADD COLUMN IF NOT EXISTS data_remocao TIMESTAMP;

COMMENT ON COLUMN enderecos.data_criacao IS 'Data e hora de criacao do registro.';
COMMENT ON COLUMN enderecos.data_ultima_atualizacao IS 'Data e hora da ultima atualizacao do registro.';
COMMENT ON COLUMN enderecos.data_remocao IS 'Data e hora em que o endereco foi removido logicamente.';


-- Source: V0.20260428000000__add_ativo_to_clientes.sql
ALTER TABLE clientes ADD COLUMN ativo BOOLEAN NOT NULL DEFAULT true;

COMMENT ON COLUMN clientes.ativo IS 'Indica se o cliente esta ativo (true) ou removido logicamente (false).';


-- Source: V0.20260428120000__create_table_servicos.sql
CREATE TABLE servicos (
    id UUID PRIMARY KEY,
    nome VARCHAR(120) NOT NULL,
    descricao TEXT NOT NULL,
    valor NUMERIC(10,2) NOT NULL,
    categoria VARCHAR(30),
    nivel_complexidade VARCHAR(20),
    garantia_dias INTEGER,
    observacoes_tecnicas TEXT,
    ativo BOOLEAN NOT NULL DEFAULT TRUE,
    data_criacao TIMESTAMP NOT NULL,
    data_ultima_atualizacao TIMESTAMP NOT NULL,
    data_remocao TIMESTAMP,
    CONSTRAINT ck_servicos_valor_positivo CHECK (valor > 0),
    CONSTRAINT ck_servicos_garantia_dias_positivo CHECK (garantia_dias IS NULL OR garantia_dias > 0)
);

CREATE UNIQUE INDEX ux_servicos_nome_ativo ON servicos (nome) WHERE ativo = TRUE;
CREATE INDEX ix_servicos_ativo ON servicos (ativo);
CREATE INDEX ix_servicos_categoria ON servicos (categoria);

COMMENT ON TABLE servicos IS 'Tabela que armazena os serviços oferecidos pela oficina.';
COMMENT ON COLUMN servicos.id IS 'Identificador único do serviço em formato UUID.';
COMMENT ON COLUMN servicos.nome IS 'Nome do serviço, único entre os serviços ativos.';
COMMENT ON COLUMN servicos.descricao IS 'Descrição detalhada do serviço prestado.';
COMMENT ON COLUMN servicos.valor IS 'Valor cobrado pelo serviço, deve ser maior que zero.';
COMMENT ON COLUMN servicos.categoria IS 'Categoria do serviço: MECANICA, ELETRICA, ESTETICA ou PREVENTIVA.';
COMMENT ON COLUMN servicos.nivel_complexidade IS 'Nível de complexidade do serviço: BAIXA, MEDIA ou ALTA.';
COMMENT ON COLUMN servicos.garantia_dias IS 'Prazo de garantia do serviço em dias, quando aplicável.';
COMMENT ON COLUMN servicos.observacoes_tecnicas IS 'Observações técnicas livres sobre a execução do serviço.';
COMMENT ON COLUMN servicos.ativo IS 'Indica se o serviço está ativo e disponível para uso.';
COMMENT ON COLUMN servicos.data_criacao IS 'Data e hora de criação do registro.';
COMMENT ON COLUMN servicos.data_ultima_atualizacao IS 'Data e hora da última atualização do registro.';
COMMENT ON COLUMN servicos.data_remocao IS 'Data e hora em que o serviço foi removido logicamente.';


-- Source: V0.20260429100000__create_table_ordens_servico.sql
CREATE TABLE ordens_servico (
    id UUID PRIMARY KEY,
    id_cliente UUID NOT NULL,
    id_veiculo UUID NOT NULL,
    status VARCHAR(40) NOT NULL,
    data_remocao TIMESTAMP,
    data_criacao TIMESTAMP NOT NULL,
    data_ultima_atualizacao TIMESTAMP NOT NULL
);

CREATE TABLE ordens_servico_itens (
    ordem_servico_id UUID NOT NULL,
    ordem_item INTEGER NOT NULL,
    descricao VARCHAR(255) NOT NULL,
    valor NUMERIC(19, 2) NOT NULL,
    tipo VARCHAR(30) NOT NULL,
    CONSTRAINT pk_ordens_servico_itens PRIMARY KEY (ordem_servico_id, ordem_item),
    CONSTRAINT fk_ordens_servico_itens_ordem_servico FOREIGN KEY (ordem_servico_id) REFERENCES ordens_servico(id)
);

CREATE INDEX ix_ordens_servico_cliente ON ordens_servico (id_cliente);
CREATE INDEX ix_ordens_servico_veiculo ON ordens_servico (id_veiculo);
CREATE INDEX ix_ordens_servico_status ON ordens_servico (status);

COMMENT ON TABLE ordens_servico IS 'Tabela que armazena as ordens de servico da oficina.';
COMMENT ON COLUMN ordens_servico.id IS 'Identificador unico da ordem de servico em formato UUID.';
COMMENT ON COLUMN ordens_servico.id_cliente IS 'Identificador do cliente vinculado a ordem de servico.';
COMMENT ON COLUMN ordens_servico.id_veiculo IS 'Identificador do veiculo vinculado a ordem de servico.';
COMMENT ON COLUMN ordens_servico.status IS 'Estado atual do fluxo operacional da ordem de servico.';
COMMENT ON COLUMN ordens_servico.data_remocao IS 'Data e hora de remocao logica da ordem de servico.';
COMMENT ON COLUMN ordens_servico.data_criacao IS 'Data e hora de criacao do registro da ordem de servico.';
COMMENT ON COLUMN ordens_servico.data_ultima_atualizacao IS 'Data e hora da ultima atualizacao do registro da ordem de servico.';

COMMENT ON TABLE ordens_servico_itens IS 'Tabela que armazena os itens de composicao tecnica de cada ordem de servico.';
COMMENT ON COLUMN ordens_servico_itens.ordem_servico_id IS 'Identificador da ordem de servico dona do item de composicao.';
COMMENT ON COLUMN ordens_servico_itens.ordem_item IS 'Posicao ordenada do item dentro da composicao tecnica da ordem.';
COMMENT ON COLUMN ordens_servico_itens.descricao IS 'Descricao do item de composicao tecnica.';
COMMENT ON COLUMN ordens_servico_itens.valor IS 'Valor monetario do item de composicao tecnica.';
COMMENT ON COLUMN ordens_servico_itens.tipo IS 'Classificacao do item de composicao tecnica entre servico, peca e insumo.';


-- Source: V0.20260429220000__create_table_pecas_estoques_movimentacoes.sql
-- Tabela de pecas/insumos
CREATE TABLE pecas_insumos (
    id UUID PRIMARY KEY,
    sku VARCHAR(50) NOT NULL,
    nome VARCHAR(200) NOT NULL,
    valor_unitario DECIMAL(10,2) NOT NULL,
    estoque_minimo DECIMAL(10,3) NOT NULL DEFAULT 0,
    unidade_medida VARCHAR(10) NOT NULL,
    tipo_item VARCHAR(20) NOT NULL,
    fornecedor VARCHAR(200),
    codigo_barras VARCHAR(50),
    marca VARCHAR(100),
    categoria VARCHAR(100),
    aplicacao VARCHAR(500),
    observacoes TEXT,
    ativo BOOLEAN NOT NULL DEFAULT true,
    versao INTEGER NOT NULL DEFAULT 0,
    data_criacao TIMESTAMP NOT NULL DEFAULT now(),
    data_ultima_atualizacao TIMESTAMP NOT NULL DEFAULT now(),
    data_remocao TIMESTAMP,

    CONSTRAINT chk_estoque_minimo_nao_negativo CHECK (estoque_minimo >= 0),
    CONSTRAINT chk_valor_positivo CHECK (valor_unitario > 0)
);

-- Partial unique index for SKU (only active parts)
CREATE UNIQUE INDEX uk_pecas_insumos_sku_ativo ON pecas_insumos(sku) WHERE ativo = true;

COMMENT ON TABLE pecas_insumos IS 'Cadastro de pecas e insumos utilizados nos servicos da oficina';
COMMENT ON COLUMN pecas_insumos.id IS 'Identificador unico da peca';
COMMENT ON COLUMN pecas_insumos.sku IS 'Codigo SKU unico entre pecas ativas';
COMMENT ON COLUMN pecas_insumos.nome IS 'Nome ou descricao da peca';
COMMENT ON COLUMN pecas_insumos.valor_unitario IS 'Preco unitario da peca';
COMMENT ON COLUMN pecas_insumos.estoque_minimo IS 'Nivel minimo para alerta de reposicao (soma de todos os estoques)';
COMMENT ON COLUMN pecas_insumos.unidade_medida IS 'Unidade de medida: UN, L, KG, M, ML, CX, PC';
COMMENT ON COLUMN pecas_insumos.tipo_item IS 'Tipo principal do item: PECA ou INSUMO';
COMMENT ON COLUMN pecas_insumos.ativo IS 'Indicador de peca ativa no catalogo';
COMMENT ON COLUMN pecas_insumos.versao IS 'Versao para controle de concurrencia (optimistic locking)';

-- Tabela de estoques (localizacoes)
CREATE TABLE estoques (
    id UUID PRIMARY KEY,
    peca_insumo_id UUID NOT NULL,
    localizacao VARCHAR(100) NOT NULL,
    quantidade DECIMAL(10,3) NOT NULL DEFAULT 0,
    ativo BOOLEAN NOT NULL DEFAULT true,
    versao INTEGER NOT NULL DEFAULT 0,
    data_criacao TIMESTAMP NOT NULL DEFAULT now(),
    data_ultima_atualizacao TIMESTAMP NOT NULL DEFAULT now(),
    
    CONSTRAINT fk_estoques_pecas_insumos FOREIGN KEY (peca_insumo_id) 
        REFERENCES pecas_insumos(id) ON DELETE RESTRICT,
    CONSTRAINT uk_estoques_peca_localizacao UNIQUE (peca_insumo_id, localizacao),
    CONSTRAINT chk_quantidade_nao_negativa CHECK (quantidade >= 0)
);

CREATE INDEX idx_estoques_peca ON estoques(peca_insumo_id);
CREATE INDEX idx_estoques_ativo ON estoques(ativo);

COMMENT ON TABLE estoques IS 'Registro de quantidade de pecas por localizacao fisica';
COMMENT ON COLUMN estoques.peca_insumo_id IS 'Referencia a peca do estoque';
COMMENT ON COLUMN estoques.localizacao IS 'Localizacao fisica no estoque (ex: Prateleira A2)';
COMMENT ON COLUMN estoques.quantidade IS 'Quantidade disponivel nesta localizacao';
COMMENT ON COLUMN estoques.ativo IS 'Indicador de estoque ativo';
COMMENT ON COLUMN estoques.versao IS 'Versao para controle de concurrencia (optimistic locking)';

-- Tabela de movimentacoes de estoque
CREATE TABLE movimentacoes_estoque (
    id UUID PRIMARY KEY,
    estoque_id UUID NOT NULL,
    tipo VARCHAR(20) NOT NULL,
    quantidade DECIMAL(10,3) NOT NULL,
    quantidade_anterior DECIMAL(10,3) NOT NULL,
    quantidade_posterior DECIMAL(10,3) NOT NULL,
    motivo VARCHAR(500),
    data_movimentacao TIMESTAMP NOT NULL DEFAULT now(),
    data_criacao TIMESTAMP NOT NULL DEFAULT now(),
    
    CONSTRAINT fk_movimentacoes_estoques FOREIGN KEY (estoque_id) 
        REFERENCES estoques(id) ON DELETE RESTRICT,
    CONSTRAINT chk_tipo_valido CHECK (tipo IN ('ENTRADA', 'SAIDA', 'AJUSTE'))
);

CREATE INDEX idx_movimentacoes_estoque ON movimentacoes_estoque(estoque_id);
CREATE INDEX idx_movimentacoes_data ON movimentacoes_estoque(data_movimentacao DESC);

COMMENT ON TABLE movimentacoes_estoque IS 'Historico de movimentacoes de estoque';
COMMENT ON COLUMN movimentacoes_estoque.estoque_id IS 'Referencia ao estoque movimentado';
COMMENT ON COLUMN movimentacoes_estoque.tipo IS 'Tipo de movimentacao: ENTRADA, SAIDA, AJUSTE';
COMMENT ON COLUMN movimentacoes_estoque.quantidade IS 'Quantidade movimentada';
COMMENT ON COLUMN movimentacoes_estoque.quantidade_anterior IS 'Quantidade em estoque antes da movimentacao';
COMMENT ON COLUMN movimentacoes_estoque.quantidade_posterior IS 'Quantidade em estoque apos a movimentacao';
COMMENT ON COLUMN movimentacoes_estoque.motivo IS 'Motivo ou justificativa da movimentacao';

-- Indices para consultas frequentes
CREATE INDEX idx_pecas_insumos_sku ON pecas_insumos(sku);
CREATE INDEX idx_pecas_insumos_ativo ON pecas_insumos(ativo);
CREATE INDEX idx_pecas_insumos_categoria ON pecas_insumos(categoria);


-- Source: V0.20260501100000__add_numero_observacoes_to_ordens_servico.sql
ALTER TABLE ordens_servico
    ADD COLUMN observacoes TEXT,
    ADD COLUMN numero VARCHAR(20);

WITH numerados AS (
    SELECT id,
           'OS-' || EXTRACT(YEAR FROM data_criacao)::TEXT || '-' ||
           LPAD(ROW_NUMBER() OVER (PARTITION BY EXTRACT(YEAR FROM data_criacao) ORDER BY data_criacao)::TEXT,
                5, '0') AS num
    FROM ordens_servico
    WHERE numero IS NULL
)
UPDATE ordens_servico
SET numero = numerados.num
FROM numerados
WHERE ordens_servico.id = numerados.id;

ALTER TABLE ordens_servico
    ALTER COLUMN numero SET NOT NULL;

CREATE UNIQUE INDEX ux_ordens_servico_numero ON ordens_servico (numero);

COMMENT ON COLUMN ordens_servico.numero IS 'Numero sequencial unico da OS no formato OS-{ANO}-{NNNNN}.';
COMMENT ON COLUMN ordens_servico.observacoes IS 'Observacoes registradas pelo atendente na recepcao do veiculo.';


-- Source: V0.20260501190000__create_table_usuarios_roles_refresh_tokens.sql
CREATE TABLE usuarios (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    email VARCHAR(255),
    senha_hash VARCHAR(255) NOT NULL,
    cliente_id UUID,
    ativo BOOLEAN NOT NULL DEFAULT true,
    bloqueado BOOLEAN NOT NULL DEFAULT false,
    data_criacao TIMESTAMP NOT NULL DEFAULT now(),
    data_ultima_atualizacao TIMESTAMP NOT NULL DEFAULT now(),
    data_remocao TIMESTAMP,

    CONSTRAINT uk_usuarios_username UNIQUE (username),
    CONSTRAINT uk_usuarios_email UNIQUE (email),
    CONSTRAINT uk_usuarios_cliente UNIQUE (cliente_id),
    CONSTRAINT fk_usuarios_clientes FOREIGN KEY (cliente_id) REFERENCES clientes(id) ON DELETE RESTRICT
);

CREATE TABLE usuarios_roles (
    usuario_id UUID NOT NULL,
    role VARCHAR(30) NOT NULL,

    CONSTRAINT pk_usuarios_roles PRIMARY KEY (usuario_id, role),
    CONSTRAINT fk_usuarios_roles_usuarios FOREIGN KEY (usuario_id) REFERENCES usuarios(id) ON DELETE CASCADE,
    CONSTRAINT chk_usuarios_roles_validos CHECK (role IN ('ADMINISTRADOR', 'ATENDENTE', 'MECANICO', 'CLIENTE'))
);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    token VARCHAR(255) NOT NULL,
    usuario_id UUID NOT NULL,
    data_expiracao TIMESTAMP NOT NULL,
    revogado BOOLEAN NOT NULL DEFAULT false,
    data_revogacao TIMESTAMP,
    data_criacao TIMESTAMP NOT NULL DEFAULT now(),
    data_ultima_atualizacao TIMESTAMP NOT NULL DEFAULT now(),
    data_remocao TIMESTAMP,

    CONSTRAINT uk_refresh_tokens_token UNIQUE (token),
    CONSTRAINT fk_refresh_tokens_usuarios FOREIGN KEY (usuario_id) REFERENCES usuarios(id) ON DELETE CASCADE
);

CREATE INDEX idx_usuarios_email ON usuarios(email);
CREATE INDEX idx_refresh_tokens_usuario ON refresh_tokens(usuario_id);
CREATE INDEX idx_refresh_tokens_token ON refresh_tokens(token);

COMMENT ON TABLE usuarios IS 'Contas autenticaveis do sistema de oficina';
COMMENT ON COLUMN usuarios.id IS 'Identificador unico da conta de usuario';
COMMENT ON COLUMN usuarios.username IS 'Identificador principal de login do usuario';
COMMENT ON COLUMN usuarios.email IS 'Email opcional utilizado como identificador alternativo de login';
COMMENT ON COLUMN usuarios.senha_hash IS 'Hash BCrypt da senha do usuario';
COMMENT ON COLUMN usuarios.cliente_id IS 'Cliente vinculado de forma obrigatoria para contas com papel CLIENTE';
COMMENT ON COLUMN usuarios.ativo IS 'Indica se a conta esta habilitada para autenticacao';
COMMENT ON COLUMN usuarios.bloqueado IS 'Indica se a conta foi bloqueada administrativamente';
COMMENT ON COLUMN usuarios.data_criacao IS 'Data de criacao do registro';
COMMENT ON COLUMN usuarios.data_ultima_atualizacao IS 'Data da ultima atualizacao do registro';
COMMENT ON COLUMN usuarios.data_remocao IS 'Data da remocao logica da conta, quando aplicavel';

COMMENT ON TABLE usuarios_roles IS 'Tabela de papeis de acesso associados a cada conta autenticavel';
COMMENT ON COLUMN usuarios_roles.usuario_id IS 'Conta autenticavel proprietaria do papel de acesso';
COMMENT ON COLUMN usuarios_roles.role IS 'Papel de acesso atribuido a conta';

COMMENT ON TABLE refresh_tokens IS 'Credenciais persistidas para renovacao de sessao autenticada';
COMMENT ON COLUMN refresh_tokens.id IS 'Identificador unico da credencial de renovacao';
COMMENT ON COLUMN refresh_tokens.token IS 'Valor opaco e unico da credencial de renovacao';
COMMENT ON COLUMN refresh_tokens.usuario_id IS 'Conta autenticavel dona da credencial de renovacao';
COMMENT ON COLUMN refresh_tokens.data_expiracao IS 'Prazo maximo para uso da credencial de renovacao';
COMMENT ON COLUMN refresh_tokens.revogado IS 'Indica se a credencial foi invalidada antes do vencimento';
COMMENT ON COLUMN refresh_tokens.data_revogacao IS 'Data em que a credencial foi revogada';
COMMENT ON COLUMN refresh_tokens.data_criacao IS 'Data de criacao da credencial';
COMMENT ON COLUMN refresh_tokens.data_ultima_atualizacao IS 'Data da ultima atualizacao da credencial';
COMMENT ON COLUMN refresh_tokens.data_remocao IS 'Data de remocao logica da credencial, quando aplicavel';


-- Source: V0.20260506120000__add_peca_insumo_id_to_os_itens.sql
ALTER TABLE ordens_servico_itens
    ADD COLUMN peca_insumo_id UUID NULL;

COMMENT ON COLUMN ordens_servico_itens.peca_insumo_id IS 'Identificador da peca ou insumo do catalogo. Preenchido para itens dos tipos PECA e INSUMO para possibilitar rastreio de reservas de estoque.';


-- Source: V0.20260506130000__add_reserva_liberacao_to_tipo_movimentacao.sql
ALTER TABLE movimentacoes_estoque
    DROP CONSTRAINT chk_tipo_valido;

ALTER TABLE movimentacoes_estoque
    ADD CONSTRAINT chk_tipo_valido
        CHECK (tipo IN ('ENTRADA', 'SAIDA', 'AJUSTE', 'RESERVA', 'LIBERACAO'));


-- Source: V0.20260507100000__add_execution_cycle_to_ordens_servico.sql
ALTER TABLE ordens_servico
	ADD COLUMN data_inicio_execucao TIMESTAMP,
	ADD COLUMN data_finalizacao TIMESTAMP,
	ADD COLUMN data_entrega TIMESTAMP;

COMMENT ON COLUMN ordens_servico.data_inicio_execucao IS 'Data e hora em que a execucao tecnica da ordem de servico foi iniciada.';
COMMENT ON COLUMN ordens_servico.data_finalizacao IS 'Data e hora em que a execucao tecnica da ordem de servico foi finalizada.';
COMMENT ON COLUMN ordens_servico.data_entrega IS 'Data e hora em que o veiculo foi entregue ao cliente.';


-- Source: V0.20260507101000__create_table_historico_status_os.sql
CREATE TABLE historico_status_os (
	id UUID PRIMARY KEY,
	ordem_servico_id UUID NOT NULL,
	status_anterior VARCHAR(40) NOT NULL,
	status_novo VARCHAR(40) NOT NULL,
	data_transicao TIMESTAMP NOT NULL,
	usuario_id UUID NOT NULL,
	usuario_username VARCHAR(120) NOT NULL,
	data_criacao TIMESTAMP NOT NULL,
	data_ultima_atualizacao TIMESTAMP NOT NULL,
	data_remocao TIMESTAMP,
	CONSTRAINT fk_historico_status_os_ordem_servico
		FOREIGN KEY (ordem_servico_id) REFERENCES ordens_servico(id),
	CONSTRAINT chk_historico_status_os_status_distinto
		CHECK (status_anterior <> status_novo)
);

CREATE INDEX ix_historico_status_os_ordem_data
	ON historico_status_os (ordem_servico_id, data_transicao);

COMMENT ON TABLE historico_status_os IS 'Linha do tempo auditavel de transicoes de status da ordem de servico.';
COMMENT ON COLUMN historico_status_os.ordem_servico_id IS 'Identificador da ordem de servico vinculada ao historico.';
COMMENT ON COLUMN historico_status_os.status_anterior IS 'Status da ordem de servico antes da transicao.';
COMMENT ON COLUMN historico_status_os.status_novo IS 'Status da ordem de servico apos a transicao.';
COMMENT ON COLUMN historico_status_os.data_transicao IS 'Data e hora em que a transicao foi registrada.';
COMMENT ON COLUMN historico_status_os.usuario_id IS 'Identificador do usuario responsavel pela transicao.';
COMMENT ON COLUMN historico_status_os.usuario_username IS 'Username do usuario responsavel pela transicao.';


-- Source: V0.20260507230000__vincular_movimentacao_estoque_ordem_orcamento.sql
ALTER TABLE movimentacoes_estoque
    ADD COLUMN ordem_servico_id UUID,
    ADD COLUMN orcamento_id UUID;

COMMENT ON COLUMN movimentacoes_estoque.ordem_servico_id IS 'Identificador da ordem de servico relacionada a movimentacao operacional de estoque.';
COMMENT ON COLUMN movimentacoes_estoque.orcamento_id IS 'Identificador do orcamento que originou a movimentacao de reserva ou liberacao.';

ALTER TABLE movimentacoes_estoque
    ADD CONSTRAINT fk_movimentacoes_estoque_ordem_servico
        FOREIGN KEY (ordem_servico_id) REFERENCES ordens_servico (id);



CREATE INDEX idx_movimentacoes_estoque_ordem_servico_id
    ON movimentacoes_estoque (ordem_servico_id);

CREATE INDEX idx_movimentacoes_estoque_orcamento_id
    ON movimentacoes_estoque (orcamento_id);

COMMENT ON CONSTRAINT fk_movimentacoes_estoque_ordem_servico ON movimentacoes_estoque
    IS 'Garante rastreabilidade de movimentacoes operacionais por ordem de servico.';




-- Source: V0.20260511215148__add_execucao_individual_aos_itens_os.sql
-- Adiciona ciclo de vida individual aos itens de servico da ordem de servico.
-- Substitui a PK composta (ordem_servico_id, ordem_item) por uma PK UUID propria,
-- mantendo ordem_item como indicador de prioridade de execucao.

ALTER TABLE ordens_servico_itens ADD COLUMN id UUID;
UPDATE ordens_servico_itens SET id = gen_random_uuid() WHERE id IS NULL;
ALTER TABLE ordens_servico_itens ALTER COLUMN id SET NOT NULL;

ALTER TABLE ordens_servico_itens DROP CONSTRAINT pk_ordens_servico_itens;
ALTER TABLE ordens_servico_itens ADD CONSTRAINT pk_ordens_servico_itens PRIMARY KEY (id);
ALTER TABLE ordens_servico_itens
    ADD CONSTRAINT uq_ordens_servico_itens_ordem UNIQUE (ordem_servico_id, ordem_item);

ALTER TABLE ordens_servico_itens ADD COLUMN servico_id UUID;
ALTER TABLE ordens_servico_itens
    ADD CONSTRAINT fk_ordens_servico_itens_servico FOREIGN KEY (servico_id) REFERENCES servicos(id);

ALTER TABLE ordens_servico_itens ADD COLUMN status_execucao VARCHAR(20);
ALTER TABLE ordens_servico_itens ADD COLUMN data_inicio_execucao TIMESTAMP;
ALTER TABLE ordens_servico_itens ADD COLUMN data_finalizacao TIMESTAMP;

UPDATE ordens_servico_itens SET status_execucao = 'PENDENTE' WHERE tipo = 'SERVICO';

CREATE INDEX ix_ordens_servico_itens_servico ON ordens_servico_itens (servico_id);
CREATE INDEX ix_ordens_servico_itens_status_execucao ON ordens_servico_itens (status_execucao);

COMMENT ON COLUMN ordens_servico_itens.id IS 'Identificador unico do item de composicao tecnica em formato UUID.';
COMMENT ON COLUMN ordens_servico_itens.ordem_item IS 'Prioridade de execucao do item dentro da composicao tecnica da ordem.';
COMMENT ON COLUMN ordens_servico_itens.servico_id IS 'Identificador do servico do catalogo vinculado ao item, preenchido apenas para itens do tipo SERVICO.';
COMMENT ON COLUMN ordens_servico_itens.status_execucao IS 'Estado do ciclo de execucao do item de servico (PENDENTE, EM_EXECUCAO, FINALIZADO). Apenas para itens do tipo SERVICO.';
COMMENT ON COLUMN ordens_servico_itens.data_inicio_execucao IS 'Data e hora em que a execucao tecnica do servico foi iniciada pelo mecanico.';
COMMENT ON COLUMN ordens_servico_itens.data_finalizacao IS 'Data e hora em que a execucao tecnica do servico foi finalizada pelo mecanico.';



-- Source: V0.20260912185705__add_integrity_constraints_to_workshop.sql
-- Identidade persistida para transicoes disparadas por integracoes maquina-a-maquina.
-- O BCrypt abaixo nao possui credencial correspondente provisionada. Somado a
-- ativo=false e bloqueado=true, ele mantem esta conta tecnica nao autenticavel.
INSERT INTO usuarios (
    id, username, email, senha_hash, cliente_id, ativo, bloqueado,
    data_criacao, data_ultima_atualizacao
) VALUES (
    '70000000-0000-0000-0000-000000000001', 'system.webhook', NULL,
    '$2a$12$gITEG.iBxvaOrg7TI69EuuOT0vN7dZdWpjRvFF3ohF0RdWyTJpUAB',
    NULL, false, true, now(), now()
);

-- O papel tecnico mantem a conta compativel com o modelo de dominio sem conceder
-- acesso aos endpoints, que autorizam apenas os papeis humanos existentes.
ALTER TABLE usuarios_roles
    DROP CONSTRAINT chk_usuarios_roles_validos,
    ADD CONSTRAINT chk_usuarios_roles_validos
        CHECK (role IN ('ADMINISTRADOR', 'ATENDENTE', 'MECANICO', 'CLIENTE', 'SISTEMA'));

INSERT INTO usuarios_roles (usuario_id, role) VALUES
    ('70000000-0000-0000-0000-000000000001', 'SISTEMA');

-- Versoes anteriores geravam um UUID diferente para cada origem do webhook. A
-- origem continua preservada em usuario_username, enquanto a FK passa a apontar
-- para a identidade tecnica unica.
UPDATE historico_status_os historico
SET usuario_id = '70000000-0000-0000-0000-000000000001'
WHERE historico.usuario_username LIKE 'webhook:%'
  AND NOT EXISTS (
      SELECT 1 FROM usuarios usuario WHERE usuario.id = historico.usuario_id
  );

ALTER TABLE ordens_servico
    ADD CONSTRAINT fk_ordens_servico_clientes
        FOREIGN KEY (id_cliente) REFERENCES clientes(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_ordens_servico_veiculos
        FOREIGN KEY (id_veiculo) REFERENCES veiculos(id) ON DELETE RESTRICT;

ALTER TABLE ordens_servico_itens
    ADD CONSTRAINT fk_ordens_servico_itens_pecas_insumos
        FOREIGN KEY (peca_insumo_id) REFERENCES pecas_insumos(id) ON DELETE RESTRICT;

ALTER TABLE historico_status_os
    ADD CONSTRAINT fk_historico_status_os_usuarios
        FOREIGN KEY (usuario_id) REFERENCES usuarios(id) ON DELETE RESTRICT;

CREATE INDEX ix_ordens_servico_itens_peca_insumo
    ON ordens_servico_itens (peca_insumo_id);

CREATE INDEX ix_historico_status_os_usuario
    ON historico_status_os (usuario_id);
