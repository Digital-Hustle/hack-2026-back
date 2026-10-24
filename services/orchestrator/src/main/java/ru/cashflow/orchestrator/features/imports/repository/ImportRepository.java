package ru.cashflow.orchestrator.features.imports.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.ImportRecord;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.ImportOptions;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.ValidationMode;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.ImportStatus;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedBalance;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedImport;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedIssue;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedPayment;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;

@Repository
public class ImportRepository {

    private static final String COLUMNS = "id, company_id, account_id, status, idempotency_key, request_hash, "
            + "input_fingerprint, schema_version, rules_version, preprocessor_status, rows_staged, rows_inserted, "
            + "rows_already_present, issue_count, created_by, created_at, finalized_by, finalized_at, version, "
            + "file_name, file_sha256, validation_mode, completeness, declared_from_date, declared_to_date, "
            + "total_rows, rejected_rows, duplicate_rows, warning_count, data_version";

    private final JdbcClient jdbc;

    public ImportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ImportRecord> findByKey(CompanyScope scope, String key) {
        return jdbc.sql("select " + COLUMNS + " from imports where company_id = :company and idempotency_key = :key")
                .param("company", scope.companyId()).param("key", key).query(ImportRepository::record).optional();
    }

    public Optional<ImportRecord> find(CompanyScope scope, UUID accountId, UUID importId) {
        return jdbc.sql("select " + COLUMNS + " from imports where id = :id and company_id = :company "
                        + "and account_id = :account")
                .param("id", importId).param("company", scope.companyId()).param("account", accountId)
                .query(ImportRepository::record).optional();
    }

    public Optional<ImportRecord> lock(CompanyScope scope, UUID accountId, UUID importId) {
        return jdbc.sql("select " + COLUMNS + " from imports where id = :id and company_id = :company "
                        + "and account_id = :account for update")
                .param("id", importId).param("company", scope.companyId()).param("account", accountId)
                .query(ImportRepository::record).optional();
    }

    public List<ImportRecord> list(CompanyScope scope, UUID accountId) {
        return jdbc.sql("select " + COLUMNS + " from imports where company_id = :company and account_id = :account "
                        + "order by created_at desc, id limit 200")
                .param("company", scope.companyId()).param("account", accountId)
                .query(ImportRepository::record).list();
    }

    public void insert(CompanyScope scope, UUID id, UUID accountId, String key, String requestHash,
                       StagedImport staged, ImportOptions options, UUID userId, Instant now) {
        jdbc.sql("insert into imports (" + COLUMNS + ") values (:id, :company, :account, 'STAGED', :key, :hash, "
                        + " :fingerprint, :schema, :rules, :pp, :rows, null, null, :issues, :user, :now, null, null, 0, "
                        + ":fileName, :fileSha, :mode, :completeness, :fromDate, :toDate, :totalRows, "
                        + ":rejectedRows, :duplicateRows, :warningCount, null)")
                .param("id", id).param("company", scope.companyId()).param("account", accountId).param("key", key)
                .param("hash", requestHash).param("fingerprint", staged.inputFingerprint())
                .param("schema", staged.schemaVersion()).param("rules", staged.rulesVersion())
                .param("pp", staged.preprocessorStatus()).param("rows", staged.payments().size())
                .param("issues", staged.issueCount()).param("user", userId).param("now", Timestamp.from(now))
                .param("fileName", options.fileName()).param("fileSha", options.fileSha256())
                .param("mode", options.validationMode().name()).param("completeness", options.completeness().name())
                .param("fromDate", options.declaredFromDate()).param("toDate", options.declaredToDate())
                .param("totalRows", options.rowsTotal()).param("rejectedRows", options.rowsRejected())
                .param("duplicateRows", options.rowsDuplicate()).param("warningCount", options.warningCount())
                .update();
        for (StagedPayment p : staged.payments()) {
            jdbc.sql("insert into staged_payments (import_id, row_no, external_operation_id, source_row, "
                            + "operation_date, amount, direction, counterparty_inn, counterparty_name, purpose, "
                            + "category_code, category_source, rule_version, balance_eod) values (:import, :row, "
                            + ":external, :source, :date, :amount, :direction, :inn, :name, :purpose, :category, "
                            + ":cs, :rule, :eod)")
                    .param("import", id).param("row", p.rowNo()).param("external", p.externalOperationId())
                    .param("source", p.sourceRow()).param("date", p.operationDate()).param("amount", p.amount())
                    .param("direction", p.direction().name()).param("inn", p.counterpartyInn())
                    .param("name", p.counterpartyName()).param("purpose", p.purpose())
                    .param("category", p.categoryCode()).param("cs", p.categorySource().name())
                    .param("rule", p.ruleVersion()).param("eod", p.balanceEod()).update();
        }
        for (StagedBalance b : staged.balances()) {
            jdbc.sql("insert into staged_balances (import_id, date, balance) values (:import, :date, :balance)")
                    .param("import", id).param("date", b.date()).param("balance", b.balance()).update();
        }
        for (StagedIssue i : staged.issues()) {
            jdbc.sql("insert into import_issues (import_id, seq, code, severity, source_line, field, message) "
                            + "values (:import, :seq, :code, :severity, :line, :field, :message)")
                    .param("import", id).param("seq", i.seq()).param("code", i.code()).param("severity", i.severity())
                    .param("line", i.sourceLine()).param("field", i.field()).param("message", i.message()).update();
        }
    }

