# Orchestrator

Владелец бизнес-данных и своей PostgreSQL. Реализуется поэтапно по
`backend-skills/docs/ORCHESTRATOR_FORECAST_HANDOFF.md` (раздел 7): одна задача — один PR.

Java 21, Spring Boot 3.5.7, Spring JDBC (`JdbcClient`), Liquibase, PostgreSQL. Отдельная Gradle-сборка
в `services/orchestrator`, корневая сборка и соседние сервисы не затрагиваются.

## Запуск

```powershell
.\gradlew.bat clean check bootJar
$env:ORCHESTRATOR_DB_URL = "jdbc:postgresql://localhost:5432/orchestrator"
$env:ORCHESTRATOR_DB_USER = "orchestrator"
$env:ORCHESTRATOR_DB_PASSWORD = "<из секрета>"
java -jar build\libs\orchestrator.jar
```

По умолчанию слушает `127.0.0.1:8083`. Тесты поднимают embedded PostgreSQL (Zonky), Docker не нужен;
`TEST_DATABASE_URL` позволяет указать внешнюю БД. Как и у PreProcessor, на Windows путь с кириллицей
может ломать classpath тестов — используйте checkout с латинским путём.

## Доверенный контекст

JWT проверяет только Gateway. Orchestrator читает ровно один заголовок `X-User-Id` (UUID). Нет заголовка,
два заголовка или не-UUID → `401`. Эта схема безопасна **только** если порт Orchestrator недоступен
никому, кроме Gateway; сетевую изоляцию обеспечивает деплой, она здесь не проверяется.

`CompanyAccess` — единственное место, где из `userId + companyId` получается `CompanyScope`
(ArchitectureTest это закрепляет). Нет членства или нет компании → `404` (чужая компания не отличается
от несуществующей). Роль недостаточна → `403`.

| Действие | OWNER | ACCOUNTANT | VIEWER |
|---|:---:|:---:|:---:|
| Читать компанию, счета, участников | ✅ | ✅ | ✅ |
| Создавать/менять счета (и далее — финансовые данные) | ✅ | ✅ | ❌ |
| Менять компанию, управлять участниками | ✅ | ❌ | ❌ |

## Задача 1: компании, участники, счета, CompanyScope

- `companies` с `data_version` (версия финансовых входов, handoff §3) и `version` (optimistic locking).
- Создание компании и OWNER-membership в одной транзакции; партиальный unique-индекс гарантирует одного
  OWNER. Второй OWNER, передача владения и удаление OWNER отклоняются.
- `accounts`: только RUB, unique `(id, company_id)` под будущие составные FK, `external_company_id`
  уникален внутри компании (связь с `company_id` из CSV PreProcessor) — **но не даёт доступа** и не выбирает
  компанию сам.
- Изменения требуют `If-Match: "<version>"`: без заголовка `428`, устаревшая версия `409`.

Эндпоинты (`/api/v1/orchestrator/companies`): `POST /`, `GET /`, `GET|PUT /{companyId}`,
`GET|POST /{companyId}/members`, `PATCH|DELETE /{companyId}/members/{userId}`,
`GET|POST /{companyId}/accounts`, `GET|PUT /{companyId}/accounts/{accountId}`.

## Задача 2: платежи, дневная история, полнота, балансные якоря

- `payments` — неизменяемые по дате/сумме/направлению факты, `amount > 0`, составной FK
  `(account_id, company_id)`, partial unique `(company, account, external_operation_id)`.
- `payment_revisions` — append-only история категорий, включая исходную (revision 0). Правка категории
  пользователем (`PUT .../payments/{id}/category`, `If-Match`) добавляет revision `USER` и поднимает
  `dataVersion`; факт не переписывается.
- `completeness_ranges` и `balance_anchors` — append-only с `known_at`. Поздний диапазон перекрывает ранний;
  для даты побеждает якорь, известный последним. Ручной якорь не меняет выписку. Полным можно отметить
  только закрытый прошедший день (по часовому поясу компании).
- `daily_observations` — пересчитываемая проекция, а не источник истины. `DailyHistoryCalculator` строит её
  из фактов/якорей/полноты, **уже отфильтрованных по моменту знания**, поэтому тот же код используется и для
  исторических срезов (задачи 6, 11): прошлый cutoff не читает сегодняшнюю проекцию.
