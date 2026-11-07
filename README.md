# Banking Receipt Resilience Engine

Motor de engenharia de dados e resiliência transacional desenvolvido para garantir a emissão, rastreabilidade e consistência de comprovantes Pix mesmo sob cenários de instabilidade ou lentidão nas APIs bancárias.

## Demonstração ao Vivo
Acesse o simulador interativo publicado via GitHub Pages:
[Visualizar Painel de Resiliência Bancária](https://github.io)

## Problema Resolvido
Em horários de pico ou falhas de rede (como erros `HTTP 504 Timeout` ou congestionamento de filas), o cliente realiza uma transferência Pix com sucesso, mas o serviço de geração do comprovante falha. Este motor impede a perda do registro da transação e garante a entrega posterior sem duplicidade.

## Arquitetura e Fluxo de Resiliência
1. **Recepção Transacional (`TX_RECEIVED`):** Registro imediato da operação com identificador único (`transactionId`) e padrão BACEN (`EndToEndId`).
2. **Retry com Backoff Exponencial (`RETRY_SCHEDULED`):** Em caso de instabilidade na API externa, o motor aguarda intervalos progressivos (`700ms` / backoff exponencial) antes de reprocessar automaticamente (até 3 tentativas).
3. **Garantia Criptográfica (`RECEIPT_ISSUED`):** Após a emissão bem-sucedida, gera um hash de verificação vinculado ao `EndToEndId`.
4. **Contingência Assíncrona (`FAILED_DLQ`):** Caso todas as tentativas falhem, a transação é encaminhada para uma *Dead Letter Queue* (DLQ) para reprocessamento assíncrono, mantendo o saldo e o histórico protegidos.
5. **Trilha de Auditoria (`AuditLog` / SQL):** Todas as transições de estado são gravadas cronologicamente para rastreabilidade e governança de dados.

## Estrutura do Projeto
* `BankingReceiptResilienceEngine.java` — Motor principal em Java com máquina de estados, simulação de falhas HTTP 504, backoff exponencial e logs de auditoria.
* `schema.sql` — Estrutura relacional das tabelas de transações, comprovantes e logs de auditoria para persistência e análise de dados.
* `index.html` — Interface visual responsiva (padrão bancário) para simulação em tempo real do ciclo de vida dos comprovantes e monitoramento da fila DLQ.

## Tecnologias Utilizadas
* **Back-end & Lógica:** Java (`UUID`, `LocalDateTime`, Collections, tratamento de exceções e tolerância a falhas)
* **Banco de Dados:** SQL (Modelagem relacional de transações e trilha de auditoria)
* **Front-end & Visualização:** HTML5, CSS3 e JavaScript (Simulador em tempo real)