    public List<StagedPayment> stagedPayments(UUID importId) {
        return jdbc.sql("select row_no, external_operation_id, source_row, operation_date, amount, direction, "
                        + "counterparty_inn, counterparty_name, purpose, category_code, category_source, "
                        + "rule_version, balance_eod from staged_payments where import_id = :import order by row_no")
                .param("import", importId)
                .query((rs, n) -> {
                    int source = rs.getInt("source_row");
                    return new StagedPayment(rs.getInt("row_no"), rs.getString("external_operation_id"),
                            rs.wasNull() ? null : source, rs.getObject("operation_date", LocalDate.class),
                            rs.getBigDecimal("amount"), Direction.valueOf(rs.getString("direction")),
                            rs.getString("counterparty_inn"), rs.getString("counterparty_name"),
                            rs.getString("purpose"), rs.getString("category_code"),
                            CategorySource.valueOf(rs.getString("category_source")), rs.getString("rule_version"),
                            rs.getBigDecimal("balance_eod"));
                }).list();
    }

    public List<StagedBalance> stagedBalances(UUID importId) {
        return jdbc.sql("select date, balance from staged_balances where import_id = :import order by date")
                .param("import", importId)
                .query((rs, n) -> new StagedBalance(rs.getObject("date", LocalDate.class), rs.getBigDecimal("balance")))
                .list();
    }

    public List<StagedIssue> issues(UUID importId) {
        return jdbc.sql("select seq, code, severity, source_line, field, message from import_issues "
                        + "where import_id = :import order by seq")
                .param("import", importId)
                .query((rs, n) -> {
                    long line = rs.getLong("source_line");
                    return new StagedIssue(rs.getInt("seq"), rs.getString("code"), rs.getString("severity"),
                            rs.wasNull() ? null : line, rs.getString("field"), rs.getString("message"));
                }).list();
    }

    public void markFinalized(UUID importId, int inserted, int present, Long dataVersion, UUID userId, Instant now) {
        jdbc.sql("update imports set status = 'FINALIZED', rows_inserted = :inserted, rows_already_present = :present, "
                        + "finalized_by = :user, finalized_at = :now, data_version = :dataVersion, "
                        + "version = version + 1 where id = :id")
                .param("inserted", inserted).param("present", present).param("user", userId)
                .param("now", Timestamp.from(now)).param("dataVersion", dataVersion).param("id", importId).update();
    }

    public void markCancelled(UUID importId, UUID userId, Instant now) {
        jdbc.sql("update imports set status = 'CANCELLED', finalized_by = :user, finalized_at = :now, "
                        + "version = version + 1 where id = :id")
                .param("user", userId).param("now", Timestamp.from(now)).param("id", importId).update();
    }

    private static ImportRecord record(ResultSet rs, int row) throws SQLException {
        int inserted = rs.getInt("rows_inserted");
        Integer insertedValue = rs.wasNull() ? null : inserted;
        int present = rs.getInt("rows_already_present");
        Integer presentValue = rs.wasNull() ? null : present;
        Timestamp finalized = rs.getTimestamp("finalized_at");
        return new ImportRecord(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("account_id", UUID.class), ImportStatus.valueOf(rs.getString("status")),
                rs.getString("idempotency_key"), rs.getString("request_hash"), rs.getString("input_fingerprint"),
                rs.getString("schema_version"), rs.getString("rules_version"), rs.getString("preprocessor_status"),
                rs.getInt("rows_staged"), insertedValue, presentValue, rs.getLong("issue_count"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getObject("finalized_by", UUID.class), finalized == null ? null : finalized.toInstant(),
                rs.getLong("version"),
                new ImportOptions(rs.getString("file_name"), rs.getString("file_sha256"),
                        ValidationMode.valueOf(rs.getString("validation_mode")),
                        Completeness.valueOf(rs.getString("completeness")),
                        rs.getObject("declared_from_date", LocalDate.class),
                        rs.getObject("declared_to_date", LocalDate.class),
                        rs.getInt("total_rows"), rs.getInt("rejected_rows"), rs.getInt("duplicate_rows"),
                        rs.getLong("warning_count")),
                rs.getObject("data_version", Long.class));
    }
}
