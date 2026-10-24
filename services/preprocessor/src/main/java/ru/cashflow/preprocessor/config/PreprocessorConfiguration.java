package ru.cashflow.preprocessor.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ru.cashflow.preprocessor.features.statements.command.CsvStatementParser;
import ru.cashflow.preprocessor.features.statements.command.RuleCategorizer;

@Configuration
public class PreprocessorConfiguration {

    @Bean
    Clock clock(@Value("${preprocessor.timezone:Europe/Moscow}") String timezone) {
        return Clock.system(ZoneId.of(timezone));
    }

    @Bean
    ParseLimits parseLimits(@Value("${preprocessor.limits.statement-bytes:104857600}") long statementBytes,
                            @Value("${preprocessor.limits.company-bytes:2097152}") long companyBytes,
                            @Value("${preprocessor.limits.max-rows:250000}") int rows,
                            @Value("${preprocessor.limits.max-field-length:4096}") int fieldLength,
                            @Value("${preprocessor.limits.max-issues:1000}") int issues,
                            @Value("${preprocessor.limits.timeout-seconds:120}") int timeout) {
        return new ParseLimits(statementBytes, companyBytes, rows, fieldLength, issues, timeout);
    }

    @Bean
    CsvStatementParser parser(Clock clock, ParseLimits limits) {
        return new CsvStatementParser(clock, limits, new RuleCategorizer());
    }
}