- Ровно один день на дату. `OBSERVED` — полный день с операциями, `ZERO_CONFIRMED` — подтверждённый
  полный день без операций (потоки `0.00`), `MISSING` — потоки и EOD `null`, хотя `paymentCount` отражает
  известные строки. EOD не суммируется. `balanceMismatch` — EOD не следует из предыдущего EOD и потоков.
- `startBalance(T)` — последний якорь ≤ T, протянутый только через полные дни; неизвестный день между ними →
  баланса нет (не протягиваем через пропуск, §2).
- `CounterpartyKeys` (версия `CPK1`) — стабильный ключ: ИНН важнее имени, имя нормализуется. Без ИНН и имени →
  `null`, связь не выдумывается.

Эндпоинты (`/api/v1/orchestrator/companies/{c}/accounts/{a}`): `GET /payments?from&to&limit&offset`,
`GET /payments/{id}/revisions`, `PUT /payments/{id}/category`, `POST /completeness`, `POST /balance-anchors`,
`GET /daily-history?from&to`. Запись фактов появится в задаче 3 (импорт из PreProcessor).

## Задача 3: импорт из PreProcessor — staging и атомарный finalize

`POST /api/v1/orchestrator/companies/{c}/accounts/{a}/imports` принимает JSON
`PreparedStatementRs` (`schemaVersion: "1"`) и заголовки `X-User-Id` (только доверенный Gateway)
и `Idempotency-Key`. Дополнительные query-параметры публичного PreProcessor upload:
`fileName`, `fileSha256`, `validationMode=ATOMIC|SKIP_INVALID`,
`completeness=UNKNOWN|CONFIRMED_COMPLETE`, `declaredFromDate`, `declaredToDate`.
Для старых внутренних клиентов без query используются `SKIP_INVALID` и `UNKNOWN`.

- При stage проверяются WRITE-доступ и принадлежность счёта, `externalCompanyId`,
  валидность операций и согласованность счётчиков с prepared payload. Нет ни платежей,
  ни изменений `dataVersion`.
- `ATOMIC` отклоняет неполную/некорректную выписку (422) целиком;
  `SKIP_INVALID` допускает частично обработанные данные, если есть корректные операции.
- `CONFIRMED_COMPLETE` требует `PREPARED` без отклонённых строк. Частичная выписка
  возвращает `422 INCOMPLETE_STATEMENT`; её можно загрузить с `UNKNOWN`, сохранив
  корректные операции без подтверждения полноты периода.
- `Idempotency-Key` привязан к устойчивому hash подготовленных данных и параметров
  импорта, **без технического `preparedAt`**. Повтор с тем же ключом и содержимым
  возвращает тот же импорт; другое содержимое даёт `409 IDEMPOTENCY_KEY_REUSED`.
  Конкурирующие stage-запросы синхронизируются по компании.
- `finalize` с `If-Match` в одной транзакции сохраняет новые платежи,
  остатки и при явном `CONFIRMED_COMPLETE` — **заявленный период**.
  `UNKNOWN` не создаёт ложный диапазон полноты; отсутствие операций
  само по себе не означает нулевой день.
- Повторный finalize идемпотентен. Duplicate-only импорт не создаёт повторных
  якорей и не увеличивает `dataVersion`. Версия записывается в `imports.data_version`
  во время finalize и возвращается одинаковой при последующих GET, даже
  если у компании позже появились новые данные.
- `ImportRs` сохраняет исходные поля и дополнительно возвращает файл,
  выбранные параметры, исходные счётчики и `dataVersion`.
  Состояния Orchestrator: `STAGED`, `FINALIZED`, `CANCELLED`;
  публичный PreProcessor отображает `FINALIZED` как `COMMITTED`.
- Отменить `FINALIZED` нельзя. После непредсказуемой ошибки finalize
  PreProcessor сначала проверяет `GET /imports/{id}`, не делает слепой cancel.

Эндпоинты: `POST /imports`, `POST /imports/{id}/finalize`,
`POST /imports/{id}/cancel`, `GET /imports`, `GET /imports/{id}`,
`GET /imports/{id}/issues`.

