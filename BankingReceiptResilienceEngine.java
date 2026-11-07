import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class BankingReceiptResilienceEngine {

    // Status possíveis do ciclo de vida do comprovante
    enum ReceiptStatus {
        PENDING,
        PROCESSING,
        RETRY_SCHEDULED,
        ISSUED,
        FAILED_DLQ // Dead Letter Queue (Fila de contingência manual)
    }

    // Modelo da Transação e Comprovante
    static class TransactionReceipt {
        String transactionId;
        String endToEndId; // Padrão BACEN Pix (E2E)
        String payerBank;
        String payeeName;
        double amount;
        ReceiptStatus status;
        int retryCount;
        String receiptHash;
        LocalDateTime createdAt;
        LocalDateTime issuedAt;

        public TransactionReceipt(String payerBank, String payeeName, double amount) {
            this.transactionId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            this.endToEndId = "E" + System.currentTimeMillis() + "BR" + (new Random().nextInt(9000) + 1000);
            this.payerBank = payerBank;
            this.payeeName = payeeName;
            this.amount = amount;
            this.status = ReceiptStatus.PENDING;
            this.retryCount = 0;
            this.createdAt = LocalDateTime.now();
        }
    }

    // Log de Auditoria para rastreabilidade (espelha a tabela SQL)
    static class AuditLog {
        String timestamp;
        String transactionId;
        String event;
        String details;

        public AuditLog(String transactionId, String event, String details) {
            this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
            this.transactionId = transactionId;
            this.event = event;
            this.details = details;
        }

        @Override
        public String toString() {
            return String.format("[%s] TX:%s | %-18s | %s", timestamp, transactionId, event, details);
        }
    }

    private static final int MAX_RETRIES = 3;
    private static final List<AuditLog> auditLogs = new ArrayList<>();
    private static final Random random = new Random();

    // Simula o serviço externo de geração de PDF/Comprovante que sofre instabilidade
    private static boolean externalReceiptServiceCall(int attempt) throws Exception {
        // Simula 65% de chance de falha na primeira tentativa para demonstrar resiliência
        if (attempt == 1 && random.nextInt(100) < 65) {
            throw new Exception("Timeout na API de Extrato/Comprovante (HTTP 504)");
        }
        // Simula 30% de chance de falha na segunda tentativa
        if (attempt == 2 && random.nextInt(100) < 30) {
            throw new Exception("Fila de mensageria congestionada (Lag detectado)");
        }
        return true;
    }

    // Motor principal de Resiliência com Retry + Backoff Exponencial
    public static void processWithResilience(TransactionReceipt receipt) {
        log(receipt.transactionId, "TX_RECEIVED",
                String.format("Pix R$ %.2f -> %s (%s)", receipt.amount, receipt.payeeName, receipt.payerBank));

        while (receipt.retryCount < MAX_RETRIES) {
            receipt.retryCount++;
            receipt.status = ReceiptStatus.PROCESSING;

            try {
                log(receipt.transactionId, "GENERATING_RECEIPT", "Tentativa " + receipt.retryCount + " de " + MAX_RETRIES);
                
                boolean success = externalReceiptServiceCall(receipt.retryCount);

                if (success) {
                    receipt.status = ReceiptStatus.ISSUED;
                    receipt.issuedAt = LocalDateTime.now();
                    receipt.receiptHash = UUID.nameUUIDFromBytes(receipt.endToEndId.getBytes()).toString();
                    
                    log(receipt.transactionId, "RECEIPT_ISSUED",
                            "Comprovante garantido! E2E: " + receipt.endToEndId + " | Hash: " + receipt.receiptHash.substring(0, 12));
                    return;
                }
            } catch (Exception e) {
                receipt.status = ReceiptStatus.RETRY_SCHEDULED;
                log(receipt.transactionId, "INSTABILITY_CAUGHT", e.getMessage());

                if (receipt.retryCount < MAX_RETRIES) {
                    long backoffMs = (long) Math.pow(2, receipt.retryCount) * 300; // Backoff exponencial
                    log(receipt.transactionId, "BACKOFF_WAIT", "Aguardando " + backoffMs + "ms antes de reprocessar...");
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        // Fallback caso esgote todas as tentativas sem perder o registro da transação
        receipt.status = ReceiptStatus.FAILED_DLQ;
        log(receipt.transactionId, "SENT_TO_DLQ", "Encaminhado para contingência assíncrona. Saldo protegido.");
    }

    private static void log(String txId, String event, String details) {
        AuditLog entry = new AuditLog(txId, event, details);
        auditLogs.add(entry);
        System.out.println(entry);
    }

    public static void main(String[] args) {
        System.out.println("=== INICIANDO MOTOR DE RESILIÊNCIA DE COMPROVANTES BANCÁRIOS ===\n");

        List<TransactionReceipt> batch = List.of(
            new TransactionReceipt("Itaú Unibanco", "Mercado Livre Pagamentos", 249.90),
            new TransactionReceipt("Nubank", "Energia Enel SP", 184.50),
            new TransactionReceipt("Santander", "Transferência João Silva", 1200.00)
        );

        for (TransactionReceipt tx : batch) {
            processWithResilience(tx);
            System.out.println("------------------------------------------------------------------");
        }

        System.out.println("\n=== RESUMO FINAL DE CONSISTÊNCIA ===");
        for (TransactionReceipt tx : batch) {
            System.out.printf("TX %s | Banco: %-14s | Status: %-10s | Tentativas: %d%n",
                    tx.transactionId, tx.payerBank, tx.status, tx.retryCount);
        }
    }
}
