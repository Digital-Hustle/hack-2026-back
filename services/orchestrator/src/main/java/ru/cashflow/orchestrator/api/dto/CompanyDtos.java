package ru.cashflow.orchestrator.api.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import ru.cashflow.orchestrator.features.companies.model.Account;
import ru.cashflow.orchestrator.features.companies.model.Company;
import ru.cashflow.orchestrator.features.companies.model.LegalForm;
import ru.cashflow.orchestrator.features.companies.model.Membership;
import ru.cashflow.orchestrator.features.companies.model.Role;

public final class CompanyDtos {

    private CompanyDtos() {
    }

    public record CompanyRq(@NotBlank @Size(max = 500) String name, LegalForm legalForm,
                            @Pattern(regexp = "^([0-9]{10}|[0-9]{12})?$") String inn,
                            @Pattern(regexp = "^([0-9]{9})?$") String kpp,
                            @Size(max = 64) String timezone) {
    }

    public record CompanyRs(UUID id, String name, LegalForm legalForm, String inn, String kpp, String timezone,
                            UUID ownerUserId, long dataVersion, long version, Instant createdAt, Instant updatedAt) {
        public static CompanyRs of(Company c) {
            return new CompanyRs(c.id(), c.name(), c.legalForm(), c.inn(), c.kpp(), c.timezone(), c.ownerUserId(),
                    c.dataVersion(), c.version(), c.createdAt(), c.updatedAt());
        }
    }

    public record MemberRq(@NotNull UUID userId, @NotNull Role role) {
    }

    public record RoleRq(@NotNull Role role) {
    }

    public record MemberRs(UUID userId, Role role, long version, Instant joinedAt) {
        public static MemberRs of(Membership m) {
            return new MemberRs(m.userId(), m.role(), m.version(), m.joinedAt());
        }
    }

    public record AccountRq(@NotBlank @Size(max = 200) String name, @Size(max = 200) String bankName,
                            @Pattern(regexp = "^([0-9]{20})?$") String accountNumber,
                            @Pattern(regexp = "^([A-Za-z0-9_.:-]{1,128})?$") String externalCompanyId) {
    }

    public record AccountRs(UUID id, UUID companyId, String name, String bankName, String accountNumber,
                            String externalCompanyId, String currency, long version, Instant createdAt,
                            Instant updatedAt) {
        public static AccountRs of(Account a) {
            return new AccountRs(a.id(), a.companyId(), a.name(), a.bankName(), a.accountNumber(),
                    a.externalCompanyId(), a.currency(), a.version(), a.createdAt(), a.updatedAt());
        }
    }

    public record ItemsRs<T>(java.util.List<T> items) {
    }
}