Сквозная проверка с реальным выходом PreProcessor:
`PREPROCESSOR_SAMPLE_JSON=<путь к JSON одной компании>` включает
`PreprocessorContractTest`; без переменной тест пропускается.
Gateway должен отдельно маршрутизировать публичный upload.

## Задача 4: ожидаемые платежи и распределения оплат

- `expected_payments` — планы, а не факты. Поля и ответ совпадают с `ExpectedPaymentRs` контракта Forecast
  (§4): `settledAmount`, `remainingAmount = amount − settledAmount` (у отменённого 0), `overdue`, `knownAt`,
  `sourceDocument`, `version`.
- `relation` проверяется и в коде (`422 UNRESOLVED_PLAN`), и CHECK-ограничением БД: `ADDITIONAL` — без связей,
  `REPLACES_RECURRING` — только `recurringPatternId`, `REPLACES_BASELINE` — только `baselineCounterpartyKey`
  (и нужен `counterpartyKey`). Двойной учёт без подтверждённой связи не допускается.
- Оплата закрывает план только через `allocations` к реальному платежу. В транзакции блокируются план и платёж:
  сумма распределений не превышает ни план, ни факт; направление и счёт совпадают; не больше одной активной
  связи plan/payment. Параллельные распределения одного платежа на разные планы не превышают его сумму (тест).
- `reverse` ставит `reversed_at`, строка не удаляется — история распределений на прошлые даты сохраняется.
- Статус выводится из распределений: `OPEN → PARTIALLY_SETTLED → SETTLED`; `CANCELLED` — только явной отменой.
  Сумму нельзя опустить ниже оплаченной части, а направление частично оплаченного плана менять нельзя.
- Каждое изменение (создание, правка, отмена, распределение, reversal) пишет **полное состояние** в
  `expected_payment_revisions` с `valid_at` и поднимает `dataVersion` (§2, §3). Просроченный план не переносится
  на завтра автоматически.

Эндпоинты (`.../accounts/{a}`): `POST|GET /expected-payments`, `GET|PUT /expected-payments/{id}`,
`POST /expected-payments/{id}/cancel`, `POST|GET /expected-payments/{id}/allocations`,
`POST /allocations/{id}/reverse`.

## Задача 5: регулярности и их ревизии

- `recurring_patterns` с устойчивым `stable_key = (accountId, categoryCode, direction, counterpartyKey)` (§2) —
  повторный detection находит ту же строку, а не создаёт новую UUID. Без ключа контрагента используется явный
  маркер `<none>`, чтобы такой кандидат не склеился с реальным контрагентом.
- `Schedule` как в контракте: `WEEKLY` — `weekday 1..7`, `MONTHLY` — `dayOfMonth 1..31`, `businessDayRule
  NONE|FOLLOWING`; CHECK в БД и проверка в коде.
- `mergeDetection` принимает кандидатов `DETECTED` (ответ Forecast `recurring-detection` будет подключён в задаче 9):
  новые создаются, неподтверждённые обновляются, а **решения пользователя `CONFIRMED`/`EXCLUDED` не перезаписываются**.
  Кандидаты с одинаковым ключом в одном прогоне считаются неоднозначными и пропускаются, а не угадываются.
- `PUT .../recurring-patterns/{id}/status` (`If-Match`, только OWNER/ACCOUNTANT) — CONFIRMED или EXCLUDED;
  меняет то, что получит Forecast, поэтому поднимает `dataVersion`.
- `recurring_revisions` хранит полное состояние с `known_at` при каждом изменении — для вопроса «что было
  CONFIRMED на cutoff T» (§3). В Forecast будут отправляться только `CONFIRMED` (задача 7).
- Подготовлены таблицы `account_settings`/`settings_revisions` для настроек прогноза (используются в задаче 6/7).

## Задача 6: append-only revisions с `knownAt` и историческое знание

`KnowledgeReader.read(scope, account, historyFrom, asOf, knownBefore)` отвечает на вопрос §3 «что система знала
на cutoff T?» и возвращает `KnowledgeState`:

- **факты** — только `operation_date ≤ asOf` и `known_at < knownBefore`; будущие и поздно загруженные строки
  не возвращаются;
- **категория** каждого платежа — последняя revision, известная до cutoff, а не сегодняшняя (поздний USER override
  невидим);
