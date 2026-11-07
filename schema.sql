-- ============================================================================
-- BANCO DE DADOS: BANKING RECEIPT RESILIENCE ENGINE
-- Compatível com SQLite / PostgreSQL
-- ============================================================================

DROP TABLE IF EXISTS receipt_audit_logs;
DROP TABLE IF EXISTS transaction_receipts;

-- 1. Tabela Principal de Transações e Comprovantes (Idempotência garantida)
CREATE TABLE transaction_receipts (
    transaction_id VARCHAR(16) PRIMARY KEY,
    end_to_end_id VARCHAR(40) NOT NULL UNIQUE,
    payer_bank VARCHAR(60) NOT NULL,
    payee_name VARCHAR(100) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL CHECK (amount > 0),
    status VARCHAR(25) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY_SCHEDULED', 'ISSUED', 'FAILED_DLQ')),
    retry_count INT NOT NULL DEFAULT 0,
    receipt_hash VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    issued_at TIMESTAMP NULL
);

-- 2. Tabela de Logs de Auditoria e Rastreabilidade de Falhas
CREATE TABLE receipt_audit_logs (
    log_id INTEGER PRIMARY KEY AUTOINCREMENT,
    transaction_id VARCHAR(16) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    details TEXT NOT NULL,
    logged_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (transaction_id) REFERENCES transaction_receipts(transaction_id)
);

-- 3. Índices de Performance para Consultas de Contingência e Auditoria
CREATE INDEX idx_receipts_status ON transaction_receipts(status);
CREATE INDEX idx_receipts_bank ON transaction_receipts(payer_bank);
CREATE INDEX idx_audit_tx_id ON receipt_audit_logs(transaction_id);

-- ============================================================================
-- SIMULAÇÃO DE DADOS (ESPELHANDO A EXECUÇÃO DO MOTOR JAVA)
-- ============================================================================

INSERT INTO transaction_receipts (
    transaction_id, end_to_end_id, payer_bank, payee_name, amount, status, retry_count, receipt_hash, issued_at
) VALUES
    ('BC9D7544', 'E1794068858887BR4103', 'Itaú Unibanco', 'Mercado Livre Pagamentos', 249.90, 'ISSUED', 2, 'cad80653-e53', CURRENT_TIMESTAMP),
    ('7B57CB16', 'E1794068858897BR7579', 'Nubank', 'Energia Enel SP', 184.50, 'ISSUED', 1, 'be1f90e3-4f1', CURRENT_TIMESTAMP),
    ('B01CEB2F', 'E1794068858897BR7175', 'Santander', 'Transferência João Silva', 1200.00, 'ISSUED', 3, 'f40562ec-da6', CURRENT_TIMESTAMP),
    ('9F4A1120', 'E1794068859912BR8841', 'Itaú Unibanco', 'Pagamento Boleto Vivo', 310.00, 'FAILED_DLQ', 3, NULL, NULL);

INSERT INTO receipt_audit_logs (transaction_id, event_type, details) VALUES
    ('BC9D7544', 'TX_RECEIVED', 'Pix R$ 249.90 -> Mercado Livre Pagamentos (Itaú Unibanco)'),
    ('BC9D7544', 'INSTABILITY_CAUGHT', 'Timeout na API de Extrato/Comprovante (HTTP 504)'),
    ('BC9D7544', 'BACKOFF_WAIT', 'Aguardando 600ms antes de reprocessar'),
    ('BC9D7544', 'RECEIPT_ISSUED', 'Comprovante garantido na tentativa 2'),
    ('B01CEB2F', 'TX_RECEIVED', 'Pix R$ 1200.00 -> Transferência João Silva (Santander)'),
    ('B01CEB2F', 'INSTABILITY_CAUGHT', 'Timeout na API de Extrato/Comprovante (HTTP 504)'),
    ('B01CEB2F', 'INSTABILITY_CAUGHT', 'Fila de mensageria congestionada (Lag detectado)'),
    ('B01CEB2F', 'RECEIPT_ISSUED', 'Comprovante garantido na tentativa 3'),
    ('9F4A1120', 'SENT_TO_DLQ', 'Encaminhado para contingência assíncrona após 3 falhas. Saldo protegido.');

-- ============================================================================
-- CONSULTAS DE AUDITORIA E MONITORAMENTO (SLA BANCÁRIO)
-- ============================================================================

SELECT 
    payer_bank AS banco,
    COUNT(*) AS total_transacoes,
    SUM(CASE WHEN status = 'ISSUED' THEN 1 ELSE 0 END) AS comprovantes_garantidos,
    SUM(CASE WHEN status = 'FAILED_DLQ' THEN 1 ELSE 0 END) AS em_contingencia_dlq,
    ROUND(AVG(retry_count), 2) AS media_tentativas
FROM transaction_receipts
GROUP BY payer_bank;

SELECT 
    r.transaction_id,
    r.payer_bank,
    r.amount,
    r.status,
    l.event_type,
    l.details
FROM transaction_receipts r
JOIN receipt_audit_logs l ON r.transaction_id = l.transaction_id
WHERE r.retry_count > 1
ORDER BY l.log_id ASC;
