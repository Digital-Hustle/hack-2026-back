package ru.cashflow.preprocessor.config;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import ru.cashflow.preprocessor.api.security.TrustedUserResolver;
import ru.cashflow.preprocessor.features.statements.command.CsvStatementParser;
import ru.cashflow.preprocessor.features.statements.command.RuleCategorizer;

@Configuration
public class PreprocessorConfiguration implements WebMvcConfigurer {

    private final TrustedUserResolver userResolver;

    public PreprocessorConfiguration(TrustedUserResolver userResolver) {
        this.userResolver = userResolver;
    }

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
    ImportLimits importLimits(@Value("${preprocessor.imports.max-file-bytes:20971520}") long maxFileBytes,
                              @Value("${preprocessor.imports.max-rows:50000}") int maxRows) {
        return new ImportLimits(maxFileBytes, maxRows);
    }

    @Bean
    CsvStatementParser parser(Clock clock, ParseLimits limits) {
        return new CsvStatementParser(clock, limits, new RuleCategorizer());
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(userResolver);
    }
}
