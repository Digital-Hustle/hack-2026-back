package ru.cashflow.preprocessor.api.controller.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ru.cashflow.preprocessor.api.controller.ImportController;
import ru.cashflow.preprocessor.api.dto.response.ImportRs;
import ru.cashflow.preprocessor.api.dto.response.PreparedStatementRs;
import ru.cashflow.preprocessor.api.error.ApiException;
import ru.cashflow.preprocessor.api.mapper.StatementMapper;
import ru.cashflow.preprocessor.api.security.CurrentUser;
import ru.cashflow.preprocessor.api.validation.StatementFileValidator;
import ru.cashflow.preprocessor.config.ImportLimits;
import ru.cashflow.preprocessor.features.statements.application.StatementPreparationService;
import ru.cashflow.preprocessor.features.statements.model.CsvInputException;
import ru.cashflow.preprocessor.integrations.orchestrator.OrchestratorImportGateway;
import ru.cashflow.preprocessor.integrations.orchestrator.OrchestratorImportGateway.ImportUploadCommand;

/**
 * Public synchronous upload: parse CSV once in this service, stage the PreparedStatementRs in Orchestrator, then
 * finalize it. Nothing becomes history until Orchestrator answers COMMITTED.
 */
@RestController
public class ImportControllerImpl implements ImportController {

    private static final Logger LOG = LoggerFactory.getLogger(ImportControllerImpl.class);
    private static final int FILE_NAME_MAX_LENGTH = 200;

    private final StatementPreparationService service;
    private final StatementMapper mapper;
    private final StatementFileValidator fileValidator;
    private final ImportLimits limits;
    private final OrchestratorImportGateway gateway;

    public ImportControllerImpl(StatementPreparationService service, StatementMapper mapper,
                                StatementFileValidator fileValidator, ImportLimits limits,
                                OrchestratorImportGateway gateway) {
        this.service = service;
        this.mapper = mapper;
        this.fileValidator = fileValidator;
        this.limits = limits;
        this.gateway = gateway;
    }

    @Override
    public ResponseEntity<ImportRs> upload(CurrentUser user, UUID companyId, String idempotencyKey, MultipartFile file,
                                           String accountId, String validationMode, String completeness,
                                           String declaredFromDate, String declaredToDate) {
        UUID account = canonicalUuid(accountId, "accountId", "REQUIRED");
        String key = canonicalKey(idempotencyKey);
        if (file == null) {
            throw ApiException.badRequest("Нужно передать CSV в multipart-поле file", "file", "FILE_REQUIRED");
        }
        ValidationMode mode = enumValue(ValidationMode.class, validationMode, "validationMode", "REQUIRED");
        Completeness declared = enumValue(Completeness.class, completeness, "completeness", "REQUIRED");
        LocalDate from = date(declaredFromDate, "declaredFromDate");
        LocalDate to = date(declaredToDate, "declaredToDate");
        if (declared == Completeness.CONFIRMED_COMPLETE) {
            if (from == null || to == null) {
                throw ApiException.unprocessable("Для CONFIRMED_COMPLETE нужны declaredFromDate и declaredToDate",
                        "declaredFromDate", "COMPLETENESS_RANGE_REQUIRED");
            }
            if (from.isAfter(to)) {
                throw ApiException.unprocessable("declaredFromDate не может быть позже declaredToDate",
                        "declaredFromDate", "INVALID_DATE_RANGE");
            }
        } else {
            from = null;
            to = null;
        }

        try {
            fileValidator.validate(file, limits.maxFileBytes());
        } catch (CsvInputException exception) {
            throw contractProblem(exception);
        }
        String fileName = fileName(file);
        byte[] content = bytes(file);
        PreparedStatementRs statement = prepare(content);
        if (statement.report().rowsTotal() > limits.maxRows()) {
            throw ApiException.payloadTooLarge("Превышено допустимое число строк CSV: " + limits.maxRows());
        }

        long start = System.nanoTime();
        var result = gateway.upload(new ImportUploadCommand(user.userId(), companyId, account, key, fileName,
                sha256(content), mode.name(), declared.name(), from, to, statement));
        LOG.info("import_upload_completed importId={} status={} created={} durationMs={}", result.importRs().id(),
                result.importRs().status(), result.created(), (System.nanoTime() - start) / 1_000_000);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(result.importRs());
    }

    private PreparedStatementRs prepare(byte[] content) {
        try {
            return mapper.response(service.prepare(new ByteArrayInputStream(content), null));
        } catch (CsvInputException exception) {
            throw contractProblem(exception);
        }
    }

    private static ApiException contractProblem(CsvInputException exception) {
        int status = exception.status();
        if (status == 413) {
            return ApiException.payloadTooLarge(exception.getMessage());
        }
        if (status == 415) {
            return ApiException.unsupportedMediaType(exception.getMessage());
        }
        return ApiException.unprocessable(exception.getMessage(), "file", exception.code());
    }

    private static String fileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("Не удалось определить имя CSV-файла", "file", "FILE_NAME_REQUIRED");
        }
        if (name.length() > FILE_NAME_MAX_LENGTH) {
            throw ApiException.badRequest("Имя CSV-файла длиннее " + FILE_NAME_MAX_LENGTH + " символов", "file",
                    "FILE_NAME_TOO_LONG");
        }
        return name;
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw ApiException.unprocessable("Не удалось прочитать загруженный CSV", "file", "FILE_READ_ERROR");
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 недоступен", exception);
        }
    }

    private static UUID canonicalUuid(String value, String field, String missingCode) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("Нужно передать " + field, field, missingCode);
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest(field + " должен быть UUID", field, "INVALID_UUID");
        }
    }

    private static String canonicalKey(String value) {
        return canonicalUuid(value, "Idempotency-Key", "REQUIRED").toString();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field, String missingCode) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("Нужно передать " + field, field, missingCode);
        }
        try {
            return Enum.valueOf(type, value.strip());
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("Недопустимое значение " + field, field, "INVALID_ENUM");
        }
    }

    private static LocalDate date(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException exception) {
            throw ApiException.badRequest(field + " должен быть датой ISO YYYY-MM-DD", field, "INVALID_DATE");
        }
    }

    private enum ValidationMode { ATOMIC, SKIP_INVALID }

    private enum Completeness { UNKNOWN, CONFIRMED_COMPLETE }
}
