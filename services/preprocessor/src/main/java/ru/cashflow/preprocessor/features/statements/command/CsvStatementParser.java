package ru.cashflow.preprocessor.features.statements.command;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.Map;
import java.util.Set;

import ru.cashflow.preprocessor.config.ParseLimits;
import ru.cashflow.preprocessor.features.statements.model.CompanyInfo;
import ru.cashflow.preprocessor.features.statements.model.Direction;
import ru.cashflow.preprocessor.features.statements.model.ParseReport;
import ru.cashflow.preprocessor.features.statements.model.ParsedOperation;
import ru.cashflow.preprocessor.features.statements.model.StatementResult;

public final class CsvStatementParser {

    private static final List<String> STATEMENT_COLUMNS = List.of("company_id", "operation_id", "operation_date",
            "amount", "direction", "counterparty_inn", "counterparty_name", "payment_purpose", "balance_eod");
    private static final List<String> COMPANY_COLUMNS = List.of("company_id", "company_name", "company_inn", "legal_form",
            "industry", "first_operation_date", "last_operation_date", "operations_count");
    private final Clock clock;
    private final ParseLimits limits;
    private final RuleCategorizer categorizer;

    public CsvStatementParser(Clock clock, ParseLimits limits, RuleCategorizer categorizer) {
        this.clock = clock;
        this.limits = limits;
        this.categorizer = categorizer;
    }

    public StatementResult parse(InputStream statements, InputStream companies) {
        return parse(statements, "statements.csv", companies, "companies.csv");
    }

    public StatementResult parse(InputStream statements, String statementFilename, InputStream companies,
                                 String companyFilename) {
        var preparedAt = clock.instant();
        LocalDate today = LocalDate.now(clock);
        long deadline = System.nanoTime() + limits.timeoutSeconds() * 1_000_000_000L;
        var issues = new IssueCollector(limits.maxIssues());
        MessageDigest digest = sha256();
        digest.update("COMPANIES\0".getBytes(StandardCharsets.UTF_8));
        var metadata = companies == null ? Map.<String, CompanyInfo>of()
                : readCompanies(companies, companyFilename, issues, deadline, digest, today);
        digest.update("\0STATEMENTS\0".getBytes(StandardCharsets.UTF_8));
        var state = new ParseState(issues);
        readRows(statements, statementFilename, "STATEMENTS", STATEMENT_COLUMNS, limits.statementBytes(),
                deadline, digest,
                row -> {
                    state.total++;
                    try {
                        ParsedOperation operation = operation(row, today);
                        if (companies != null && !metadata.containsKey(operation.companyId())) {
                            issues.add("UNKNOWN_COMPANY", "ERROR", row.source(), row.record(), row.line(), "company_id",
                                    "Компания не найдена среди корректных записей справочника");
                            return;
                        }
                        state.accept(operation);
                    } catch (RowValues.InvalidRow invalid) {
                        issues.add("INVALID_FIELD", "ERROR", row.source(), row.record(), row.line(), invalid.field(), invalid.getMessage());
                    }
                });
        if (state.total == 0) {
            issues.add("NO_OPERATIONS", "ERROR", "STATEMENTS", 0, 1, null, "В файле нет операций после заголовков");
        }
        List<ParsedOperation> operations = List.copyOf(state.accepted.values());
        List<CompanyInfo> companyInfo = new ArrayList<>();
        Set<String> usedCompanies = new HashSet<>();
        for (ParsedOperation operation : operations) {
            optionalIssues(operation, issues);
            usedCompanies.add(operation.companyId());
        }
        if (companies == null) {
            usedCompanies.stream().sorted().forEach(id -> companyInfo.add(new CompanyInfo(id, null, null, null, null,
                    null, null, null, Map.of())));
        } else {
            metadata.values().stream().sorted(java.util.Comparator.comparing(CompanyInfo::companyId)).forEach(companyInfo::add);
        }
        var balances = new DailyBalanceBuilder().build(operations, issues);
        int rejected = state.total - operations.size() - state.duplicates;
        String status = operations.isEmpty() ? "INVALID"
                : rejected > 0 || issues.counts().containsKey("INVALID_COMPANY") ? "PARTIAL" : "PREPARED";
        var report = new ParseReport(status, state.total, operations.size(), rejected, state.duplicates,
                (int) operations.stream().filter(o -> o.category().needsReview()).count(), issues.total(),
                issues.warningTotal(), issues.truncated(),
                issues.counts(), issues.issues());
        return new StatementResult("1", RuleCategorizer.RULES_VERSION, HexFormat.of().formatHex(digest.digest()), preparedAt,
                List.copyOf(companyInfo), operations, balances, report);
    }