- **дневная история и B(T)** — пересчитываются `DailyHistoryCalculator` из этих фактов, якорей и полноты,
  известных до cutoff; сегодняшняя проекция `daily_observations` для прошлого не читается;
- **планы** — полное состояние из последней revision до cutoff; `settledAmount` — сумма распределений,
  созданных до cutoff и ещё не отменённых на нём (поздняя оплата не уменьшает remaining исторического плана);
  план, созданный после cutoff, отсутствует;
- **регулярности** — состояние на cutoff (подтверждение после cutoff невидимо);
- **настройки прогноза** — действовавшие до cutoff, иначе значения по умолчанию.

Тот же reader используется и для текущего прогноза (cutoff = сейчас), поэтому живой и исторический путь не
расходятся. Исторический cutoff — конец дня T в часовом поясе компании (`KnowledgeReader.endOfDay`, исключительная
граница начала T+1). `revisionsUsed` возвращается для provenance snapshot (задача 7).

Настройки прогноза: `GET|PUT .../accounts/{a}/forecast-settings` (`If-Match`). Принимаются только значения
контракта: `windowWeeks 8|10|12`, `horizonDays 30`, `bufferAmount ≥ 0`, `scenarioCount 100..5000`,
`minCalibrationOrigins 12..100`, `calibrationStepDays 7`, `algorithm WEEKDAY_MEAN_V1`. Изменение пишет
`settings_revisions` и поднимает `dataVersion`.

## Задача 7: canonical snapshot builder для текущего прогноза

`SnapshotService.buildCurrent(scope, account, asOf, scope, calibrationOrigins)` выполняет шаги 1–10 §5 в одной
транзакции **REPEATABLE READ**: доступ, `asOf` — последний закрытый день, `dataVersion` и все входы читаются из
одного MVCC-среза. Сетевых вызовов внутри нет.

- Wire-DTO `ForecastWire` написаны вручную по §4 и проверены тестом против `forecast-examples/calculate.json`
  (тот же набор полей на каждом уровне). Nullable поля — явный `null`, списки — `[]`, деньги — строка с 2 знаками,
  моменты — UTC `...Z`.
- Окно истории `[T − windowWeeks·7 + 1, T]`, сокращается до первого полного дня без выдуманных нулей; каждая дата
  ровно один раз. Будущие факты не передаются. `startBalance/startBalanceDate` — B(T) из якоря, протянутого только
  через полные дни; без него — `NO_START_BALANCE`, а не догадка.
- `HISTORY_ONLY` → `knownPlans = []`; `WITH_KNOWN_PLANS` → только `OPEN/PARTIALLY_SETTLED` с `remainingAmount > 0`.
  `recurringPatterns` — только `CONFIRMED`. `completeRanges` выводятся из уже разрешённой дневной истории, чтобы
  не противоречить ей (§4).
- **Fingerprint** по профилю §5: удаляются `snapshotId`/`fingerprint` на каждом уровне ForecastSnapshot (включая
  вложенные calibration inputs), ключи сортируются, компактный UTF-8 JSON, `sha256` в lower-case hex. Коллекции
  сортируются билдером (`payments` по (date, paymentId), `knownPlans` по (dueDate, id), …), дубликаты origin
  отклоняются. Новый `snapshotId` не меняет fingerprint; бизнес-ID и `inputDataVersion` меняют.
- `snapshots` — неизменяемая таблица (триггер запрещает UPDATE/DELETE). `provenance_json` фиксирует режим replay,
  момент знания, использованные revisions планов и версии правил подготовки (§2, §10.5).
- `buildHistorical` использует тот же путь с cutoff = конец дня T; он понадобится в задачах 11–12.

## Задача 8: очередь заданий в PostgreSQL без брокера

- `POST .../accounts/{a}/forecast-jobs` (`Idempotency-Key`, тело `{asOfDate, scope}`) → `202` и задание `QUEUED`.
  В одной транзакции REPEATABLE READ: проверка прав, snapshot (задача 7), запись snapshot, job и
  `idempotency_records`. Forecast внутри транзакции не вызывается (§3).
