package ru.cashflow.orchestrator.features.forecast.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.cashflow.orchestrator.features.results.repository.ForecastSelectionSql;

/** Finds accounts whose financial inputs are newer than their latest saved forecast. */
@Repository
public class AutoRefreshRepository {

    private final JdbcClient jdbc;

    public AutoRefreshRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Candidate> candidates(int limit, Instant now) {
        return jdbc.sql("""
                        select c.id as company_id, c.owner_user_id, c.timezone, a.id as account_id,
                               c.data_version,
                               latest.scope as latest_scope,
                               active.id as active_job_id, active.status as active_job_status,
                               active.origin as active_job_origin, active.input_version as active_job_version
                        from companies c
                        join accounts a on a.company_id = c.id
                        left join auto_refresh_attempts attempt
                          on attempt.company_id = c.id and attempt.account_id = a.id
                        left join lateral (
                            select f.input_version, f.scope
                            %s
                            where f.company_id = c.id and f.account_id = a.id
                            order by %s limit 1
                        ) latest on true
                        left join lateral (
                            select j.id, j.status, j.origin, s.input_version
                            from jobs j join snapshots s on s.id = j.snapshot_id
                            where j.company_id = c.id and j.account_id = a.id and j.type = 'FORECAST'
                              and j.status in ('QUEUED', 'RUNNING')
                            order by case when j.status = 'RUNNING' then 0 else 1 end, j.created_at, j.id
                            limit 1
                        ) active on true
                        where not c.archived and not a.archived
                          and c.data_version > 0
                          and c.data_version > coalesce(latest.input_version, -1)
                          and (attempt.input_version is distinct from c.data_version or attempt.retry_after <= :now)
                          and (active.id is null
                               or (active.status = 'QUEUED' and active.origin = 'AUTO'
                                   and active.input_version < c.data_version))
                        order by attempt.attempted_at nulls first, c.data_version desc, c.id, a.id
                        limit :limit
                        """.formatted(ForecastSelectionSql.FROM, ForecastSelectionSql.ORDER_BY))
                .param("limit", limit)
                .param("now", Timestamp.from(now))
                .query((rs, row) -> new Candidate(rs.getObject("company_id", UUID.class),
                        rs.getObject("account_id", UUID.class), rs.getObject("owner_user_id", UUID.class),
                        rs.getString("timezone"), rs.getLong("data_version"), rs.getString("latest_scope"),
                        rs.getObject("active_job_id", UUID.class), rs.getString("active_job_status"),
                        rs.getString("active_job_origin"), rs.getObject("active_job_version", Long.class)))
                .list();
    }

    /** The reservation survives failed snapshot transactions and prevents two scheduler instances claiming one attempt. */
    public boolean reserve(Candidate candidate, Instant now, Instant retryAfter) {
        return jdbc.sql("""
                        insert into auto_refresh_attempts(company_id,account_id,input_version,attempted_at,retry_after)
                        values(:company,:account,:version,:now,:retry)
                        on conflict (company_id,account_id) do update
                        set input_version=excluded.input_version, attempted_at=excluded.attempted_at,
                            retry_after=excluded.retry_after
                        where auto_refresh_attempts.input_version <> excluded.input_version
                           or auto_refresh_attempts.retry_after <= :now
                        """)
                .param("company", candidate.companyId()).param("account", candidate.accountId())
                .param("version", candidate.dataVersion()).param("now", Timestamp.from(now))
                .param("retry", Timestamp.from(retryAfter)).update() == 1;
    }

    public record Candidate(UUID companyId, UUID accountId, UUID ownerUserId, String timezone, long dataVersion,
                            String latestScope, UUID activeJobId, String activeJobStatus, String activeJobOrigin,
                            Long activeJobVersion) {
    }
}
