# ForecastService

Stateless Java 21 / Spring Boot 3.5.7 вычислитель прогноза денежных потоков.
Самостоятельная Gradle-сборка: изменения соседних сервисов не требуются.
Нет БД, ML, брокера, outbox/inbox, JWT decoder или внешних HTTP-вызовов.

## Запуск и проверка

Из `services/forecast`:

```bash
./gradlew clean check bootJar
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
java -jar build/libs/forecast.jar
```

По умолчанию только `127.0.0.1:8080`. `GET /health` возвращает `{"status":"UP"}`.
Проверки: JUnit unit/реальный HTTP, ArchUnit, Checkstyle, JaCoCo.
Отчёты: `build/reports/tests/test/index.html`, `build/reports/jacoco/test/html/index.html`.
Примеры запросов: [`http/forecast.http`](http/forecast.http).

```bash
curl --fail-with-body http://127.0.0.1:8080/internal/v1/forecast/calculate \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' \
  -H 'X-Job-Id: bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' \
  -H 'X-Calculation-Id: aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' \
  --data-binary @src/test/resources/fixtures/calculate.json
```

## API и владение данными

Три синхронных POST, `200` с результатом:

| Путь `/internal/v1/forecast` | Вход | Выход |
|---|---|---|
| `/calculate` | `ForecastSnapshot` | `ForecastRs` |
| `/backtest` | `BacktestSnapshot` | `BacktestRs`, включая `origins[]` |
| `/recurring-detection` | `RecurringDetectionInput` | `detectorVersion`, `patterns[]` |

Все POST требуют UUID `Idempotency-Key`, `X-Job-Id`, `X-Calculation-Id`;
первые два равны. Нельзя передавать `Authorization`/cookies.
Forecast не предоставляет публичного чтения результатов, не хранит jobs/данные и не проверяет membership.
Права, immutable snapshots, durable idempotency, leases и сохранение результатов — Orchestrator.
Повтор одного snapshot/версии детерминирован численно; время выполнения и trace ID могут отличаться.
`staleness=CURRENT` означает соответствие переданному snapshot, **не** сравнение с сегодняшней БД:
Orchestrator устанавливает официальные staleness/createdAt при сохранении.

Money — JSON-строка с двумя знаками, BigDecimal и HALF_EVEN. Вероятности — number `[0,1]`.
Все поля records обязательны, nullable — явный `null`, коллекции — `[]`.
Синтаксический JSON — `400`; поля/enum/инварианты — `422`; конфликт замены — `409`;
лимиты — `413`; overload — `503` с `Retry-After: 1`.
Ошибка — `application/problem+json`, `code`, `traceId`, `errors[]`, `retryAfterSec`, `resourceId`.
Логи содержат только сгенерированный trace ID, HTTP status и duration, без финансового тела.

## Численная модель `WEEKDAY_MEAN_V1`

- Train: последние 8/10/12 недель до T, горизонт — ровно T+1…T+30.
- Каждый день истории явный. ZERO_CONFIRMED входит в знаменатель; MISSING не ноль и исключается.
  Минимум один полный день каждого weekday, иначе `INSUFFICIENT_HISTORY`.
  Короткая/неполная история или несогласованный EOD даёт LIMITED/warnings, не fake forecast.
- `startBalance` уже B(T). `startBalanceDate` — provenance, баланс повторно не протягивается.
  Видимый неизвестный период после якоря требует нового якоря; более ранний provenance проверяет Orchestrator.
- Detection v1: category + direction + stable counterparty, минимум 3 платежа;
  недельные/месячные интервалы и суммы в ±20%, модальный weekday/день месяца,
  медиана последних 3 сумм. Неоднозначные/ежедневные группы не становятся обязательствами.
  Стабильный ID — UUID name-based от account/group. На выходе только DETECTED; в forecast только CONFIRMED.
- Календарь WEEKLY/MONTHLY, день 31 ограничивается концом месяца, FOLLOWING переносит на Mon–Fri.
  Официальные праздники **не** моделируются.
- Историческую регулярность исключаем только по конкретному платежу группы, ±3 дня и ±20% суммы.
  Неоднозначное сопоставление отклоняется, теоретический календарь из произвольного потока не вычитается.
- ADDITIONAL добавляется. REPLACES_RECURRING заменяет ровно один экземпляр с тем же
  account/direction/category/counterparty и dueDate/номинальной датой; две замены конфликтуют.
  Явная dueDate плана сохраняется; FOLLOWING сдвигает только регулярный календарь.
  REPLACES_BASELINE убирает связанное направление контрагента из residual на всём горизонте.
  Неоплаченная часть только, overdue не переносится автоматически.
- Считаются отдельные weekday means residual IN/OUT, округляются до cents **до** накопления B(d).
  Итоги вне NUMERIC(19,2) отклоняются.

## Сценарный риск и бэктест