- Тот же ключ и тело → то же задание; другое тело → `409`. Гонка параллельных запросов с одним ключом даёт один
  job и один snapshot (тест на 4 потока); serialization conflict приводит к повтору **всей** транзакции
  (`SerializationRetry`), а не к частично обновлённому snapshot.
- `jobs.result_id` задаётся при создании и уникален, `snapshot_id` уникален: каждая попытка отправит тот же
  `X-Calculation-Id`, повтор worker не создаст второй результат (§2, §5).
- Захват — `FOR UPDATE SKIP LOCKED`; задание с истёкшим lease захватывается снова (восстановление после падения
  worker). Каждый захват — новая попытка (`attempts`) с новым lease; все последующие записи (`extend`, `succeed`,
  `fail`, `retryLater`) проверяют `lease_owner` **и** номер попытки — поздний ответ старого worker ничего не меняет.
- `retryLater` возвращает в `QUEUED` с `not_before` (backoff); после `max_attempts` задание `FAILED`
  с `ATTEMPTS_EXHAUSTED`, без бесконечных повторов.
- `GET .../jobs/{id}`, `GET .../accounts/{a}/jobs`, `POST .../jobs/{id}/cancel` (только `QUEUED`).
- `audit_entries` фиксирует создание заданий без финансовых тел.

## Задача 9: HTTP-клиент Forecast вне транзакции БД

- `ForecastClient` (порт) и `HttpForecastClient` (`java.net.http`) для трёх путей §4:
  `/internal/v1/forecast/calculate`, `/backtest`, `/recurring-detection`. Base URL и таймауты — из конфигурации
  (`ORCHESTRATOR_FORECAST_URL`, `..._CONNECT_TIMEOUT`, `..._REQUEST_TIMEOUT`), в коде не зашиты.
- Заголовки ровно по контракту: `Content-Type`, `Idempotency-Key = jobId`, `X-Job-Id = jobId`,
  `X-Calculation-Id = resultId` (для recurring detection — jobId, как в §4.4). **Нет** `Authorization`, cookies
  и `X-User-*`. Редиректы не выполняются.
- Клиент отказывается работать внутри активной транзакции БД (защита §3 «HTTP после commit»).
- `ForecastWorker.runOnce`: claim (своя короткая транзакция) → проверка, что snapshot принадлежит job и **его
  fingerprint совпадает с сохранённым** → вызов Forecast без транзакции → передача 200-ответа `ResultHandler`.
  Изменённый/повреждённый snapshot не отправляется (`FINGERPRINT_MISMATCH`).
- Классификация: таймаут/сеть/429/503/5xx — повтор с backoff (уважается `Retry-After`) теми же байтами и теми же
  ключами; 400/404/409/422 — `FAILED` с кодом из problem+json, без повторов (плохой payload не повторяется
  бесконечно, §6). Логи — только путь, статус, длительность, без тел.
- До появления валидатора (задача 10) `RejectingResultHandler` никогда не сохраняет непроверенный ответ: job →
  `FAILED RESULT_VALIDATOR_MISSING`, фиктивный прогноз не создаётся.
- `WorkerScheduler` опрашивает очередь (`orchestrator.jobs.poll-interval`, выключается
  `orchestrator.jobs.worker-enabled=false`).

## Задача 10: проверка результата и атомарное сохранение

`ForecastResultValidator` проверяет `ForecastRs` против задания и неизменяемого snapshot **до** сохранения (§6):

- `id = resultId (X-Calculation-Id)`, `jobId`, `snapshotId`, `companyId`, `accountId`, `asOfDate`, `scope`,
  `algorithmVersion`, `inputDataVersion`, `startBalance/startBalanceDate` совпадают с отправленным. Fingerprint
  в ответе draft нет, поэтому связь проверяется через эти идентификаторы и сохранённый input.
- Ровно 30 дней подряд с T+1; `balance[d] = balance[d−1] + inflow − outflow` точной арифметикой;
  `inflow = plannedIn + recurringIn + residualIn`, `outflow = plannedOut + recurringOut + residualOut`.
- Деньги — строки с двумя знаками; потоки ≥ 0; нет NaN/Infinity; неизвестный `quality`/`availability`/`kind` → отказ.
- Недоступный риск: `probability = null`, `scenarioCount = 0`, дневные квантили и вероятности `null`. Доступный —
  только `HISTORICAL_TRAJECTORY_BOOTSTRAP`, вероятность в `[0,1]`, origins не меньше `minCalibrationOrigins`.
  `probabilityCalibrated` всегда `false`.
