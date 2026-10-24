package ru.cashflow.orchestrator.features.jobs.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.cashflow.orchestrator.features.companies.model.CompanyScope;

@Repository
public class IdempotencyRepository {

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the stored record if this key was already used for this operation in this company. */
    public Optional<Record> find(CompanyScope scope, String operation, String key) {
        return jdbc.sql("select request_hash, resource_id from idempotency_records where company_id = :company "
                        + "and operation = :operation and idempotency_key = :key")
                .param("company", scope.companyId()).param("operation", operation).param("key", key)
                .query((rs, row) -> new Record(rs.getString("request_hash"), rs.getObject("resource_id", UUID.class)))
                .optional();
    }

    /** Inserts the record; returns false if a concurrent request with the same key won the race. */
    public boolean insert(CompanyScope scope, String operation, String key, String requestHash, UUID resourceId,
                          Instant now) {
        return jdbc.sql("insert into idempotency_records (company_id, idempotency_key, operation, request_hash, "
                        + "resource_id, created_by, created_at) values (:company, :key, :operation, :hash, :resource, "
                        + ":user, :now) on conflict do nothing")
                .param("company", scope.companyId()).param("key", key).param("operation", operation)
                .param("hash", requestHash).param("resource", resourceId).param("user", scope.userId())
                .param("now", Timestamp.from(now)).update() == 1;
    }

    public void audit(CompanyScope scope, String action, String resourceType, UUID resourceId, Instant now) {
        jdbc.sql("insert into audit_entries (id, company_id, actor_id, action, resource_type, resource_id, created_at) "
                        + "values (:id, :company, :actor, :action, :type, :resource, :now)")
                .param("id", UUID.randomUUID()).param("company", scope.companyId()).param("actor", scope.userId())
                .param("action", action).param("type", resourceType).param("resource", resourceId)
                .param("now", Timestamp.from(now)).update();
    }

    public record Record(String requestHash, UUID resourceId) {
    }
}
