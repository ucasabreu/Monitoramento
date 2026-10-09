# Decisões do primeiro marco de backend

## Origem do problema e recorte

A evolução preserva a motivação relatada: um analista precisava compreender dados de monitoramento com menos trabalho de interpretação. O portfólio apresenta backend, concorrência e regras de monitoramento por meio de uma demo independente da rede institucional. A interface posterior terá investigação, operadoras, redundância e operação simples como quatro fluxos centrais.

## Migração da plataforma

O build original Java 17/Spring Boot 3.1.2 foi verificado no JDK 21. A migração passou pelo Spring Boot 3.5.16 antes de chegar ao 4.0.8, com teste de contexto em cada etapa. Maven Wrapper fixa 3.9.9 e o projeto passa a declarar Java 21.

Spring Boot 4 utiliza o starter Web MVC e Jackson 3 no HTTP. A dependência Jackson 2 continua apenas para compilar o `Monitor` legado; ela não é a fonte dos snapshots novos. Starters SOAP e DevTools sem uso foram removidos. Recursos institucionais não entram no JAR novo. As fontes originais e alterações preexistentes em IDE/artefatos compilados foram preservadas.

## Configuração em um diretório de build existente

Na primeira execução do usuário, o `application.properties` em `target/classes` estava vazio e tinha timestamp posterior ao fonte correto. A restauração dos artefatos antigos ao concluir a preparação produziu essa situação. O goal `process-resources` informou sucesso, mas manteve essa cópia antiga; o binding validado de `MonitoringProperties` então recebeu valores nulos e zero e impediu a inicialização. A verificação anterior num diretório novo não reproduzia esse estado.

O `maven-resources-plugin` 3.3.1 herdado do parent agora usa `overwrite=true`, substituindo a cópia gerada independentemente da data. O parâmetro é descrito na [documentação do Maven Resources Plugin](https://maven.apache.org/plugins/maven-resources-plugin/resources-mojo.html#overwrite). A validação das propriedades continua ativa; não foram introduzidos valores de fallback para esconder configuração ausente.

Os históricos também são excluídos pelo `maven-jar-plugin`: a exclusão apenas na cópia de recursos não remove arquivos que já estavam no diretório de classes. Assim, builds incrementais não reintroduzem os históricos no JAR. A configuração gerada corrigida fica no `target`; não se restaura o arquivo vazio depois da validação.

## Resultado e estado separados

`ProbeResult` é imutável e distingue SUCCESS, FAILURE e ERROR. A latência de sucesso pode ser zero ou fracionária; falha/erro têm latência ausente. Uma falha do executor não comprova queda do destino.

`CircuitMonitor` mantém disponibilidade e qualidade separadas, confirma transições e publica snapshots imutáveis sob exclusão por circuito. Os serviços HTTP não leem objetos enquanto múltiplas threads alteram seus campos. O mês é calculado por ano/mês no fuso configurado. O tempo entra explicitamente nas regras, com `Clock` no serviço, permitindo testes sem esperar minutos.

O incidente guarda primeira evidência e confirmação. Lacunas mantêm sua identidade e sinalizam observabilidade incompleta; não provam continuidade do downtime. Sucesso após uma lacuna também precisa de confirmação. Um estado inicial indisponível não incrementa uma queda UP → DOWN que não foi observada.

## Execução limitada e cancelamento

Um pool fixo executa sondas e uma fila limitada controla a espera. A sonda sintética possui uma identidade por circuito; a exclusão poderá ser refinada por `ProbeDefinition` quando um circuito tiver várias sondas. Rejeições e sobreposições são contadas, sem executar a tarefa na thread do chamador nem criar falhas fictícias do destino.

O prazo total é medido pelo agendador de deadlines, incluindo espera na fila. Um resultado é entregue uma única vez. O timeout solicita interrupção e descarta resposta tardia; a exclusão de uma tarefa já iniciada só é liberada no seu encerramento real. A interrupção é sincronizada com a saída do worker para não alcançar a próxima tarefa que reutiliza essa thread.

Shutdown recusa novas submissões, cancela tarefas pendentes, solicita interrupção e aguarda um prazo limitado. Uma implementação que ignore interrupção não pode ser terminada à força com segurança; mantém a capacidade ocupada até encerrar. O pool permanece limitado e esse caso é testado explicitamente.

## Demo em memória e evolução

Os 12 circuitos usam nomes e operadoras fictícios; a simulação não consulta arquivos, DNS ou destinos históricos. O roteiro combina indisponibilidade, recuperação, erro do coletor, qualidade degradada e falha isolada. HTTP expõe os resultados para a futura UI. `/hostdata` deixa de publicar o JSON histórico e passa a ser alias do contrato novo.

A retenção em memória é limitada; dados somem no reinício. Identificação de redundância, cadastro persistente, políticas duráveis e relatórios completos estão nas próximas entregas. Docker Compose prepara PostgreSQL, ainda sem conexão da aplicação neste marco. A primeira versão não precisa do banco para executar as regras e os testes.

## Evidências

JUnit verifica regras temporais e sequências de resultados. Latches coordenam sondas simultâneas para verificar isolamento, limite, exclusão, timeout e shutdown. O roteiro é testado com relógio controlado; MockMvc verifica contratos, erros HTTP e o perfil de teste sem coleta automática. Build, execução do JAR e validação do Compose completam a verificação; números de capacidade de produção não são inferidos desses testes.