- `expectedMinimumBalance`, `firstExpectedGapDate`, `maxExpectedShortfall` пересчитываются из дней и должны совпасть.
- Разрывы: внутри горизонта, одного вида не пересекаются, порог = 0 для `CASH_GAP` и `bufferAmount` для
  `BUFFER_BREACH`, минимум и shortfall эпизода согласованы с днями.

Любое нарушение → job `FAILED INVALID_FORECAST_RESULT`, ничего не сохраняется. После проверки **в одной
транзакции** пишутся `forecasts`, `forecast_days`, `forecast_gaps` (и полный ответ в JSONB), а job переводится в
`SUCCEEDED` — только если lease и номер попытки всё ещё принадлежат этому worker. Поздний ответ просроченного
worker отбрасывается; `forecasts.job_id` уникален, второго результата быть не может.

## Задача 11: historical snapshot builder для backtest origins

`BacktestSnapshotService.build(scope, accountId, outerAsOf, request, experimentSettings)` собирает внутренний
`BacktestSnapshot` по §3/§4.3 handoff в одной **REPEATABLE READ** транзакции. `scope` предварительно выдаёт
`CompanyAccess`; принадлежность счёта компании проверяется до чтения финансовых данных.

- Сетка строго `firstOrigin + k * stepDays <= lastOrigin`, шаг 7–30 дней; дата вне сетки не добавляется.
  Не более 100 запрошенных origins, как в контракте Forecast.
- Вход каждого origin восстанавливается через `KnowledgeReader` с исключительной границей начала следующего
  дня в часовом поясе компании. Поздние факты, категории, планы, allocations и подтверждения регулярностей
  не попадают в исторический input; сегодняшняя `daily_observations` не читается.
- `experimentSettings` — явные фиксированные параметры эксперимента, одинаковые для всех origins и согласованные
  с `request.windowWeeks`. Пользовательские account settings, включая исторические, при этом не читаются.
- Scoring восстанавливается отдельно по состоянию на конец `outerAsOf`. `actualDays` содержит только объединение
  принятых 30-дневных горизонтов, без повторяющихся дат, и не передаётся в builder входа origin.
- `INCLUDED` означает подготовленный input, а не выполненный Forecast. Для `SKIPPED` сохраняется причина:
  `IMMATURE_ORIGIN` (горизонт выходит за внешний cutoff), `INSUFFICIENT_HISTORY` (нет восстановимой истории либо
  полного наблюдения для каждого weekday), `NO_START_BALANCE`, `INCOMPLETE_SCORING` (неполный день/неизвестный EOD).
  Известный нулевой EOD пригоден для оценки; неизвестный остаток не превращается в ноль.
- Если все origins пропущены, выбрасывается `BacktestSnapshotException` с кодом `NO_EVALUABLE_ORIGINS` и
  подробностями в `origins()` для отображения вызывающим API как `422`. Snapshot при этом не сохраняется.
- `store(scope, built)` записывает immutable строку `snapshots` с `kind=BACKTEST`, `as_of=outerAsOf`, общим
  `inputDataVersion`, fingerprint и защищённым provenance каждого origin: knowledge cutoff, выбранные версии,
  причины пропусков и параметры replay. Повторная сборка не изменяет сохранённый input.
- Fingerprint не зависит от случайных `snapshotId`/вложенных fingerprints, но учитывает бизнес-ID, версии,
  настройки и scoring outcomes. Профиль текущих forecast snapshots сохранён.

Это внутренний builder для следующего этапа интеграции: создатель backtest job должен вызывать `build` и `store`
в своей общей транзакции вместе с записью задания; HTTP выполняется после commit. Публичные endpoints и приём
результата backtest здесь не добавляются. При `evaluateRisk=false` inputs содержат `calibrationOrigins=[]`;
при `true` применяется builder задачи 12, описанный ниже.

Используется operational replay по `knownAt`. Поздно импортированная выписка не выдаётся за знание на прошлую дату;
offline-допущение о доступности банковских данных задним числом не применяется.

