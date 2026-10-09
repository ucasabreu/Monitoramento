# Persistência e cadastro — etapa 02

Esta etapa mantém a origem sintética e torna o cadastro genérico e o processamento durável. O perfil padrão é `demo,postgres`; `demo,memory` permite executar a demonstração anterior sem banco. Java 21 e Spring Boot 4.0.8 continuam na base.

## Como executar

Na raiz, `docker compose up -d --wait postgres`. Em `API_monitor`, executar `./mvnw spring-boot:run`. O banco padrão é `jdbc:postgresql://127.0.0.1:15432/monitoramento`, com as credenciais locais de `.env.example`. O Flyway executa `V1__catalog_and_monitoring.sql` antes de usar as tabelas.

Para começar sem a carga de demonstração:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--monitoring.demo.seed-enabled=false"
```

A propriedade desativa a inserção dos cadastros iniciais; não apaga os existentes. `monitoring.demo.enabled=false` desativa o scheduler, mantendo a API de cadastro/consulta. O Compose lê `.env`; Java local usa variáveis exportadas ou argumentos Spring, conforme o README.

## Cadastro pela API

| Recurso | Operações em `/api/v1/catalog` | Comportamento |
| --- | --- | --- |
| `/sites` | GET lista, POST; GET/PUT/DELETE em `/{id}` | Unidade com nome e localização opcional. |
| `/providers` | GET lista, POST; GET/PUT/DELETE em `/{id}` | Operadora com nome e contato opcional. |
| `/circuits` | GET lista, POST; GET/PUT/DELETE em `/{id}` | Referências por UUID, papel e cenário de simulação. DELETE arquiva. |

POST retorna HTTP 201, identificador e `Location`; PUT retorna o cadastro atualizado; DELETE retorna 204. Nome vazio, tamanho inválido, enum desconhecido e UUID malformado retornam 400. Recurso inexistente retorna 404. Nomes duplicados e exclusão de unidade/operadora ainda associada a circuito retornam 409. Comparação de nomes ignora maiúsculas/minúsculas; espaços nas bordas são removidos.

Criar primeiro a unidade e a operadora:

```bash
curl -X POST http://127.0.0.1:8080/api/v1/catalog/sites \
  -H 'Content-Type: application/json' \
  -d '{"name":"Filial de demonstração","location":"Local fictício"}'

curl -X POST http://127.0.0.1:8080/api/v1/catalog/providers \
  -H 'Content-Type: application/json' \
  -d '{"name":"Operadora fictícia","contact":"Contato de demonstração"}'