    private void readRows(InputStream stream, String filename, String source, List<String> columns, long bytesLimit,
                          long deadline, MessageDigest digest, Consumer<CsvRows.Row> consumer) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) {
            CsvRows.read(stream, source, columns, bytesLimit, limits, deadline, digest, consumer);
        } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            ExcelRows.read(stream, filename, source, columns, bytesLimit, limits, deadline, digest, consumer);
        } else {
            throw new ru.cashflow.preprocessor.features.statements.model.CsvInputException(
                    "UNSUPPORTED_FILE", 415, "Поддерживаются CSV, XLSX и XLS", 0);
        }
    }

    private Map<String, CompanyInfo> readCompanies(InputStream companies, String filename, IssueCollector issues,
                                                  long deadline, MessageDigest digest, LocalDate today) {
        Map<String, CompanyInfo> metadata = new LinkedHashMap<>();
        Set<String> conflicts = new HashSet<>();
        readRows(companies, filename, "COMPANIES", COMPANY_COLUMNS, limits.companyBytes(),
                deadline, digest, row -> {
            try {
                String id = RowValues.id(row, "company_id");
                String form = RowValues.text(row, "legal_form", 50, false);
                if (!form.isEmpty() && !Set.of("ООО", "ИП").contains(form)) {
                    throw RowValues.invalid("legal_form", "В данном формате поддерживаются ООО и ИП");
                }
                LocalDate first = RowValues.date(row, "first_operation_date", today, false);
                LocalDate last = RowValues.date(row, "last_operation_date", today, false);
                if (first != null && last != null && first.isAfter(last)) {
                    throw RowValues.invalid("first_operation_date", "Начало истории позже её окончания");
                }
                var company = new CompanyInfo(id, RowValues.text(row, "company_name", 500, true),
                        RowValues.inn(row, "company_inn"), form, RowValues.text(row, "industry", 200, false), first, last,
                        RowValues.count(row, "operations_count"), row.fields());
                if (conflicts.contains(id)) {
                    throw RowValues.invalid("company_id", "Идентификатор компании имеет конфликтующие записи");
                }
                CompanyInfo previous = metadata.putIfAbsent(id, company);
                if (previous != null) {
                    if (previous.equals(company)) {
                        issues.add("DUPLICATE_COMPANY", "WARNING", row.source(), row.record(), row.line(), "company_id",
                                "Повтор записи компании не добавлен повторно");
                    } else {
                        metadata.remove(id);
                        conflicts.add(id);
                        throw RowValues.invalid("company_id", "Конфликтующие записи компании исключены из справочника");
                    }
                }
            } catch (RowValues.InvalidRow invalid) {
                issues.add("INVALID_COMPANY", "ERROR", row.source(), row.record(), row.line(), invalid.field(), invalid.getMessage());
            }
        });
        return metadata;
    }

    private ParsedOperation operation(CsvRows.Row row, LocalDate today) {
        String companyId = RowValues.id(row, "company_id");
        String operationId = RowValues.id(row, "operation_id");
        LocalDate date = RowValues.date(row, "operation_date", today, true);
        BigDecimal amount = RowValues.money(row, "amount", true, true);
        Direction direction = switch (row.value("direction")) {
            case "Поступление", "IN" -> Direction.IN;
            case "Списание", "OUT" -> Direction.OUT;
            default -> throw RowValues.invalid("direction", "Направление должно быть Поступление/Списание или IN/OUT");
        };
        String inn = RowValues.inn(row, "counterparty_inn");
        String name = RowValues.text(row, "counterparty_name", 500, false);
        String purpose = RowValues.text(row, "payment_purpose", 2000, false);
        BigDecimal balance = RowValues.money(row, "balance_eod", false, false);
        return new ParsedOperation(companyId, operationId, date, amount, direction, inn, name, purpose, balance,
                categorizer.categorize(direction, purpose, name), row.record(), row.line(), row.fields());
    }

    private void optionalIssues(ParsedOperation operation, IssueCollector issues) {
        if (operation.counterpartyInn().isBlank()) {
            optionalIssue(operation, issues, "EMPTY_COUNTERPARTY_INN", "counterparty_inn", "ИНН контрагента не указан");
        }
        if (operation.paymentPurpose().isBlank()) {
            optionalIssue(operation, issues, "EMPTY_PURPOSE", "payment_purpose", "Назначение не указано; категория не определена");
        }
        if (operation.balanceEod() == null) {
            optionalIssue(operation, issues, "MISSING_BALANCE", "balance_eod", "Остаток на конец дня не указан");
        }
    }

    private void optionalIssue(ParsedOperation operation, IssueCollector issues, String code, String field, String message) {
        issues.add(code, "WARNING", "STATEMENTS", operation.sourceRecord(), operation.sourceLine(), field, message);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 недоступен", exception);
        }
    }

    private static final class ParseState {
        private final IssueCollector issues;
        private final Map<OperationKey, ParsedOperation> accepted = new LinkedHashMap<>();
        private final Map<OperationKey, DuplicateState> identifiers = new HashMap<>();
        private final Map<PaymentKey, String> similar = new HashMap<>();
        private int total;
        private int duplicates;

        ParseState(IssueCollector issues) {
            this.issues = issues;
        }

        void accept(ParsedOperation operation) {
            var key = new OperationKey(operation.companyId(), operation.operationId());
            DuplicateState prior = identifiers.get(key);
            if (prior != null) {
                if (!prior.conflict && prior.first.sourceFields().equals(operation.sourceFields())) {
                    duplicates++;
                    prior.duplicates++;
                    issue("DUPLICATE_OPERATION", "WARNING", operation, "Повтор той же операции с тем же ID не добавлен повторно");
                } else {
                    if (!prior.conflict) {
                        accepted.remove(key);
                        duplicates -= prior.duplicates;
                        issue("OPERATION_ID_CONFLICT", "ERROR", prior.first, "У одного ID разные данные; обе версии исключены");
                    }
                    prior.conflict = true;
                    issue("OPERATION_ID_CONFLICT", "ERROR", operation, "У одного ID разные данные; запись исключена");
                }
                return;
            }
            identifiers.put(key, new DuplicateState(operation));
            accepted.put(key, operation);
            var payment = new PaymentKey(operation.companyId(), operation.operationDate(), operation.amount(), operation.direction(),
                    operation.counterpartyInn(), operation.counterpartyName(), operation.paymentPurpose(), operation.balanceEod());
            if (similar.putIfAbsent(payment, operation.operationId()) != null) {
                issue("POSSIBLE_DUPLICATE", "WARNING", operation, "Похожая оплата с другим ID сохранена; проверьте, не повтор ли это");
            }
        }

        private void issue(String code, String severity, ParsedOperation operation, String message) {
            issues.add(code, severity, "STATEMENTS", operation.sourceRecord(), operation.sourceLine(), "operation_id", message);
        }
    }

    private record OperationKey(String companyId, String operationId) {}
    private record PaymentKey(String companyId, LocalDate date, BigDecimal amount, Direction direction, String inn,
                              String name, String purpose, BigDecimal balance) {}

    private static final class DuplicateState {
        private final ParsedOperation first;
        private int duplicates;
        private boolean conflict;

        DuplicateState(ParsedOperation first) {
            this.first = first;
        }
    }
}
