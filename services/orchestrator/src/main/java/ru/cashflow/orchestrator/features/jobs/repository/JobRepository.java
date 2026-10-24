package ru.cashflow.orchestrator.features.jobs.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.Job;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.JobStatus;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.JobType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL job queue without a broker: claims use FOR UPDATE SKIP LOCKED, writes are fenced by
 * lease owner.
 */
@Repository
public class JobRepository {

    private static final String COLUMNS =
            "id, company_id, account_id, type, status, snapshot_id, result_id, progress,"
                    + " error_json::text as error_json, lease_owner, lease_until, attempts,"
                    + " max_attempts, not_before, created_by, created_at, started_at, finished_at";

    private final JdbcClient jdbc;
    private final java.time.Clock clock;

    public JobRepository(JdbcClient jdbc, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void insert(CompanyScope scope, Job job) {
        jdbc.sql(
                        "insert into jobs (id, company_id, account_id, type, status, snapshot_id,"
                                + " result_id, progress, attempts, max_attempts, not_before,"
                                + " created_by, created_at) values (:id, :company, :account, :type,"
                                + " 'QUEUED', :snapshot, :result, 0, 0, :max, :notBefore, :user,"
                                + " :created)")
                .param("id", job.id())
                .param("company", scope.companyId())
                .param("account", job.accountId())
                .param("type", job.type().name())
                .param("snapshot", job.snapshotId())
                .param("result", job.resultId())
                .param("max", job.maxAttempts())
                .param("notBefore", Timestamp.from(job.notBefore()))
                .param("user", job.createdBy())
                .param("created", Timestamp.from(job.createdAt()))
                .update();
    }

    public Optional<Job> find(CompanyScope scope, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from jobs where id = :id and company_id = :company")
                .param("id", id)
                .param("company", scope.companyId())
                .query(JobRepository::job)
                .optional();
    }

    /**
     * Under the company row lock, a completed detection with a newer immutable input wins
     * regardless of job completion order. Input version takes precedence over business date;
     * enqueue_sequence resolves otherwise identical submissions.
     */
    public boolean hasNewerSuccessfulRecurringDetection(UUID jobId) {
        return jdbc.sql("""
                select exists (
                    select 1 from jobs old_job
                    join snapshots old_snapshot on old_snapshot.id = old_job.snapshot_id
                    join jobs new_job on new_job.company_id = old_job.company_id
                        and new_job.account_id = old_job.account_id
                        and new_job.type = 'DETECT_RECURRING'
                        and new_job.status = 'SUCCEEDED'
                        and new_job.id <> old_job.id
                    join snapshots new_snapshot on new_snapshot.id = new_job.snapshot_id
                    where old_job.id = :id and old_job.type = 'DETECT_RECURRING'
                        and (new_snapshot.input_version, new_snapshot.as_of, new_snapshot.created_at,
                             new_job.created_at, new_job.enqueue_sequence)
                          > (old_snapshot.input_version, old_snapshot.as_of, old_snapshot.created_at,
                             old_job.created_at, old_job.enqueue_sequence)
                )
                """).param("id", jobId).query(Boolean.class).single();
    }

    public Optional<Job> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from jobs where id = :id")
                .param("id", id)
                .query(JobRepository::job)
                .optional();
    }

    public List<Job> list(CompanyScope scope, UUID accountId, int limit) {
        return jdbc.sql(
                        "select "
                                + COLUMNS
                                + " from jobs where company_id = :company and account_id = :account"
                                + " order by created_at desc, id limit :limit")
                .param("company", scope.companyId())
                .param("account", accountId)
                .param("limit", limit)
                .query(JobRepository::job)
                .list();
    }

    /**
     * Takes the oldest due job, or a RUNNING job whose lease expired (worker crash). Each claim is
     * a new attempt with a fresh lease; a job that used up its attempts is failed instead of being
     * retried forever.
     */
    public Optional<Job> claim(String owner, Instant now, Instant leaseUntil) {
        return jdbc.sql(
                        "update jobs set status = 'RUNNING', lease_owner = :owner, lease_until ="
                            + " :until, attempts = attempts + 1, started_at = coalesce(started_at,"
                            + " :now) where id = (select id from jobs where ((status = 'QUEUED' and"
                            + " not_before <= :now) or (status = 'RUNNING' and lease_until <="
                            + " :now)) and attempts < max_attempts order by created_at, id limit 1"
                            + " for update skip locked) returning "
                                + COLUMNS)
                .param("owner", owner)
                .param("until", Timestamp.from(leaseUntil))
                .param("now", Timestamp.from(now))
                .query(JobRepository::job)
                .optional();
    }

