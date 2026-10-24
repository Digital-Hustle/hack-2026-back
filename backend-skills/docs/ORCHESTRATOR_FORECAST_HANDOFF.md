# Handoff: Orchestrator и ForecastService v1

Документ для реализации Orchestrator и согласования внутреннего HTTP-контракта с ForecastService.
Он фиксирует границу данных между сервисами. Внутренняя схема PostgreSQL может быть другой, если
сохраняются описанные инварианты и внешний payload.

**Статус: проект для согласования двух бэкендеров, не реализованный API.** База — архитектура и
[draft API из Zapas PR #8](https://github.com/Digital-Hustle/zapas/pull/8).
DTO запросов ниже сохраняют его названия. Уточнения для идемпотентности stateless-сервиса и
детализации backtest вынесены в раздел 10; их нужно принять до реализации HTTP-адаптеров.
Документ не вводит обязательный codegen и не содержит кода других сервисов.

## 1. Граница ответственности

Orchestrator — единственный владелец финансовых/организационных данных и своей PostgreSQL.
Auth/Profile сохраняет отдельную БД пользователей и сессий. Orchestrator хранит:

- компании, участники и роли;
- счета и балансные якоря;
- фактические платежи и их категории;
- полнота исторических диапазонов;
- ожидаемые платежи и их исполнения;
- подтверждённые/исключённые регулярности;
- задания, snapshots, сохранённые прогнозы и результаты backtest.

ForecastService — stateless HTTP-вычислитель без PostgreSQL:

- получает полный immutable snapshot;
- не читает БД Orchestrator;
- не вызывает PreProcessor;
- не меняет операции, планы или регулярности;
- не проверяет JWT и не получает JWT;
- возвращает расчёт, который Orchestrator валидирует и сохраняет.

Вызов идёт по закрытой сети от Orchestrator к ForecastService. В production доступ к Forecast
должен быть разрешён только Orchestrator; один `companyId` или внутренний заголовок не заменяет
сетевое ограничение.

## 2. Минимальная модель хранения Orchestrator

Названия таблиц примерные. Важно сохранить поля, версии, append-only историю и ограничения.

### Компании, пользователи и счета

```text
companies
  id uuid primary key
  name, legal_form, inn, kpp, timezone
  owner_user_id uuid
  archived boolean
  data_version bigint
  version bigint
  created_at, updated_at timestamptz

memberships
  company_id uuid
  user_id uuid
  role OWNER|ACCOUNTANT|VIEWER
  version bigint
  joined_at timestamptz
  primary key (company_id, user_id)

accounts
  id uuid primary key
  company_id uuid
  name, bank_name, account_number
  external_company_id varchar
  currency varchar check (currency = 'RUB')
  archived boolean
  version bigint
```

Все запросы к финансовым данным фильтруются по `company_id` и `account_id`. Ссылки на Auth user
не являются внешними ключами в БД Orchestrator. При создании компании OWNER membership записывается
в той же транзакции; один активный OWNER на компанию. Для `accounts` нужен unique `(id, company_id)`
для составных FK финансовых записей. В v1 один расчёт относится к одному RUB-счёту, без консолидации.

### Факты и ежедневная история

```text
payments
  id uuid primary key
  company_id, account_id uuid not null
  import_id uuid
  external_operation_id varchar null
  source_row integer
  operation_date date
  amount numeric(19,2) check (amount > 0)
  direction IN|OUT
  counterparty_key, counterparty_inn, counterparty_name
  purpose text
  category_code varchar
  category_source RULE|COUNTERPARTY|USER|OTHER
  rule_version varchar null
  balance_eod numeric(19,2) null
  version bigint
  known_at, created_at timestamptz

payment_revisions
  id, payment_id, company_id
  category_code, category_source, rule_version
  known_at timestamptz
  actor_id uuid null
  version bigint

daily_observations
  company_id, account_id, date primary key
  inflow, outflow, eod numeric(19,2) null
  status OBSERVED|ZERO_CONFIRMED|MISSING
  balance_mismatch boolean
  input_version bigint

completeness_ranges
  id, company_id, account_id
  from_date, to_date date
  completeness CONFIRMED_COMPLETE|UNKNOWN
  reason, known_at, actor_id

balance_anchors
  id, company_id, account_id
  date date
  balance numeric(19,2)
  source STATEMENT|MANUAL
  reason, known_at, actor_id, supersedes_id
```

Операция является immutable по дате, сумме и направлению. Пользовательская коррекция категории
создаёт revision и не переписывает исходный факт. `balance_eod` из нескольких строк одного дня
сводится к одному согласованному EOD, а не суммируется.

Начальная категория/версия правила тоже сохраняется в истории, не только последующие исправления.
`known_at` означает время фактической доступности записи системе, а `operation_date` — дату платежа;
это разные времена. `daily_observations` — пересчитываемая проекция, не источник исторической истины.
Для старого cutoff её реконструируют из доступных тогда фактов/revisions/completeness либо берут
уже зафиксированный historical snapshot. Читать сегодняшнюю проекцию для прошлой отсечки нельзя.

Balance anchors и completeness append-only. Ручной якорь не изменяет выписку. Нельзя протягивать
старый остаток через неизвестный период: нужен подтверждённый EOD/якорь или полный ряд потоков до T.

Индексы и ограничения:

- `payments(company_id, account_id, operation_date, id)`;
- partial unique `(company_id, account_id, external_operation_id)` для non-null id;
- composite FK/проверки `(account_id, company_id)`;
- `daily_observations(company_id, account_id, date)`;
- входной `company_id` из CSV не даёт права доступа и не выбирает компанию самостоятельно.

### Планы и исполнения

```text
expected_payments
  id, company_id, account_id
  title, direction, amount, due_date
  category_code, counterparty_key
  source MANUAL|INVOICE|TAX|CONTRACT
  external_system, external_id, source_document
  relation ADDITIONAL|REPLACES_RECURRING|REPLACES_BASELINE
  recurring_pattern_id, baseline_counterparty_key
  status OPEN|PARTIALLY_SETTLED|SETTLED|CANCELLED
  settled_amount numeric(19,2)
  known_at, created_at, updated_at, version

expected_payment_revisions
  expected_payment_id, company_id, valid_at, version, payload, actor_id

allocations
  id, company_id, account_id
  expected_payment_id, payment_id, amount
  created_at, actor_id, reversed_at, reversal_actor_id
```

`remainingAmount` в DTO вычисляется как `amount - settledAmount`; `settledAmount` — сумма активных
allocations на выбранный cutoff. Хранить remaining отдельно не обязательно. Если settledAmount
кэшируется в таблице, обновлять его в одной транзакции с allocations/revision/version.
Allocation проверяется в транзакции с блокировкой
плана и платежа; сумма распределений не может превысить ни план, ни факт. Reversal сохраняет историю.
Просроченный открытый план не переносится в завтра автоматически.

Ограничения: одинаковые company/account/direction у плана и факта, amount > 0,
0 ≤ settledAmount ≤ amount, не более одной активной связи plan/payment. При backtest используются
только allocations, созданные к cutoff и ещё не отменённые на нём. Поздняя оплата не уменьшает
remainingAmount исторического плана. Ревизия должна сохранять полное состояние, включая отмену,
dueDate, relation и сумму; `updated_at` текущей строки для этого недостаточно.

Импорт хранится отдельно: `imports`, `import_batches`, `staged_payments`, `import_issues`.
До успешного атомарного finalize staging не попадает ни в историю, ни в прогноз.

### Регулярности и настройки

```text
recurring_patterns
  id, company_id, account_id, stable_key
  direction, category_code, counterparty_key
  status DETECTED|CONFIRMED|EXCLUDED
  schedule_json, amount, observations
  first_seen, last_seen, detector_version
  input_version, version, known_at

recurring_revisions
  pattern_id, company_id, known_at, payload, actor_id, version

account_settings
  company_id, account_id
  settings_json, version, updated_at

settings_revisions
  company_id, account_id, known_at, version, payload
```

Forecast получает только `CONFIRMED` регулярности. Повторный detection не должен затирать
пользовательский `CONFIRMED` или `EXCLUDED` override.

В Forecast передаются готовые categoryCode и стабильный counterpartyKey. Категоризация/проверка CSV
принадлежит PreProcessor, не Forecast. Связь кандидата регулярности с сохранённым правилом определяется
стабильным ключом `(accountId, categoryCode, direction, counterpartyKey)`, а не новой случайной UUID
на каждый detection. Неоднозначные совпадения не превращаются в автоматическое списание.

### Snapshots, jobs и результаты

```text
snapshots
  id uuid primary key
  company_id, account_id uuid
  kind FORECAST|BACKTEST|DETECT_RECURRING
  as_of date
  input_version bigint
  fingerprint char(64)
  payload jsonb
  provenance_json jsonb
  created_at timestamptz

jobs
  id, company_id, account_id
  type FORECAST|BACKTEST|DETECT_RECURRING
  status QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELLED
  snapshot_id, result_id
  progress, error_json
  lease_owner, lease_until, attempts
  created_at, started_at, finished_at

forecasts
  id, company_id, account_id, job_id, snapshot_id
  as_of, horizon, algorithm_version, input_version, scope
  settings_json, risk_json, summary_json, created_at

forecast_days
  forecast_id, date primary key
  planned_in, planned_out, recurring_in, recurring_out
  residual_in, residual_out, inflow, outflow, balance
  balance_p10, balance_p50, balance_p90
  p_below_zero, p_below_buffer

forecast_gaps
  id, forecast_id, company_id, account_id
  kind, from_date, to_date, threshold
  point_min, point_shortfall, probability
  shortfall_p50, shortfall_p90, shortfall_basis

backtests
  id, company_id, account_id, job_id, snapshot_id
  request_json, input_version, algorithm_version
  status, metrics_json, warnings_json, created_at

backtest_origins
  backtest_id, company_id, account_id, as_of
  status EVALUATED|SKIPPED
  reason, forecast_summary_json, metrics_json
```

`snapshots.payload` — immutable audit input. После вызова Forecast его нельзя изменять из-за новых
платежей, планов или пользовательских правок. Результат хранит ссылку на snapshot и его fingerprint.
`provenance_json` хранит сведения о выбранных revisions, правилах подготовки и режиме replay;
это защищённые audit-данные Orchestrator, не произвольное дополнительное поле HTTP DTO.

Дополнительно нужны `idempotency_records` для внешних команд и `audit_entries` без финансовых тел
в логах. Полный response можно хранить в JSONB вместе с нормализованными days/origins для запросов.
Он не заменяет проверку результатов. `forecasts.job_id`/`backtests.job_id` и `jobs.result_id`
уникальны; повтор worker не создаёт второй результат.

## 3. Версии и согласованность

### `dataVersion`

В v1 используем один `companies.data_version`: он увеличивается монотонно при изменении входов:

- новый факт или balance anchor;
- изменение полноты периода;
- изменение категории;
- создание/изменение/cancel/allocation плана;
- подтверждение/исключение регулярности;
- изменение forecast settings.

`version` отдельной записи — optimistic locking для редактирования, `dataVersion` компании —
версия финансовых входов, `snapshotId` — конкретный сохранённый снимок, `fingerprint` — его
вычислительное содержимое. Это четыре разных понятия. Версия компании консервативна: изменение
одного счёта может пометить прогноз другого устаревшим; отдельную версию счёта можно добавить позже.

При создании job Orchestrator читает входы в согласованной транзакции PostgreSQL **REPEATABLE READ**:

1. проверяет `CompanyScope` и права;
2. фиксирует `inputDataVersion`;
3. собирает snapshot;
4. вычисляет fingerprint по правилам раздела 5;
5. сохраняет snapshot и job со статусом `QUEUED`.

HTTP-вызов Forecast выполняется после commit, не внутри транзакции PostgreSQL.

Обычный READ COMMITTED с несколькими SELECT может смешать версии фактов/планов. Нужен единый
MVCC-срез и retry всей транзакции при serialization conflict. Тяжёлую подготовку можно вынести из
транзакции только с последующей проверкой версии перед фиксацией и повторной сборкой при изменении;
частично обновлённый snapshot недопустим.

Если данные изменились после snapshot, старый результат всё равно сохраняется, но становится
`STALE` относительно текущего `dataVersion`. Старый snapshot и результат не переписываются.

### `knownAt` и историческая реконструкция

Для backtest Orchestrator обязан уметь ответить на вопрос: «что система знала на cutoff T?».
Поэтому revisions для категорий, планов, регулярностей, settings и completeness должны иметь
`known_at`/`valid_at`.

Для historical T cutoff известности — конец дня T в `Europe/Moscow` (исключительная верхняя граница:
начало T+1). Для современного прогноза Orchestrator отдельно фиксирует момент знания/сборки:
незавершённый сегодняшний день не объявляется полным и его EOD не выдумывается. Для первых smoke-тестов
используется последний закрытый день. В wire DTO обязательны `knowledgeCutoff` (Instant, исключительная
граница известности) и `timezone` (ZoneId компании). Для текущего расчёта cutoff — момент сборки,
для historical input — начало T+1 в timezone компании. Эти поля входят в fingerprint.
Plans/completeness с моментом известности >= knowledgeCutoff отклоняются; дата операции
по-прежнему ограничена asOfDate. Исторические inputs backtest/calibration не могут расширить
knowledgeCutoff за конец своего дня; поздний импорт разрешён текущему расчёту.

Нельзя для исторического origin брать:

- сегодняшнюю категорию, если на origin она была другой;
- сегодняшний CONFIRMED recurring pattern;
- план, созданный после origin;
- позднее пользовательское исправление;
- факт из scoring-периода.

Если корректный historical state восстановить невозможно, origin получает `SKIPPED` либо исключается
из calibration. Нельзя заменять его текущим snapshot.

Если банковская выписка загружена позже всех её дат, operational replay по `knownAt` и offline
backtest по датам выписки — не одно и то же. Для последнего допустимо только явно отмеченное
допущение «банковские записи были доступны к концу своего дня», с фиксированными правилами
категоризации, без поздних USER overrides/планов. Это не доказательство точности реального replay.
Режим и ограничения сохраняются в provenance/отчёте, а не маскируются под достоверное историческое знание.

## 4. Forecast HTTP API

Base URL внутреннего сервиса, пример: `http://forecast:8080` в закрытой сети. Host/порт задаются
конфигурацией клиента Orchestrator, не зашиваются в бизнес-код.

Все три endpoint синхронные: `200` с вычисленным результатом, а не `202/jobId` от Forecast.
Длительное задание, статус и polling реализует Orchestrator. Forecast не хранит jobs/результаты.

### Общий формат

- UUID — строка; даты — `YYYY-MM-DD`; моменты — UTC ISO-8601 с `Z`.
- `Money` — decimal-строка с ровно двумя знаками, например `"-1500.25"`.
- `PositiveMoney` — такая же строка, но строго > 0. Потоки/settled/remaining ≥ 0, баланс может быть отрицательным.
- Java: деньги `BigDecimal`, версии `long`, даты `LocalDate`, моменты `Instant`.
- Денежное усреднение/квантили округляются `HALF_EVEN`; float/double не используются для денежных итогов.
- Вероятности — JSON number в `[0,1]`, не процент в `[0,100]`.
- Все перечисленные поля обязательны. Nullable-поле передаётся явным `null`, список — `[]`, а не `null`.
- Неизвестные поля/enum/нарушения доменных инвариантов → `422`; невалидный JSON → `400`.
- История уже нормализована и категоризована; CSV, ИНН, назначения платежей и токены Forecast не нужны.

### Вложенные DTO запросов

Названия совпадают с draft Zapas. Это описание wire-формата, не требование генерировать Java из TypeScript.

```typescript
type Id = string;       // UUID
type Date = string;     // YYYY-MM-DD
type Instant = string;  // UTC ISO-8601
type Money = string;
type PositiveMoney = string;
type Direction = "IN" | "OUT";
type ForecastScope = "HISTORY_ONLY" | "WITH_KNOWN_PLANS";

interface ForecastSettings {
  windowWeeks: 8 | 10 | 12;
  horizonDays: 30;
  bufferAmount: Money;          // >= 0
  scenarioCount: number;        // integer 100..5000; default 2000
  minCalibrationOrigins: number; // integer 12..100; default 12
  calibrationStepDays: 7;
  algorithm: "WEEKDAY_MEAN_V1";
}

interface DailyActualRs {
  date: Date;
  inflow: Money | null;
  outflow: Money | null;
  balanceEod: Money | null;
  status: "OBSERVED" | "ZERO_CONFIRMED" | "MISSING";
  paymentCount: number;          // integer >= 0
  balanceMismatch: boolean;
}

interface SnapshotPayment {
  paymentId: Id;
  date: Date;
  amount: PositiveMoney;
  direction: Direction;
  categoryCode: string;
  counterpartyKey: string | null;
}

interface CompletenessRangeRs {
  id: Id;
  accountId: Id;
  fromDate: Date;
  toDate: Date;
  completeness: "CONFIRMED_COMPLETE" | "UNKNOWN";
  reason: string;
  createdAt: Instant;
}

interface Schedule {
  frequency: "WEEKLY" | "MONTHLY";
  weekday: number | null;        // WEEKLY: 1..7; MONDAY=1
  dayOfMonth: number | null;     // MONTHLY: 1..31
  businessDayRule: "NONE" | "FOLLOWING";
}

interface RecurringPatternRs {
  id: Id;
  accountId: Id;
  direction: Direction;
  categoryCode: string;
  counterpartyKey: string | null;
  status: "DETECTED" | "CONFIRMED" | "EXCLUDED";
  schedule: Schedule;
  amount: PositiveMoney;
  observations: number;
  firstSeen: Date;
  lastSeen: Date;
  inputDataVersion: number;
  detectorVersion: string;
  version: number;              // >= 0; совпадает с версией Orchestrator, без +1 при передаче
}

interface SourceDocument {
  externalSystem: string;
  externalId: string;
  documentNumber: string | null;
  issuedDate: Date | null;
}

interface ExpectedPaymentRs {
  id: Id;
  companyId: Id;
  accountId: Id;
  title: string;
  direction: Direction;
  amount: PositiveMoney;
  dueDate: Date;
  categoryCode: string;
  counterpartyKey: string | null;
  note: string | null;
  relation: "ADDITIONAL" | "REPLACES_RECURRING" | "REPLACES_BASELINE";
  recurringPatternId: Id | null;
  baselineCounterpartyKey: string | null;
  source: "MANUAL" | "INVOICE" | "TAX" | "CONTRACT";
  sourceDocument: SourceDocument | null;
  status: "OPEN" | "PARTIALLY_SETTLED" | "SETTLED" | "CANCELLED";
  settledAmount: Money;
  remainingAmount: Money;
  overdue: boolean;             // dueDate < asOfDate; равная дата не просрочена
  knownAt: Instant;
  createdAt: Instant;
  updatedAt: Instant;
  version: number;              // >= 0
}
```

Правила дневной истории:

- Каждый день `historyFrom..asOfDate` представлен ровно один раз, без дыр/дубликатов дат.
- `OBSERVED` — полный день с известными потоками; `ZERO_CONFIRMED` — подтверждённый полный день
  без операций (`inflow/outflow="0.00"`, `paymentCount=0`).
- `MISSING` — потоки/EOD `null`; такой день не входит в знаменатель weekday mean. Частично известные
  операции этого дня не делают его полным. Его `paymentCount` может отражать известные строки.
- Суммы `payments` полного дня совпадают с дневными потоками. `balanceEod` — одно значение, не сумма.
- EOD может быть неизвестен даже при известных потоках; такой день нельзя использовать для MAE остатков.
- Forecast не подменяет `dailyHistory.status` сегодняшней полнотой. Orchestrator передаёт диапазоны
  полноты уже разрешёнными на cutoff, без противоречий с дневной историей.
- `counterpartyKey` стабилен для одного контрагента внутри счёта и правила нормализации; не менять
  его при смене отображаемого имени. При отсутствии ключа нельзя выдумывать связь заменяющего плана.

### 4.1 Рассчитать прогноз

```http
POST /internal/v1/forecast/calculate
Content-Type: application/json
Idempotency-Key: <job-uuid>
X-Calculation-Id: <forecast-uuid>
X-Job-Id: <job-uuid>
```

Вызов не содержит JWT, `Authorization` или пользовательские cookies. Headers `X-User-*` для
вычисления не нужны: snapshot уже создан после проверки прав Orchestrator.

Тело — `ForecastSnapshot`. Ниже только структура; пустая история и условный fingerprint **не являются
готовым запросом для расчёта**. Полный синтетический запрос с 7 днями истории, 14 фактами и одним
будущим планом: [forecast-examples/calculate.json](forecast-examples/calculate.json).

```json
{
  "snapshotId": "11111111-1111-4111-8111-111111111111",
  "companyId": "22222222-2222-4222-8222-222222222222",
  "accountId": "33333333-3333-4333-8333-333333333333",
  "asOfDate": "2026-10-24",
  "knowledgeCutoff": "2026-10-24T21:00:00Z",
  "timezone": "Europe/Moscow",
  "inputDataVersion": 42,
  "algorithmVersion": "WEEKDAY_MEAN_V1",
  "settings": {
    "windowWeeks": 12,
    "horizonDays": 30,
    "bufferAmount": "0.00",
    "scenarioCount": 2000,
    "minCalibrationOrigins": 12,
    "calibrationStepDays": 7,
    "algorithm": "WEEKDAY_MEAN_V1"
  },
  "scope": "WITH_KNOWN_PLANS",
  "startBalance": "100000.00",
  "startBalanceDate": "2026-10-24",
  "historyFrom": "2026-08-02",
  "dailyHistory": [],
  "payments": [],
  "knownPlans": [],
  "recurringPatterns": [],
  "completeRanges": [],
  "calibrationOrigins": [],
  "fingerprint": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
```

Поля `dailyHistory`, `payments`, `knownPlans`, `recurringPatterns`, `completeRanges` и
`calibrationOrigins` обязательны даже при пустом массиве. Forecast не должен достраивать данные
из внешнего состояния.

`historyFrom` — начало реально передаваемой истории. Окно для T: `[T - windowWeeks*7 + 1, T]`.
Если доступно меньше, передаётся короткая история, без выдуманных нулей. При хотя бы одном полном
дне каждого weekday Forecast может вернуть `LIMITED`; если полного дня для какого-либо weekday нет,
расчёт возвращает `422 INSUFFICIENT_HISTORY`. Горизонт при короткой истории всё равно 30 дней.

`algorithmVersion` фиксирует версию всех вычислительных правил, не дату deploy. В примерах используется
`WEEKDAY_MEAN_V1`; изменение правил требует новой версии и новых fingerprints.

`startBalance` — уже вычисленный **B(T)**. `startBalanceDate` указывает дату исходного EOD/якоря ≤ T;
если она раньше T, Orchestrator уже довёл его до T по полным потокам. Forecast не протягивает баланс
второй раз. В учебном примере startBalanceDate=T.

Правила планов и регулярностей:

- `HISTORY_ONLY` → `knownPlans=[]`; подтверждённые регулярности возможны только в состоянии на T.
- `WITH_KNOWN_PLANS` → известные на T планы и их остаток оплаты на T. Передаются только открытые/
  частично исполненные планы с remainingAmount > 0; overdue могут передаваться для предупреждений,
  но не переносятся в будущую дату без решения пользователя.
- `ADDITIONAL` явно добавляет новый поток; recurringPatternId/baselineCounterpartyKey = null.
- `REPLACES_RECURRING` требует recurringPatternId и заменяет один календарный экземпляр, а не дублирует его.
  Две замены одной даты → конфликт. Совпадают account/direction.
- `REPLACES_BASELINE` требует baselineCounterpartyKey: соответствующая базовая часть направления
  исключается **на всём 30-дневном горизонте**, затем добавляются планы. Без подтверждаемой связи →
  `422 UNRESOLVED_PLAN`, не молчаливый двойной учёт.
- `recurringPatterns` в calculate — только CONFIRMED; DETECTED/EXCLUDED не становятся календарём.
  WEEKLY: weekday заполнен/dayOfMonth=null; MONTHLY наоборот. День 31 ограничивается последним днём
  месяца, затем FOLLOWING переносит выходной вперёд. Календарь v1 — Mon–Fri, не официальный календарь.

### 4.2 Nested calibration origin

Для доступного риска внешний snapshot содержит зрелые origins. Ниже только схема расположения
полей, не исполняемый пример: scoring требует 30 полных дней. Полный пример одного origin:
[forecast-examples/calibration-origin.json](forecast-examples/calibration-origin.json).

```json
{
  "input": {
    "snapshotId": "44444444-4444-4444-8444-444444444444",
    "companyId": "22222222-2222-4222-8222-222222222222",
    "accountId": "33333333-3333-4333-8333-333333333333",
    "asOfDate": "2026-08-01",
    "knowledgeCutoff": "2026-08-01T21:00:00Z",
    "timezone": "Europe/Moscow",
    "inputDataVersion": 31,
    "algorithmVersion": "WEEKDAY_MEAN_V1",
    "settings": {
      "windowWeeks": 12,
      "horizonDays": 30,
      "bufferAmount": "0.00",
      "scenarioCount": 2000,
      "minCalibrationOrigins": 12,
      "calibrationStepDays": 7,
      "algorithm": "WEEKDAY_MEAN_V1"
    },
    "scope": "WITH_KNOWN_PLANS",
    "startBalance": "95000.00",
    "startBalanceDate": "2026-08-01",
    "historyFrom": "2026-05-10",
    "dailyHistory": [],
    "payments": [],
    "knownPlans": [],
    "recurringPatterns": [],
    "completeRanges": [],
    "calibrationOrigins": [],
    "fingerprint": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  },
  "actualDays": [],
  "actualPayments": []
}
```

Инварианты:

- `input.asOfDate + 30 days <= outerSnapshot.asOfDate`;
- `actualDays` — ровно 30 полных дней после input cutoff;
- `actualDays` и `actualPayments` используются только для scoring ошибок;
- они не являются features для расчёта origin;
- `input.calibrationOrigins` всегда пуст;
- совпадают company/account, scope, algorithm version и window;
- поздние категории, планы, регулярности и overrides не попадают в input;
- если состояние origin нельзя восстановить — origin не передаётся;
- меньше `minCalibrationOrigins` означает `probability: null`, а не `0`.

Готовые residual-средние/ошибки Orchestrator не считает: он передаёт origin input и outcomes,
Forecast запускает **тот же** point engine, затем отдельный scorer строит ошибки. Категории и
сопоставления scoring-платежей определяются правилами, доступными на origin, без поздних USER overrides.
Forecast не должен получать actualDays в сигнатуре point engine.

Origins уникальны по cutoff, выбираются с фиксированным шагом 7 дней; каждая траектория — 30 пар
ошибок IN/OUT. Bootstrap семплирует траектории целиком с возвратом, а не дни независимо.
Seed — первые 64 бита SHA-256 от `fingerprint + algorithmVersion`, bytes читаются big-endian;
версия алгоритма должна также фиксировать используемый PRNG.

**12 недель истории хватает для point forecast, но не обязательно для риска.** Для 12 зрелых
origins с шагом 7, горизонтом 30 и полным train-окном 12 недель потребуется примерно 191 день:
84 + 30 + 11×7. Внешний input остаётся своим train-окном, более ранняя история содержится в
отдельных inputs calibration origins. Не передавать только текущие 84 дня в надежде восстановить
из них все origins. Короткие origin inputs допускаются только если point engine даёт валидный прогноз.

### 4.3 Backtest

```http
POST /internal/v1/forecast/backtest
Content-Type: application/json
Idempotency-Key: <backtest-job-uuid>
X-Calculation-Id: <backtest-uuid>
X-Job-Id: <backtest-job-uuid>
```

Тело — `BacktestSnapshot`; полный пример:
[forecast-examples/backtest.json](forecast-examples/backtest.json).

```typescript
interface BacktestRq {
  accountId: Id;
  firstOrigin: Date;
  lastOrigin: Date;
  stepDays: number;             // integer 7..30
  scope: ForecastScope;
  windowWeeks: 8 | 10 | 12;
  evaluateRisk: boolean;
}

interface BacktestSnapshot {
  snapshotId: Id;
  companyId: Id;
  accountId: Id;
  inputDataVersion: number;
  request: BacktestRq;
  settings: ForecastSettings;
  originSnapshots: ForecastSnapshot[];
  actualDays: DailyActualRs[];
}
```

- `originSnapshots` — отдельные inputs по каждому cutoff; каждый может иметь собственные зрелые
  calibrationOrigins, если запрошена оценка риска;
- `actualDays` — только scoring для внешнего backtest;
- современные plans/categories/regularity overrides туда не подставляются;
- origin без корректного historical state получает `SKIPPED` с причиной;
- ошибки оцениваемого горизонта не участвуют в его собственной calibration.

Forecast возвращает `BacktestRs`. Orchestrator проверяет schema, company/account, версии и
арифметические инварианты, затем сохраняет `backtests` и `backtest_origins` атомарно.

Сетка origins: `firstOrigin + k*stepDays <= lastOrigin`. Если lastOrigin не попадает в сетку, отдельный
срез на него не добавляется. Orchestrator формирует сетку, Forecast проверяет её независимо. Нет
восстановимого input на запрошенную дату → этот origin SKIPPED, без перехода на сегодняшние данные.
Все origins пропущены → `422`, а не успешный backtest с нулевой ошибкой.

`actualDays` — один ряд фактов того же счёта по объединению scoring-дат. Для каждого origin берутся
только `T+1..T+30`; повторяющиеся dates при пересечении окон не дублируются в payload. Недостаточный
scoring-период помечается пропуском; нулевые/неизвестные EOD не смешиваются.
Агрегация метрик — по `forecast-instance × day`: один банковский день может оцениваться несколько
раз на разных горизонтах. Это не независимые наблюдения для доверительных интервалов.

`settings` backtest — фиксированные параметры эксперимента: одинаковое окно/версия метода для
всех origins, согласованные с request. Это **не** ретроспективное чтение текущих пользовательских
настроек. Окно можно выбрать на ранних validation origins, но поздний final holdout не используется
для подбора. При evaluateRisk=false nested calibration не нужна; при true все её исходы обязаны
заканчиваться до соответствующего внешнего T, а не только до lastOrigin.

`COMPLETE` — все запрошенные срезы оценены и запрошенные метрики доступны. Пропуски или недоступный
запрошенный риск → `PARTIAL`. Успешный HTTP/job не означает высокую точность прогноза.

### 4.4 Recurring detection

```http
POST /internal/v1/forecast/recurring-detection
Content-Type: application/json
Idempotency-Key: <job-uuid>
X-Calculation-Id: <job-uuid>
X-Job-Id: <job-uuid>
```

Тело содержит только историю, доступную на `asOfDate`. Пустой массив ниже — структурный пример.
Запрос с реальными синтетическими строками:
[forecast-examples/recurring-detection.json](forecast-examples/recurring-detection.json).

```json
{
  "accountId": "33333333-3333-4333-8333-333333333333",
  "asOfDate": "2026-10-24",
  "inputDataVersion": 42,
  "payments": []
}
```

Forecast возвращает кандидатов `DETECTED`. Orchestrator самостоятельно сохраняет их, применяет
пользовательские `CONFIRMED/EXCLUDED` overrides и только подтверждённые правила отправляет в
следующий forecast snapshot.

Форма результата: `{ "detectorVersion": "...", "patterns": [RecurringPatternRs, ...] }`.
В каждом кандидате status=DETECTED; id должен быть устойчивым для account/group, а не случайным
при повторном запросе. Orchestrator сопоставляет кандидатов со своей stable_key и не переутверждает
пользовательский EXCLUDED/CONFIRMED. Семь ежедневных оплат в примере не являются недельным/месячным
обязательством; корректный результат может иметь patterns=[].

В draft RecurringDetectionInput нет companyId: account принадлежит ровно одной компании, и проверку
этой связи/прав выполняет Orchestrator до вызова. Forecast не ходит в БД за membership.

## 5. Как собирать snapshot

Для текущего прогноза Orchestrator должен:

1. Проверить доступ пользователя к компании и счёту.
2. Проверить `asOfDate <= today` в timezone компании.
3. Выбрать стартовый баланс на asOf из EOD/anchor без неизвестного периода.
4. Выбрать daily observations до asOf включительно.
5. Выбрать payments до asOf; будущие факты не передавать.
6. Выбрать только планы, известные к snapshot cutoff, и их remaining amount.
7. Выбрать только CONFIRMED recurring patterns, известные на cutoff.
8. Передать completeness ranges и предупреждения о missing/mismatch.
9. Собрать mature calibration origins отдельно.
10. Канонизировать JSON и вычислить SHA-256 fingerprint.
11. Сохранить snapshot и job в одной транзакции.
12. После commit вызвать Forecast.

При изменении любого входа `dataVersion` растёт. Новый job строит новый snapshot; старый job не
получает новые данные задним числом.

### Fingerprint и повторяемость

Для стыка предлагается следующий однозначный профиль хеширования `ForecastSnapshot`:

1. Нормализовать UUID в lower-case, Money в scale 2, моменты в UTC с `Z`.
2. Сортировать dailyHistory/actualDays по date; payments/actualPayments по (date, paymentId);
   knownPlans по (dueDate, id); recurringPatterns по id; completeRanges по (fromDate, toDate, id);
   calibrationOrigins по input.asOfDate. Дубликаты identity/cutoff отклонять, не удалять молча.
3. Исключить `snapshotId` и `fingerprint` из каждого ForecastSnapshot, включая вложенные inputs.
   Не исключать бизнес-ID платежей/планов и inputDataVersion. Headers job/result не входят в hash.
4. Сериализовать JSON: ключи объектов сортируются лексикографически, UTF-8 без BOM, без пробелов/
   переводов строки между элементами. Пустые массивы и nullable-поля сохраняются.
5. `fingerprint = lower-case hex SHA-256(canonical JSON)`.

Исключение fingerprint предотвращает самоссылочный hash; исключение snapshot UUID позволяет
повторно построить тот же вычислительный input без случайной смены seed. Порядок origin outcomes
входит в hash. Все числа в запросах — целые настройки/версии; деньги остаются строками.
Java/Jackson и тестовые JSON должны давать одинаковый digest; этот профиль нужно принять вместе
с DTO, а не вычислять hash от PostgreSQL JSONB text или pretty-printed body.

Идемпотентность хранения обеспечивается **Orchestrator**, а не stateless Forecast:

- внешний одинаковый key/body → тот же сохранённый job/результат; другой body → `409`;
- каждый retry worker отправляет те же bytes snapshot, Idempotency-Key/jobId/resultId;
- Forecast может повторно вычислить запрос после restart; постоянной таблицы replay у него нет;
- deterministic — численные days/risk/metrics и устойчивые IDs при том же input/версии;
  timestamp фактического выполнения/traceId могут различаться;
- уникальный job/result и атомарное сохранение исключают двойной результат в Orchestrator;
- временный bounded cache/in-flight dedup допустим как оптимизация, но не гарантия сохранности.

После получения ответа worker сохраняет его только при действующем lease/attempt; поздний ответ
просроченного worker не перезаписывает результат более нового исполнителя.

## 6. Приём результата Forecast

До сохранения результата Orchestrator проверяет:

- `companyId/accountId/jobId/snapshotId` соответствуют отправленному job;
- `inputDataVersion` соответствует snapshot; локально пересчитанный fingerprint совпадает с
  сохранённым. В draft ForecastRs нет поля fingerprint: нельзя проверять его как отсутствующее
  response-поле; связь ответа проверяется через snapshotId/jobId/id и неизменяемый сохранённый input;
- ровно 30 последовательных дней;
- `balance[d] = balance[d-1] + inflow[d] - outflow[d]` с тем же округлением;
- `inflow = plannedIn + recurringIn + residualIn`;
- `outflow = plannedOut + recurringOut + residualOut`;
- нет вероятности вне `[0,1]`;
- `scenarioCount=0` при недоступном риске;
- `probability=null`, если origins меньше минимума;
- нет `NaN`, `Infinity`, отрицательных PositiveMoney и неизвестных enum;
- gaps не пересекаются неправильно и имеют согласованные даты/shortfall.

Только после всех проверок в одной транзакции сохраняются forecast, days, gaps, risk и alerts, а
job переводится в `SUCCEEDED`. При ошибке Forecast job становится `FAILED` с безопасным Problem,
без выдуманного результата.

### Поля результатов, нужные Orchestrator

`ForecastRs` (имена draft):

| Поля | Смысл |
|---|---|
| id, jobId, snapshotId, companyId, accountId | id результата приходит из X-Calculation-Id; scope/IDs проверяются по сохранённому job |
| asOfDate, horizonDays, scope | исходная дата, ровно 30 дней, режим расчёта |
| algorithm, algorithmVersion, settings, inputDataVersion | воспроизводимость метода и входа |
| startBalance, startBalanceDate | тот же стартовый B(T) и его provenance-дата |
| quality, warnings | GOOD/LIMITED/INSUFFICIENT и `{code,message,date:null\|Date}` |
| riskEstimate | availability, method, probability, scenarioCount, calibrationOriginCount, probabilityCalibrated=false, assumptions[] |
| expectedMinimumBalance, firstExpectedGapDate, maxExpectedShortfall | сводные точечные показатели, nullable дата при отсутствии разрыва |
| days[] | date, plannedIn/Out, recurringIn/Out, residualIn/Out, inflow/outflow, balance; balanceP10/P50/P90 и pBelowZero/Buffer nullable |
| gaps[] | id, kind CASH_GAP/BUFFER_BREACH, fromDate/toDate, threshold, expectedMinimumBalance/Shortfall, probability, shortfallP50/P90, shortfallBasis, riskEstimateAvailability |
| staleness, createdAt | Orchestrator устанавливает актуальность и официальное время сохранения; Forecast не знает текущую версию БД |

Недоступный риск: availability=INSUFFICIENT_HISTORY/INPUT_INCOMPLETE, probability=null,
scenarioCount=0, дневные вероятности/квантили=null. Доступный: method=HISTORICAL_TRAJECTORY_BOOTSTRAP,
probability — доля сценариев с min30 balance < 0; probabilityCalibrated остаётся false.
Планы считаются фиксированными по датам/суммам; задержки платежей этой моделью не покрываются.
Эпизоды shortfallP50/P90 считаются среди сценариев, нарушивших порог в эпизоде
(shortfallBasis=CONDITIONAL_ON_BREACH). Точечный баланс не обязан совпадать с дневным P50.

`BacktestRs`:

- identity/metadata: id, jobId, snapshotId, companyId, accountId, inputDataVersion,
  algorithm/algorithmVersion, scope, windowWeeks, firstOrigin/lastOrigin/stepDays, staleness/createdAt;
- evaluatedOrigins, skippedOrigins, evaluatedDays, maeBalance, rmseBalance;
- horizons[]: horizonDay=1..30, maeBalance, rmseBalance, evaluatedCount;
- gapMetrics: truePositive/falsePositive/falseNegative, precision/recall, medianLeadDays,
  dateMaeDays, shortfallMae;
- riskMetrics: brierScore, evaluatedCount, calibrationBins[], interval80Coverage;
- status COMPLETE/PARTIAL, warnings;
- **для внутреннего стыка предлагается обязательное origins[]**: asOfDate, status EVALUATED/SKIPPED,
  reason:null|string, maeBalance:null|Money, evaluatedDays. В hub draft этой коллекции ещё нет;
  без неё Orchestrator не получит детализацию для backtest_origins (раздел 10).

MAE/RMSE/shortfall — рубли, даты/lead time — дни. Нулевой знаменатель → null, не «100% точности».
Gap matching — однозначное максимальное пересечение дней, один к одному, при равенстве более ранний.
Brier — средний квадрат ошибки **дневной** pBelowZero; bins [0,.2),[.2,.4),…,[.8,1]; пустые bins null.
interval80Coverage — доля известных EOD в [P10,P90]. Это не вероятность 30-дневного события.

Ошибки: `application/problem+json`, поля type/title/status/code/detail/traceId/errors[],
retryAfterSec:null|integer, resourceId:null|UUID. В errors указывать путь поля, не финансовое значение.
Body/назначения/контакты/токены не логировать; для корреляции достаточно безопасного request ID.
Нарушение input-инвариантов → 422, перегрузка/недоступность → 503 с bounded retry; плохой payload
не повторяется бесконечно. На Forecast нет публичного endpoint чтения результатов.

## 7. Порядок реализации Orchestrator

1. Создать companies/memberships/accounts и CompanyScope.
2. Добавить payments, daily observations, completeness и balance anchors.
3. Добавить imports/staging/finalize, если PreProcessor ещё не подключён.
4. Добавить expected payments и allocations с optimistic locking.
5. Добавить recurring patterns и revisions.
6. Добавить append-only revisions с `knownAt` для historical replay.
7. Реализовать canonical snapshot builder для текущего forecast.
8. Реализовать job queue в PostgreSQL без брокера.
9. Реализовать Forecast HTTP client вне DB-транзакции.
10. Реализовать result validator и atomic persistence.
11. Реализовать historical snapshot builder для backtest origins.
12. Реализовать nested calibration origin builder.
13. Добавить публичные query endpoints для dashboard/forecast/backtest.
14. Добавить retry только для безопасного/идемпотентного вызова и lease recovery.
15. Добавить security, tenant, concurrency и integration tests.

## 8. Обязательные тесты Orchestrator

- пользователь компании A не видит account компании B;
- VIEWER не меняет планы и категории;
- два одинаковых POST с одним idempotency key создают один job;
- один payment не распределяется сверх суммы;
- повтор finalize не создаёт факт второй раз;
- изменение категории создаёт revision и увеличивает dataVersion;
- snapshot после изменения не меняет старый snapshot;
- план, созданный после cutoff, отсутствует в historical input;
- поздний category override отсутствует в historical input;
- `origin + 30 > outer cutoff` origin исключается;
- nested calibration input содержит пустой `calibrationOrigins`;
- Forecast вызывается после commit, не внутри транзакции;
- результат с чужим snapshot/job отклоняется;
- результат с 29 или 31 днём отклоняется;
- старый результат становится STALE после изменения входов;
- падение Forecast не создаёт fake forecast;
- lease/retry не создаёт второй результат.

## 9. Критерий готовности стыка

Orchestrator считается готовым к интеграции с Forecast, когда он может:

1. сохранить факты, планы, revisions и версии;
2. собрать deterministic `ForecastSnapshot` без чтения Forecast из БД;
3. восстановить historical state на cutoff либо явно пропустить origin;
4. вызвать три internal endpoints по описанным DTO;
5. проверить и атомарно сохранить результат;
6. доказать тестом отсутствие будущих данных в текущем forecast и backtest.

ForecastService не должен зависеть от того, называется ли таблица `payments`, `transactions` или
`operations`. Для него источником истины является этот HTTP snapshot, а для Orchestrator — его
собственная транзакционная БД и append-only revisions.

## 10. Что двум бэкендерам принять перед реализацией

1. DTO входов и три paths из раздела 4; точные Java-классы можно написать вручную. JSON-примеры рядом
   — синтетические fixtures, не данные организаторов и не измеренные результаты Forecast.
2. Профиль canonical fingerprint из раздела 5 и версия PRNG/алгоритма. Случайный snapshotId не меняет seed.
3. Durable idempotency — Orchestrator. Формулировка hub «Forecast возвращает исходный результат по key»
   не может обещать replay после restart без хранилища; Forecast повторяет deterministic computation.
4. Добавление origins[] в internal BacktestRs. Оно требуется для сохранения детализации;
   это явное уточнение draft, не уже опубликованное поле и не изменение публичных клиентов автоматически.
5. Историческое знание: Orchestrator передаёт knowledgeCutoff/timezone в каждом input, сохраняет provenance и reconstructs revisions.
   Forecast может проверить даты/числа/scoring-изоляцию, но **не может доказать**, что отправленный
   categoryCode не взят из поздней правки: в compact SnapshotPayment нет revision/knownAt. Это обязательный
   интеграционный тест snapshot builder, а не мнимая проверка на стороне Forecast.

### С чего начинать интеграцию

Сначала сделать calculate на сохранённом snapshot без риска → планы/регулярности → history-only
backtest → historical replay планов → nested calibration и сценарный риск. `probability=null` на
первых данных — корректный результат, не блокер point forecast.

Ожидания по учебным fixtures (арифметика, не отчёт выполненного Forecast):

- calculate: B(T)=100000, каждый день IN=10000/OUT=8000, ADDITIONAL OUT=160000 на пятый день.
  Минимум −50000, первый разрыв T+5, на T+30 баланс 0; качество LIMITED, риск null.
- calibration-origin: 7 полных train-дней, затем 30 подтверждённых нулевых scoring-дней;
  вложенных origins нет. Один такой origin недостаточен для риска.
- backtest: один origin с IN=10000/OUT=8000, факт следующих дней без операций, EOD постоянный.
  Ошибка горизонта h = 2000×h; MAE = 31000.00. evaluateRisk=false, точечная проверка полная.
- recurring-detection: ежедневные платежи сами по себе не подтверждают WEEKLY/MONTHLY.

Файлы хендофа: этот Markdown и каталог [forecast-examples](forecast-examples/). Перед запуском
работы проверить совпадение DTO/fingerprint с этими fixtures. Все спорные расширения перечислены
явно; схема БД примерная, а независимость Forecast от её структуры — обязательна.
