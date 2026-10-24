package ru.cashflow.orchestrator.features.snapshot.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.cashflow.orchestrator.features.companies.model.CompanyScope;

@Repository
public class SnapshotRepository {

    private final JdbcClient jdbc;

    public SnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(CompanyScope scope, StoredSnapshot s) {
        jdbc.sql("insert into snapshots (id, company_id, account_id, kind, as_of, input_version, fingerprint, payload, "
                        + "provenance_json, created_at) values (:id, :company, :account, :kind, :asOf, :version, :fp, "
                        + "cast(:payload as jsonb), cast(:provenance as jsonb), :created)")
                .param("id", s.id()).param("company", scope.companyId()).param("account", s.accountId())
                .param("kind", s.kind()).param("asOf", s.asOf()).param("version", s.inputVersion())
                .param("fp", s.fingerprint()).param("payload", s.payload()).param("provenance", s.provenance())
                .param("created", Timestamp.from(s.createdAt())).update();
    }

    public Optional<StoredSnapshot> find(CompanyScope scope, UUID id) {
        return jdbc.sql("select id, account_id, kind, as_of, input_version, fingerprint, payload::text as payload, "
                        + "provenance_json::text as provenance, created_at from snapshots "
                        + "where id = :id and company_id = :company")
                .param("id", id).param("company", scope.companyId())
                .query((rs, row) -> new StoredSnapshot(rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class), rs.getString("kind"),
                        rs.getObject("as_of", LocalDate.class), rs.getLong("input_version"),
                        rs.getString("fingerprint"), rs.getString("payload"), rs.getString("provenance"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    /** Internal access by id for the job worker, which has no user; the company id is checked against the job. */
    public Optional<StoredSnapshot> findForWorker(UUID companyId, UUID id) {
        return jdbc.sql("select id, account_id, kind, as_of, input_version, fingerprint, payload::text as payload, "
                        + "provenance_json::text as provenance, created_at from snapshots "
                        + "where id = :id and company_id = :company")
                .param("id", id).param("company", companyId)
                .query((rs, row) -> new StoredSnapshot(rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class), rs.getString("kind"),
                        rs.getObject("as_of", LocalDate.class), rs.getLong("input_version"),
                        rs.getString("fingerprint"), rs.getString("payload"), rs.getString("provenance"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    public record StoredSnapshot(UUID id, UUID accountId, String kind, LocalDate asOf, long inputVersion,
                                 String fingerprint, String payload, String provenance, Instant createdAt) {
    }
}
