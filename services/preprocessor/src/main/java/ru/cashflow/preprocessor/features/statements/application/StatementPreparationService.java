package ru.cashflow.preprocessor.features.statements.application;

import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ru.cashflow.preprocessor.features.statements.command.CsvStatementParser;
import ru.cashflow.preprocessor.features.statements.model.StatementResult;

@Service
public class StatementPreparationService {

    private static final Logger LOG = LoggerFactory.getLogger(StatementPreparationService.class);
    private final CsvStatementParser parser;

    public StatementPreparationService(CsvStatementParser parser) {
        this.parser = parser;
    }

    public StatementResult prepare(InputStream statements, InputStream companies) {
        StatementResult result = parser.parse(statements, companies);
        var report = result.report();
        LOG.info("statement_preparation_summary status={} rowsTotal={} rowsAccepted={} rowsRejected={} "
                        + "rowsDuplicate={} unclassifiedRows={} issueCount={} issuesTruncated={}",
                report.status(), report.rowsTotal(), report.rowsAccepted(), report.rowsRejected(),
                report.rowsDuplicate(), report.unclassifiedRows(), report.issueCount(), report.issuesTruncated());
        return result;
    }
}