    /** Jobs whose lease expired after the last allowed attempt are failed with a safe reason. */
    public int failExhausted(Instant now) {
        return jdbc.sql(
                        "update jobs set status = 'FAILED', lease_owner = null, lease_until = null,"
                            + " finished_at = :now, error_json = cast(:error as jsonb) where status"
                            + " = 'RUNNING' and lease_until <= :now and attempts >= max_attempts")
                .param("now", Timestamp.from(now))
                .param(
                        "error",
                        "{\"code\":\"ATTEMPTS_EXHAUSTED\",\"detail\":\"Задание не завершилось за"
                                + " допустимое число попыток\"}")
                .update();
    }

    /** Lease-fenced: only the current owner of this attempt can extend, finish or fail the job. */
    public boolean extend(UUID id, String owner, int attempt, Instant until) {
        return jdbc.sql(
                                "update jobs set lease_until = :until where id = :id and status ="
                                    + " 'RUNNING' and lease_owner = :owner and attempts = :attempt"
                                    + " and lease_until > :now and :until > :now")
                        .param("until", Timestamp.from(until))
                        .param("id", id)
                        .param("owner", owner)
                        .param("attempt", attempt)
                        .param("now", Timestamp.from(clock.instant()))
                        .update()
                == 1;
    }

    public boolean succeed(UUID id, String owner, int attempt, Instant now) {
        return jdbc.sql(
                                "update jobs set status = 'SUCCEEDED', progress = 100, lease_owner"
                                    + " = null, lease_until = null, finished_at = :now, error_json"
                                    + " = null where id = :id and status = 'RUNNING' and"
                                    + " lease_owner = :owner and attempts = :attempt and"
                                    + " lease_until > :now")
                        .param("now", Timestamp.from(now))
                        .param("id", id)
                        .param("owner", owner)
                        .param("attempt", attempt)
                        .update()
                == 1;
    }

    public boolean fail(UUID id, String owner, int attempt, String errorJson, Instant now) {
        return jdbc.sql(
                                "update jobs set status = 'FAILED', lease_owner = null, lease_until"
                                        + " = null, finished_at = :now, error_json = cast(:error as"
                                        + " jsonb) where id = :id and status = 'RUNNING' and"
                                        + " lease_owner = :owner and attempts = :attempt and"
                                        + " lease_until > :now")
                        .param("now", Timestamp.from(now))
                        .param("error", errorJson)
                        .param("id", id)
                        .param("owner", owner)
                        .param("attempt", attempt)
                        .update()
                == 1;
    }

    /**
     * Releases the lease for a retryable failure; the job becomes claimable again after the
     * backoff.
     */
    public boolean retryLater(
            UUID id, String owner, int attempt, String errorJson, Instant notBefore) {
        return jdbc.sql(
                                "update jobs set status = 'QUEUED', lease_owner = null, lease_until"
                                    + " = null, not_before = :notBefore, error_json = cast(:error"
                                    + " as jsonb) where id = :id and status = 'RUNNING' and"
                                    + " lease_owner = :owner and attempts = :attempt and"
                                    + " lease_until > :now")
                        .param("notBefore", Timestamp.from(notBefore))
                        .param("error", errorJson)
                        .param("id", id)
                        .param("owner", owner)
                        .param("attempt", attempt)
                        .param("now", Timestamp.from(clock.instant()))
                        .update()
                == 1;
    }

    public boolean cancel(CompanyScope scope, UUID id, Instant now) {
        return jdbc.sql(
                                "update jobs set status = 'CANCELLED', lease_owner = null,"
                                    + " lease_until = null, finished_at = :now where id = :id and"
                                    + " company_id = :company and status = 'QUEUED'")
                        .param("now", Timestamp.from(now))
                        .param("id", id)
                        .param("company", scope.companyId())
                        .update()
                == 1;
    }

    private static Job job(ResultSet rs, int row) throws SQLException {
        Timestamp lease = rs.getTimestamp("lease_until");
        Timestamp started = rs.getTimestamp("started_at");
        Timestamp finished = rs.getTimestamp("finished_at");
        return new Job(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                JobType.valueOf(rs.getString("type")),
                JobStatus.valueOf(rs.getString("status")),
                rs.getObject("snapshot_id", UUID.class),
                rs.getObject("result_id", UUID.class),
                rs.getInt("progress"),
                rs.getString("error_json"),
                rs.getString("lease_owner"),
                lease == null ? null : lease.toInstant(),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getTimestamp("not_before").toInstant(),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                started == null ? null : started.toInstant(),
                finished == null ? null : finished.toInstant());
    }
}
