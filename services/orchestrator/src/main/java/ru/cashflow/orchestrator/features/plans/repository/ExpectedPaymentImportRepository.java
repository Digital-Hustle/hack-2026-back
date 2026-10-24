package ru.cashflow.orchestrator.features.plans.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.StoredImport;

/** Replay store keyed by (company, Idempotency-Key) for the external expected-payment registry import. */
@Repository
public class ExpectedPaymentImportRepository {

    private static final TypeReference<List<UUID>> IDS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ExpectedPaymentImportRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<StoredImport> find(CompanyScope scope, String key) {
        return jdbc.sql("select request_hash, created_count, updated_count, unchanged_count, item_ids::text as ids "
                        + "from expected_payment_imports where company_id = :company and idempotency_key = :key")
                .param("company", scope.companyId()).param("key", key)
                .query((rs, row) -> new StoredImport(rs.getString("request_hash"), rs.getInt("created_count"),
                        rs.getInt("updated_count"), rs.getInt("unchanged_count"), ids(rs.getString("ids"))))
                .optional();
    }

    public void insert(CompanyScope scope, UUID id, String key, String requestHash, int created, int updated,
                       int unchanged, List<UUID> itemIds, Instant now) {
        jdbc.sql("insert into expected_payment_imports (id, company_id, idempotency_key, request_hash, created_count, "
                        + "updated_count, unchanged_count, item_ids, created_by, created_at) values (:id, :company, "
                        + ":key, :hash, :created, :updated, :unchanged, cast(:ids as jsonb), :actor, :now)")
                .param("id", id).param("company", scope.companyId()).param("key", key)
                .param("hash", requestHash).param("created", created).param("updated", updated)
                .param("unchanged", unchanged).param("ids", idsJson(itemIds)).param("actor", scope.userId())
                .param("now", Timestamp.from(now)).update();
    }

    private List<UUID> ids(String value) {
        try {
            return json.readValue(value, IDS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String idsJson(List<UUID> ids) {
        try {
            return json.writeValueAsString(ids);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
