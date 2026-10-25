package ru.cashflow.orchestrator.features.companies.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.model.CompanyImportRow;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;

/** A malformed or conflicting CSV never leaves a partially imported batch. */
@Service
public class CompanyCsvImportService {

    private final CompanyCsvParser parser;
    private final CompanyService companies;
    private final CompanyRepository repository;

    public CompanyCsvImportService(CompanyCsvParser parser, CompanyService companies, CompanyRepository repository) {
        this.parser = parser;
        this.companies = companies;
        this.repository = repository;
    }

    @Transactional
    public ImportResult importCompanies(UUID userId, byte[] file) {
        List<CompanyImportRow> rows = parser.parse(file);
        List<ImportedItem> items = new ArrayList<>(rows.size());
        int created = 0;

        for (CompanyImportRow row : rows) {
            var previous = repository.findImported(userId, row.externalId());
            if (previous.isPresent()) {
                if (!previous.get().row().equals(row)) {
                    throw ApiException.conflict("EXTERNAL_COMPANY_CONFLICT",
                            "Компания с company_id из файла уже существует с другими данными");
                }
                items.add(new ImportedItem(row.externalId(), previous.get().id(), "EXISTING"));
                continue;
            }

            try {
                UUID id = companies.create(userId, row.name(), row.legalForm(), row.inn(), null, null)
                        .company().id();
                repository.attachImportMetadata(id, row);
                items.add(new ImportedItem(row.externalId(), id, "CREATED"));
                created++;
            } catch (DuplicateKeyException exception) {
                // Includes simultaneous uploads of the same source identity for this owner.
                throw ApiException.conflict("CONCURRENT_COMPANY_IMPORT",
                        "Компания с таким company_id уже импортируется; повторите запрос");
            }
        }
        return new ImportResult(rows.size(), created, rows.size() - created, List.copyOf(items));
    }

    public record ImportedItem(String externalCompanyId, UUID companyId, String status) {
    }

    public record ImportResult(int total, int created, int existing, List<ImportedItem> companies) {
    }
}