## Задача 12: nested calibration origin builder

`CalibrationOriginBuilder.enrich(scope, built)` дополняет point-only snapshot калибровочными inputs/outcomes.
Он требует существующую транзакцию; `JobService.createForecast` и `BacktestSnapshotService.build` вызывают его
в своей общей **REPEATABLE READ** транзакции. Company/account проверяются повторно, версия outer input должна
совпадать с MVCC-версией компании. Внешних HTTP-вызовов при подготовке нет.

- Для внешнего cutoff T кандидаты идут от `T-30` назад с шагом 7 дней. Выбираются самые недавние пригодные origins
  до `settings.minCalibrationOrigins`; просматривается не более 100 кандидатов, как допускает wire-контракт.
  Политика выбора версионирована (`LATEST_MATURE_GRID_UNTIL_MINIMUM_V1`), не подбирается по ошибкам прогноза.
- Каждый input строится отдельно по историческому knowledge cutoff самого origin, с собственным train-окном
  8/10/12 недель и фиксированными настройками внешнего эксперимента. Категории, планы, remaining allocations
  и подтверждения recurring берутся на эту дату. У каждого вложенного input `calibrationOrigins=[]`.
- Его `actualDays` — ровно 30 полных дней после origin, заканчивающихся не позже T. `actualPayments` содержит
  факты только этих дат; оба набора читаются с knowledge cutoff внешнего input, а не всего backtest.
  Для текущего forecast это зафиксированный момент сборки; для исторического внешнего T — конец дня T
  в часовом поясе компании. Исходы внешнего тестового горизонта `T+1..T+30` в calibration не попадают.
- Scoring-категории заморожены: если факт уже был известен на origin, используется его revision до cutoff.
  Иначе допускается только первоначальная автоматическая категория с неизменяемой `ruleVersion`, использование
  которой на этом счёте подтверждается данными, известными на origin. Поздние USER overrides не используются.
  Если доказать доступность версии нельзя, весь кандидат пропускается как `SCORING_RULES_UNAVAILABLE`;
  неизвестная категория не подменяется сегодняшней или выдуманной. Стабильный counterparty key факта сохраняется.
- Невосстановимые inputs и неполные scoring-потоки пропускаются с причиной. EOD для калибровки ошибок потоков
  может быть неизвестен; неполный день не считается нулевым. Готовые residual/error trajectories Orchestrator
  не вычисляет: сопоставление по замороженным plans/patterns и расчёт ошибок выполняет Forecast.
- Если пригодных origins меньше минимума, передаётся фактический список, включая пустой. Point forecast не
  отклоняется и список не дополняется копиями; доступность риска и `probability=null` определяет Forecast.
- Provenance `CAL1` сохраняет политику/границы отбора, число inputs, причины пропусков, cutoff и версии каждого
  input, использованные scoring category revisions и доступные версии правил. После обогащения fingerprint
  пересчитывается, затем весь снимок вместе с calibration сохраняется неизменяемо.

Текущие forecast jobs получают calibration автоматически. Backtest готовит её отдельно для каждого внешнего
origin только при `evaluateRisk=true`; `false` сохраняет point-only путь задачи 11. Публичные query endpoints
и запуск/приём результата backtest остаются отдельными этапами.

Ошибки — `application/problem+json` с `code`, `traceId`, `errors[]` (путь поля, без значений).
Логи: метод, статус, длительность и requestId; тела, query string, user id и суммы не пишутся.

## Задачи 13–15: результаты, dashboard, backtest jobs и lease recovery

