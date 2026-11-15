# Executar as seeds existentes no Academy

O carregamento usa a classe `DemoData` e o catálogo `src/main/resources/demo/catalog.sql`
da imagem OS já implantada. Não mantém uma segunda cópia SQL das seeds. O catálogo
contém clientes, veículos, serviços, peças e estoque de demonstração; `DemoData` cria
demo-admin, demo-mecanico e demo-cliente. Não gera ordens, cobranças ou pagamentos.

Executar Actions → seed-demo-academy na main e confirmar `CARREGAR SEEDS DEMO`.
O workflow usa os Organization secrets AWS e as Variables do prod já configuradas.
Compartilha a mesma concurrency do deploy OS para evitar alterações simultâneas.

O utilitário cria uma única credencial aleatória no Secret Kubernetes
`workshop/fase4-demo-credentials`, preservando-a se já existir. Não imprime a senha e
não a replica nos repos GitHub. Aplica temporariamente `SPRING_PROFILES_ACTIVE=demo`
e a referência desse Secret na OS, aguarda o rollout/execução de DemoData e restaura
os dois campos originais, inclusive se o carregamento falhar. Não altera imagem,
réplicas, credenciais SQL/Rabbit/JWT, Terraform ou IAM. Mudanças manuais concorrentes
nos campos temporários causam falha, em vez de sobrescrita silenciosa.

As seeds existentes usam ON CONFLICT DO NOTHING: não rotacionam senhas de usuários já
criados. Se houver colisões/inconsistências, revisar o estado em vez de apagar dados.
O profile normal volta ao fim; as linhas carregadas continuam no banco. Guardar a
credencial do cluster para os testes de login; não cadastrar senha pública fixa.

Para validar a candidata Auth, usar o documento fictício já versionado do cliente
vinculado a `demo-cliente` (ID 60000000-0000-0000-0000-000000000003), configurar TEST_CPF
no prod do Auth e executar action=validate com a versão numerada. A validação precisa
concluir contra a OS real antes de ativar prod/rotas. Não usar CPF pessoal nem considerar
um teste de CPF inválido como prova de login bem-sucedido.
