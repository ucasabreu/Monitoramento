# Gestão e investigação de circuitos

Projeto de portfólio em Java/Spring Boot, motivado pela dificuldade relatada de um analista de redes em interpretar gráficos, listas de ping e outros dados de monitoramento. A evolução organiza as evidências para identificar problemas, compreender seu impacto e preparar informações para atendimento.

Os quatro pilares são investigação orientada, gestão de operadoras, visibilidade de redundância e operação simples. O backend demonstra concorrência controlada e regras explícitas de disponibilidade. A história, a auditoria do legado e o planejamento estão em [Análise e plano](docs/ANALISE_E_PLANO_MONITORAMENTO.md).

## Estado atual

O primeiro marco de backend está implementado:

- Java 21, Spring Boot 4.0.8 e Maven Wrapper 3.9.9.
- Demo com 12 circuitos sintéticos, seis unidades e três operadoras fictícias.
- Resultados imutáveis com sucesso, falha do destino e erro de execução separados.
- Confirmação de queda/recuperação, expiração para UNKNOWN, qualidade independente da disponibilidade e contadores por ano/mês/fuso.
- Execução paralela com workers/fila limitados, exclusão por circuito, prazo total e encerramento gerenciado.
- Consultas HTTP de circuitos, incidentes, resumo e métricas da execução.
- Testes de regras, concorrência, roteiro sintético e API.

Os dados ficam **em memória** e são reiniciados ao parar a aplicação. PostgreSQL está preparado no Compose, mas ainda não está conectado ao backend. Cadastro persistente, relações de redundância, exportação e nova interface são entregas seguintes. A pasta `UI_monitor` contém o protótipo antigo e ainda não representa uma demo visual funcional.

## Executar o backend

Requisito: JDK 21. O Maven Wrapper baixa a distribuição e as dependências na primeira execução; não é necessário instalar Maven separadamente.

Na raiz do repositório:

```bash
cd API_monitor
./mvnw test
./mvnw spring-boot:run
```

Em um terminal Windows, usar `mvnw.cmd` no lugar de `./mvnw`.

A API inicia em `http://127.0.0.1:8080`, no perfil `demo`. Não precisa de Docker ou banco para este primeiro marco. Para escolher outra porta:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"
```

Para consultar pelo navegador, abrir a URL completa [resumo em JSON](http://127.0.0.1:8080/api/v1/dashboard/summary) ou [circuitos em JSON](http://127.0.0.1:8080/api/v1/circuits), usando `http://` e mantendo a aplicação em execução. A raiz `http://127.0.0.1:8080/` retorna 404 porque este marco implementa somente a API, sem página inicial ou painel visual. A pasta `UI_monitor` ainda não está integrada ao novo contrato.

### Navegador no Windows e backend no WSL

Neste ambiente, o `curl` dentro do WSL respondia, mas o navegador do Windows mostrava `ERR_CONNECTION_REFUSED`. O backend estava configurado com `server.address=127.0.0.1`, aceitando conexões apenas no loopback do WSL, e o encaminhamento de `localhost` pelo Windows não funcionou nessa configuração. O acesso foi confirmado após iniciar com `server.address=0.0.0.0` e usar o IP do WSL no navegador.

Se ocorrer esse problema, parar a execução atual com `Ctrl+C` e iniciar, na pasta `API_monitor`:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.address=0.0.0.0"
```

Em outro terminal WSL, obter o endereço:

```bash
hostname -I
```

Usar o IP da interface do WSL e abrir `http://<IP-DO-WSL>:8080/api/v1/dashboard/summary` em Chrome, Edge ou Firefox no Windows, mantendo o terminal do backend aberto. Se aparecerem vários IPs, identificar o da interface `eth0` com `ip -4 -o address show dev eth0`.

No ambiente verificado, o acesso funcionou em `http://172.20.206.196:8080/api/v1/dashboard/summary`. Esse endereço é um exemplo da execução validada: o IP pode mudar ao reiniciar o WSL, portanto consultar o valor atual.