Новые маршруты относительно `/api/v1/orchestrator/companies/{companyId}/accounts/{accountId}`:

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/forecasts?limit=50&offset=0` | Сохранённые прогнозы, `{items: [...]}` |
| GET | `/forecasts/latest` | Последний прогноз; при отсутствии 404 |
| GET | `/forecasts/{id}` | Полный сохранённый результат |
| GET | `/backtests?limit=50&offset=0` | Сохранённые проверки |
| GET | `/backtests/{id}` | Метрики и origins проверки |
| GET | `/backtests/{id}/origins` | Детализация `{items: [...]}` |
| GET | `/dashboard` | Баланс, последние совместимые результаты, планы, gaps, задания и права |
| POST | `/backtest-jobs` | Запуск backtest, `202`; нужен `Idempotency-Key` |

Пример тела запуска backtest:

```json
{
  "asOfDate": "2026-10-01",
  "firstOrigin": "2026-09-01",
  "lastOrigin": "2026-09-01",
  "stepDays": 7,
  "scope": "HISTORY_ONLY",
  "windowWeeks": 12,
  "evaluateRisk": false
}
```

`asOfDate` — закрытая внешняя дата доступных scoring-данных. Эксперимент использует явно выбранное
окно и остальные фиксированные defaults ForecastSettings, а не сегодняшние настройки счёта.
Snapshot, job и idempotency записываются одной REPEATABLE READ транзакцией. Worker вызывает Forecast
после commit. Валидатор проверяет identity, сетку origins, счётчики, горизонты, денежные метрики,
gap ratios и calibration bins. Результат и `backtest_origins` сохраняются вместе с SUCCEEDED атомарно.
Некорректный ответ → FAILED без результата и повторов.

Чтение разрешено OWNER/ACCOUNTANT/VIEWER; запуск — OWNER/ACCOUNTANT. Проверки membership и
account/company выполняются при каждом запросе, включая idempotent replay; чужие ресурсы → 404.
`staleness` вычисляется относительно текущей `companies.data_version`, `createdAt` — из БД Orchestrator.
Сохранённый исходный ответ не переписывается. Latest сортируется по asOf/inputVersion, затем времени и ID;
завершившийся позже старый расчёт не подменяет более свежую дату. Dashboard выбирает backtest с теми же
account/algorithmVersion/window/scope. При отсутствии результата/баланса возвращается null,
при отсутствии достаточной истории quality=INSUFFICIENT. `activeAlerts` пока содержит эпизоды gaps
последнего прогноза; отдельного жизненного цикла acknowledgement в этом API нет.

Lease fencing проверяет owner, attempt **и срок**: после `leaseUntil` нельзя succeed/fail/extend/requeue,
даже если другой worker ещё не забрал job. Claim восстанавливает lease при `leaseUntil <= now`;
после исчерпания попыток — FAILED. HTTP retry сохраняет bytes и идентификаторы; сеть/429/5xx
повторяются с backoff, Retry-After ограничен клиентом, 4xx и невалидный 200 не повторяются.
По умолчанию lease=60s, HTTP timeout=15s; долгий ответ после lease отбрасывается, heartbeat не запускается.
Если увеличиваете timeout Forecast, увеличивайте и lease с запасом на проверку/сохранение.

Проверка из `services/orchestrator`: `./gradlew clean check bootJar`.
Тесты используют реальный embedded PostgreSQL и HTTP-заглушку Forecast; включают tenant/VIEWER,
отзыв membership, idempotency, конкурентные запросы, некорректные ответы и восстановление lease.
`PreprocessorContractTest` пропускается без `PREPROCESSOR_SAMPLE_JSON`.
Production-сеть, полный deployment и подключение реального Forecast отдельно не проверены.

## Исправление стыка Forecast: знание и версии

ForecastSnapshot передаёт `knowledgeCutoff` и `timezone` компании. Текущий input фиксирует момент
сборки, historical input — начало T+1 в timezone компании. Поэтому импортированная сегодня
выписка годится для текущего прогноза на последнюю закрытую дату, но не становится задним числом
историческим знанием. Поля входят в fingerprint, выбранные revisions сохраняются в provenance.
Версии plans/patterns передаются без преобразования (начиная с 0); overdue означает dueDate < asOfDate.

`RealForecastCompatibilityTest` запускает настоящий Forecast JAR и Orchestrator с embedded PostgreSQL.
Проверяет import/finalize, план версии 0, план на T, вызов worker, 30 дней и сохранённый результат;
та же сегодняшняя история недоступна historical replay вчерашнего дня.
Корневая сборка автоматически собирает Forecast bootJar перед тестами Orchestrator.
Standalone-сборка Orchestrator вызывает wrapper соседнего Forecast; оба каталога должны присутствовать.
Deploy двух сервисов должен быть согласован: старые immutable snapshots без новых полей не дополняются
догадками; для replay нужно восстановить knowledgeCutoff/timezone из сохранённого provenance.
