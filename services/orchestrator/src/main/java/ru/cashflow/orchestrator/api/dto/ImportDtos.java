package ru.cashflow.orchestrator.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Mirrors PreProcessor PreparedStatementRs (schemaVersion "1"). Unknown fields are tolerated so PreProcessor can add
 * optional fields, but every field used here is validated again: Orchestrator does not trust another service.
 */
public final class ImportDtos {

    private ImportDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PreparedStatementRq(@NotBlank String schemaVersion, @NotBlank @Size(max = 32) String rulesVersion,
                                      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String inputFingerprint,
                                      Instant preparedAt, List<@Valid PreparedCompany> companies,
                                      @NotNull @Size(max = 250_000) List<@Valid PreparedOperation> operations,
                                      @NotNull List<@Valid PreparedDay> dailyBalances,
                                      @NotNull @Valid PreparedReport report) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PreparedCompany(@NotBlank String companyId, String companyName, String companyInn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PreparedOperation(@NotBlank @Size(max = 128) String companyId,
                                    @NotBlank @Size(max = 128) String operationId,
                                    @NotNull LocalDate operationDate, @NotBlank String amount,
                                    @NotBlank @Pattern(regexp = "^(IN|OUT)$") String direction,
                                    @Pattern(regexp = "^([0-9]{10}|[0-9]{12})?$") String counterpartyInn,
                                    @Size(max = 500) String counterpartyName,
                                    @Size(max = 2000) String paymentPurpose, String balanceEod,
                                    @NotNull @Valid PreparedCategory category, Long sourceRecord, Long sourceLine,
                                    Map<String, String> sourceFields) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PreparedCategory(@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,63}$") String code,
                                   @NotBlank @Pattern(regexp = "^(RULE|UNCLASSIFIED)$") String source,
                                   String ruleId, @Size(max = 32) String rulesVersion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PreparedDay(@NotBlank String companyId, @NotNull LocalDate date, String balanceEod,
                              @NotBlank String balanceStatus) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PreparedReport(@NotBlank @Pattern(regexp = "^(PREPARED|PARTIAL|INVALID)$") String status,
                                 long issueCount, List<PreparedIssue> issues) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PreparedIssue(String code, String severity, Long sourceLine, String field, String message) {
    }

    public record ImportRs(UUID id, UUID accountId, String status, String preprocessorStatus, int rowsStaged,
                           Integer rowsInserted, Integer rowsAlreadyPresent, long issueCount, String inputFingerprint,
                           Instant createdAt, Instant finalizedAt, long version) {
    }
}