```

Copiar os respectivos `id` retornados e substituir os dois campos abaixo por esses UUIDs:

```json
{
  "name": "Link principal da filial",
  "siteId": "UUID-DA-UNIDADE",
  "providerId": "UUID-DA-OPERADORA",
  "role": "PRIMARY",
  "simulationScenario": "STABLE"
}
```

Enviar esse JSON por POST a `/api/v1/catalog/circuits`, com `Content-Type: application/json`. O circuito entra automaticamente nos próximos ciclos de monitoramento, sem criar arquivo de ping. Consultar `/api/v1/circuits/{id}` e `/api/v1/circuits/{id}/measurements?limit=100` para acompanhar suas evidências. O cadastro completo é consultado em `/api/v1/catalog/circuits/{id}`.

Os papéis são `PRIMARY`, `SECONDARY` e `STANDALONE`. Ainda são metadados: a agregação de redundância será implementada na etapa 03. A origem das medições permanece `SIMULATED`; não há destino IP nem coleta real neste marco.

| Cenário | Resultado sintético |
| --- | --- |
| `STABLE` | Respostas com latência abaixo do limite inicial. |
| `ALWAYS_DOWN` | Falhas desde a primeira observação. |
| `HIGH_LATENCY` | Respostas com 180 ms. |
| `INTERMITTENT` | Falha entre os segundos 30 e 40 do roteiro. |
| `COLLECTOR_ERROR` | Erro de coleta; não comprova queda do destino. |
| `OUTAGE_CYCLE` | Falhas entre os segundos 20 e 60. |
| `INITIAL_OUTAGE` | Falhas até o segundo 40. |
| `COLLECTOR_GAP_CYCLE` | Erro de coleta entre os segundos 40 e 80. |

O roteiro tem 120 segundos e seu início é salvo em `monitoring_settings`; reiniciar a aplicação preserva sua fase. O seeder utiliza IDs estáveis e inserção sem sobrescrever registros existentes. Edições de nome, cenário, unidade ou operadora e arquivamentos são preservados no reinício.

## Dados e transações

Usamos Spring JDBC com SQL explícito, HikariCP e Flyway. A escolha facilita revisar locks, índices, conflitos e a unidade transacional, sem acoplar o motor de regras a entidades de ORM. A [documentação do Spring Boot](https://docs.spring.io/spring-boot/4.0/reference/data/sql.html) descreve a configuração de JDBC e do pool.

| Tabela | Responsabilidade |
| --- | --- |
| `sites`, `providers`, `circuits` | Cadastro genérico, referências e arquivamento. |
| `measurements` | Resultado imutável, instante de ingestão e `ACCEPTED`/`OUT_OF_ORDER`. UUID único no banco. |
| `monitor_states` | Checkpoint versionado do motor: confirmações, contadores, último resultado e incidente aberto. |
| `incidents` | Evidências persistidas, inclusive além dos 100 incidentes recentes mantidos no checkpoint. |
| `monitoring_settings` | Início persistido da simulação. |

O registro de um resultado começa com `SELECT ... FOR UPDATE` no circuito. Dentro da mesma transação, o repositório recupera o checkpoint, verifica o UUID da medição, aplica as regras e grava medição, novo checkpoint e incidentes alterados. Falha em qualquer gravação reverte tudo. Outra entrega com o mesmo UUID e mesmo conteúdo retorna `DUPLICATE`; conteúdo diferente com esse UUID gera conflito. A unicidade dura enquanto a medição permanece no banco, além da janela de 256 IDs do motor.

Resultados fora de ordem são guardados para auditoria e não alteram contadores. Callbacks do executor usam o instante de atualização do cadastro para descartar uma sonda iniciada sob configuração anterior; callbacks de circuitos arquivados também são descartados. O cadastro e o processamento usam o mesmo lock de circuito.

O resultado e o checkpoint têm representação JSONB para reidratação com os timestamps originais, incluindo nanossegundos. Colunas temporais indexadas usam `timestamptz`, cuja precisão é de microssegundos. A versão 1 do checkpoint é validada explicitamente. Evoluções do banco devem usar novas migrações; não editar uma migração já aplicada.

## Reinício, lacunas e arquivamento

O motor é reconstruído do checkpoint em cada operação, sem cache mutável que possa divergir do banco após rollback. Assim, confirmações pendentes e identificadores de incidentes sobrevivem ao reinício. Não é necessário reler o histórico inteiro para recuperar o estado.

Na consulta ou no próximo ciclo, observações confiáveis com idade de 32 segundos ou mais levam a UNKNOWN. Um incidente aberto preserva sua identidade e recebe `hasObservationGap=true`. A duração é recalculada ao consultar; uma lacuna impede afirmar downtime contínuo. Contadores não são incrementados durante o período sem medições.

DELETE de circuito arquiva e suspende o monitoramento. Histórico e incidente aberto são preservados, com lacuna sinalizada; o arquivamento não inventa uma recuperação. Listas operacionais e resumo consideram apenas circuitos ativos. `GET /api/v1/catalog/circuits?includeArchived=true` permite consultar todos. Unidade e operadora ainda referenciadas, mesmo por circuitos arquivados, não podem ser excluídas.

## Verificação e limites

`./mvnw test` executa regras e API em memória. `./mvnw verify -Pintegration-tests` acrescenta testes em PostgreSQL 17 isolado pelo Testcontainers, sem tocar no banco do Compose. Docker deve estar disponível; os testes de integração não são ignorados quando falta Docker.

As verificações cobrem cadastro HTTP e referências, recuperação em um novo contexto da aplicação, confirmação pendente, incidentes, expiração, deduplicação além de 256 resultados, consumidores concorrentes, rollback com falha injetada, arquivamento, callbacks antigos, carga inicial idempotente e acompanhamento de circuito cadastrado pelo executor real. São testes de correção, não benchmarks de capacidade.

Há um coletor ativo por banco neste marco. Locks protegem a aplicação dos resultados, mas não elegem um coletor nem impedem duas instâncias de coletarem o mesmo circuito ao mesmo tempo. A política de confirmação continua global, na configuração, sem versionamento por incidente. Não há retenção automática, outbox, reenvio durável de uma medição perdida durante indisponibilidade do banco ou histórico completo de alterações de cadastro.

Consultas de incidentes/medições são limitadas a 500 resultados por chamada; paginação por cursor é uma próxima evolução. Os snapshots mantêm 100 incidentes encerrados recentes e 24 meses de contadores, enquanto as tabelas guardam o histórico completo. Autenticação e operação pública com dados reais continuam fora da demo local desta etapa.