Риск: HISTORICAL_TRAJECTORY_BOOTSTRAP, минимум 12 зрелых origins на сетке 7 дней,
по умолчанию 2000 сценариев. Невосстановимые origins можно пропустить на этой сетке.
Каждый input отдельно fit тем же point engine; scoring outcomes **не** входят в его сигнатуру.
Nested inputs имеют `calibrationOrigins=[]`, origin+30 ≤ свой внешний T, одинаковые scope/account/company/window/version.
12-недельный point input сам по себе не гарантирует историю риска: для 12 полных origins нужно около 191 дня.

Bootstrap семплирует **целые парные** 30-дневные IN/OUT error vectors с возвратом.
Seed: первые 64 бита SHA-256 UTF-8(`fingerprint + algorithmVersion`), big-endian;
PRNG `java.util.Random` LCG48. `max(0, residual + error)`; планы/регулярности фиксированы.
Дневные P10/P50/P90 — линейная интерполяция `(n−1)*p`, HALF_EVEN.
Вероятность периода — доля `min30 B < 0`, не сумма дневных вероятностей.
ProbabilityCalibrated всегда false. Недостаток origins → probability=null, scenarioCount=0.
Эпизоды: объединение point B<threshold и daily p≥0.3, соседние дни склеиваются;
буфер 0 не дублирует CASH_GAP. Shortfall P50/P90 условны на breach эпизода.

Backtest проверяет `firstOrigin + k*stepDays <= lastOrigin`, шаг 7…30, до 100 origins.
Не добавляет off-grid lastOrigin и не подставляет современные планы.
Недостаточный input/scoring — SKIPPED; неизвестные EOD исключаются, EOD=0 оценивается;
все SKIPPED → `422`. Пропуски/неполные EOD/недоступный запрошенный риск → PARTIAL.
MAE/RMSE агрегируются по **forecast-instance × day**, отдельно горизонты 1…30.
Gap matching — максимальное суммарное пересечение, один к одному, deterministic earlier-date ties.
Дневной Brier, 5 calibration bins, coverage [P10,P90]; нулевые знаменатели — `null`.
Нет MAPE, «процента валидации» или обещания измеренной банковской точности.

## Fingerprint и fixtures

Профиль совместим с [`../../backend-skills/docs/ORCHESTRATOR_FORECAST_HANDOFF.md`](../../backend-skills/docs/ORCHESTRATOR_FORECAST_HANDOFF.md):
UUID lower-case, Instant UTC, Money scale 2, сортированные массивы и ключи,
из **каждого** ForecastSnapshot исключены только snapshotId/fingerprint, explicit null/[] сохранены,
compact UTF-8 JSON → SHA-256. Forecast проверяет outer и nested fingerprints.

```bash
python3 scripts/fingerprint.py src/test/resources/fixtures/calculate.json
python3 scripts/fingerprint.py my-snapshot.json --write
```

Локальные fixtures синтетические, не данные организаторов. Заглушки hash заменены настоящими;
у historical completeness исправлен поздний createdAt на дату соответствующего cutoff.
calculate: min −50000.00, gap 2026-10-29, T+30 0.00; backtest MAE 31000.00.
Проектные исходные хендоф/fixtures вне Forecast не изменены.

## Сеть и ограничения

По умолчанию bind и caller allowlist — loopback. Для закрытой сети:

```bash
FORECAST_BIND_ADDRESS=0.0.0.0 \
FORECAST_ALLOWED_CALLER_CIDRS=10.20.30.5/32 \
java -jar build/libs/forecast.jar
```

Allowlist использует **socket peer**, не X-Forwarded-For/Forwarded. При прокси peer — его адрес;
прокси должен сам ограничивать Orchestrator. Не разрешать широкую общую сеть как замену идентичности.
Нужны firewall/NetworkPolicy, запрет публикации порта и доступ только Orchestrator.
Deployment-пример: [`deploy/network-policy.yaml`](deploy/network-policy.yaml), не выполненный production deploy.

| Env | Default | Назначение |
|---|---|---|
| FORECAST_PORT / FORECAST_BIND_ADDRESS | 8080 / 127.0.0.1 | listener |
| FORECAST_ALLOWED_CALLER_CIDRS | 127.0.0.1/32,::1/128 | trusted numeric peers |
| FORECAST_MAX_REQUEST_BYTES | 16777216 | body, включая chunked |
| FORECAST_MAX_CONCURRENT_REQUESTS | 2 | parsing + calculation; без unbounded очереди |
| FORECAST_MAX_TOTAL_PAYMENTS | 250000 | все nested/train/scoring payments |
| FORECAST_MAX_TOTAL_DAYS | 150000 | все nested/train/scoring days |
| FORECAST_MAX_SNAPSHOTS | 2048 | полные point inputs |
| FORECAST_MAX_SCENARIO_CELLS | 3000000 | суммарные requested scenarios × 30 |

JSON depth≤100, string≤8192, number≤32 символов; header≤8KB, bounded Tomcat pool.
Нет persistent cache, гарантий wall-clock deadline или production load-test.
Реальная интеграция с Orchestrator и production-сеть здесь не проверены.
Late `knownAt` категорий/recurring revisions отсутствует в compact DTO — правильный historical state
и revisions/provenance обязан доказать snapshot builder Orchestrator интеграционным тестом.