`0.0.0.0` é o endereço de escuta do servidor, não a URL a abrir no navegador. Esse modo aceita conexões pelas interfaces IPv4 do ambiente; usar para a demo local, sem alterar o padrão do projeto. O acesso de Windows a WSL e o uso de um endereço de bind apropriado estão descritos na [documentação de rede do WSL](https://learn.microsoft.com/en-us/windows/wsl/networking#connecting-via-remote-ip-addresses).

| Resultado | Significado |
| --- | --- |
| `ERR_CONNECTION_REFUSED` | A conexão com o servidor falhou antes de uma resposta HTTP. |
| HTTP 404 em `/` | O servidor respondeu, mas ainda não há página inicial nessa rota. |
| HTTP 200 em `/api/v1/dashboard/summary` | A API está acessível e retorna o resumo em JSON. |

Nas prévias integradas de editores/aplicativos, `localhost` também pode apontar para um ambiente diferente; validar primeiro no navegador do Windows com o endereço acima.

### Executar o JAR

Para construir e executar o JAR:

```bash
./mvnw package
java -jar target/monitoramento-0.0.1-SNAPSHOT.jar
```

O POM permite `-Dmonitoramento.build.directory=/tmp/monitoramento-build` para gerar artefatos fora do `target` existente. Os históricos institucionais são preservados nos fontes, mas excluídos dos recursos empacotados. A aplicação não consulta seus endereços nem escreve nesses arquivos.

O build atualiza os recursos de configuração mesmo quando a cópia antiga em `target/classes` tem uma data mais recente. Isso evita iniciar com um `application.properties` vazio ou desatualizado. O empacotamento também exclui históricos antigos já presentes nesse diretório.

## Consultar a demonstração

```bash
curl http://127.0.0.1:8080/api/v1/dashboard/summary
curl http://127.0.0.1:8080/api/v1/circuits
curl http://127.0.0.1:8080/api/v1/incidents
curl http://127.0.0.1:8080/api/v1/monitoring/execution
```

| Endpoint | Resposta |
| --- | --- |
| `GET /api/v1/circuits` | Snapshots com disponibilidade, qualidade, idade, contadores e evidências. |
| `GET /api/v1/circuits/{id}` | Detalhe pelo UUID retornado na listagem; 404 quando inexistente. |
| `GET /api/v1/incidents` | Incidentes abertos e histórico recente da sessão. |
| `GET /api/v1/dashboard/summary` | Totais separados, `source: SIMULATED` e `persisted: false`. |
| `GET /api/v1/monitoring/execution` | Workers, fila, execuções, sobreposições evitadas, rejeições, timeouts e erros de processamento. |

`/hostdata` é um alias temporário para a **nova estrutura de snapshots**. Não preserva o envelope JSON antigo e não lê `hostdata.json` do classpath. A nova UI deve usar `/api/v1`.

O roteiro se repete a cada 120 segundos, contados desde a inicialização:

| Situação | Roteiro sintético |
| --- | --- |
| Centro | Dois links falham entre os segundos 20 e 60. |
| Norte | Link secundário começa sem resposta e recupera a partir do segundo 40; principal responde. |
| Sul | Coletor do link principal retorna erro entre os segundos 40 e 80. |
| Leste | Link principal responde com latência de 180 ms, acima do limite inicial. |
| Oeste | Uma falha isolada do link principal entre os segundos 30 e 40. |
| Anexo | Link principal começa e permanece sem resposta. |

Os estados dependem das confirmações: inicialmente UNKNOWN, três falhas para DOWN e duas respostas para UP. A existência de dois links cadastrados não comprova failover nem caminho ativo; a visão agregada de redundância ainda será implementada.

## Configuração

As propriedades ficam em `API_monitor/src/main/resources/application.properties` e podem ser sobrescritas pelo Spring via argumentos ou variáveis de ambiente.

| Propriedade | Padrão |
| --- | --- |
| `server.address` | `127.0.0.1`; usar `0.0.0.0` por argumento para acesso pelo IP do WSL |
| `server.port` | `8080`, ou o valor da variável `PORT` |
| `monitoring.demo.enabled` | `true` |
| `monitoring.demo.interval` | `10s` |
| `monitoring.failure-threshold` | `3` |
| `monitoring.recovery-threshold` | `2` |
| `monitoring.observation-ttl` | `32s` desde a última observação confiável |
| `monitoring.degraded-latency-ms` | `100` |
| `monitoring.reporting-zone` | `America/Sao_Paulo` |
| `monitoring.workers` | `4` |
| `monitoring.queue-capacity` | `16` |
| `monitoring.probe-timeout` | `2s`, incluindo espera na fila |
| `monitoring.shutdown-timeout` | `2s` |

Erros de execução não entram na taxa de falha do destino; rejeições de capacidade não inventam uma medição. Sem observações confiáveis o estado expira. Um timeout interrompe a tarefa e descarta sua resposta tardia, mas não libera a exclusão até a tarefa realmente encerrar. Uma implementação que ignora interrupção permanece ocupando sua capacidade até sair; o Java não força sua terminação.

`elapsedSeconds` indica o intervalo do incidente, não downtime comprovado em caso de lacuna. `hasObservationGap` sinaliza essa limitação. Primeira evidência e confirmação são armazenadas separadamente; uma primeira observação DOWN não inventa uma queda anterior a partir de UP.

A memória é limitada a 100 incidentes concluídos, 256 IDs recentes para deduplicação e 24 meses de contadores por circuito. Resultados antigos também são rejeitados por ordem temporal. Persistência, retenção operacional e idempotência durável entram com PostgreSQL; este marco não é uma solução completa de histórico/SLA.

## PostgreSQL com Docker Compose

Executar na raiz do repositório, onde está `compose.yaml`:

```bash
docker compose up -d --wait postgres
docker compose ps
```

O serviço usa PostgreSQL 17 Alpine, healthcheck e volume persistente. A porta local padrão é `127.0.0.1:15432`; dentro do container o PostgreSQL usa 5432. `.env.example` documenta as configurações locais; os padrões permitem iniciar sem criar `.env`. Para outra porta:

```bash
POSTGRES_PORT=25432 docker compose up -d --wait postgres
```

Para parar mantendo os dados:

```bash
docker compose stop postgres
```

Na entrega de persistência, o backend será conectado a esse banco. Durante o desenvolvimento, Java e frontend podem executar localmente; o Compose completo da demo será preparado após a interface. A tag fixa a versão principal 17 e pode receber patches; uma futura publicação poderá fixar também o digest da imagem validada.

## Evidências e próximos marcos

Os testes controlam o tempo nas regras e usam sincronização nas verificações de concorrência. Incluem resposta zero/submilissegundo, primeira falha, confirmação, recuperação, lacunas, resultado atrasado/duplicado, virada de mês/ano/fuso, isolamento entre circuitos, sobrecarga, timeout com sonda que ignora interrupção e shutdown.

O código antigo (`Monitor`, `HostController`, `PingAndWriteService` e `MonitoramentoServer`) foi preservado para referência e migração. Ele não participa da execução Spring da demo nova. A entrada do backend é `MonitoramentoApplication`.

Próximo marco: PostgreSQL/Flyway, cadastro genérico persistente e regras de redundância. Depois, a interface apresentará os quatro pilares e seus dados de forma clara. Integração com Zabbix e SNMP permanecem extensões.

## Desenvolvimento por branches

A `main` registra a base validada e recebe as etapas concluídas por pull request. A tag `marco-01-base-monitoramento` identifica o primeiro marco: backend com demo sintética, regras e concorrência controlada. A próxima etapa começa em `feat/02-persistencia-cadastro`; as demais branches serão criadas quando seu trabalho começar, a partir da `main` atualizada.

| Branch de etapa | Escopo | Evidência para concluir |
| --- | --- | --- |
| `feat/02-persistencia-cadastro` | PostgreSQL, Flyway e cadastro de unidades, operadoras e circuitos | Medições, estado e incidentes sobrevivem ao reinício; duplicação não repete efeitos persistidos. |
| `feat/03-redundancia-operadoras` | Disponibilidade da unidade, perda de redundância e resumo para operadora | Diferenciar queda de um link e indisponibilidade da unidade, com evidências e horários consistentes. |
| `feat/04-dashboard` | Interface dinâmica com resumo, problemas prioritários e investigação | Apresentar os quatro pilares em fluxos compreensíveis, incluindo origem e idade dos dados. |
| `feat/05-coleta-demo` | Sondas TCP/HTTP locais, Compose completo e pacote de apresentação | Demonstrar coleta controlada, limites de execução, inicialização reproduzível e roteiro completo. |

As branches dividem as entregas do [plano](docs/ANALISE_E_PLANO_MONITORAMENTO.md#15-plano-de-implementação-por-entregas) em marcos de implementação; criar uma branch não significa que sua funcionalidade já está pronta. Correções específicas usam `fix/<descricao>` e podem partir da etapa em andamento.

Para começar uma etapa, com as alterações locais já registradas:

```bash
git switch main
git pull --ff-only origin main
git switch -c feat/02-persistencia-cadastro
git push -u origin feat/02-persistencia-cadastro
```

Se a branch já existir no remoto, usar `git switch feat/02-persistencia-cadastro`; em um clone novo, o Git configura o acompanhamento da branch remota. Fazer commits por mudança coerente e abrir um pull request para `main` ao concluir a etapa, descrevendo comportamento, validação e limites. Antes da integração, executar `./mvnw verify` na pasta `API_monitor` e os checks da interface quando ela existir. Após integrar, criar a próxima branch a partir da `main` atualizada.

Artefatos gerados em `target`, dependências instaladas e arquivos `.env` ficam locais e são ignorados pelo Git. Os históricos antigos nos fontes continuam preservados como referência; a demo utiliza somente dados sintéticos.
