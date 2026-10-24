# Выполнение плана ForecastService

Все изменения локальны в `services/forecast`; Auth/Profile, Gateway, PreProcessor и Orchestrator не изменены.

| № | Задача | Реализация / проверка |
|---|---|---|
| 1 | Ветка и границы | `feature/forecast-service` от main, самостоятельный каталог |
| 2 | Gradle Java 21 | Wrapper 8.14.2 + SHA-256, Boot, catalog, Checkstyle, JaCoCo |
| 3 | DTO и контракт | request/response records, Bean Validation, strict JSON, `ContractDtoTest` |
| 4 | Расчётная модель | `features/*/model`, MapStruct, API → application, `ArchitectureTest` |
| 5 | Денежная арифметика | BigDecimal, HALF_EVEN, квантили, предел NUMERIC(19,2), `MoneyTest` |
| 6 | Snapshot validation | даты, scope, plans/remainders, completeness, fingerprint, cutoff checks |
| 7 | Дневная история | явные dates, missing≠zero, последнее окно, weekday samples, mismatch warnings |
| 8 | Детектор регулярностей | stable group ID, WEEKLY/MONTHLY, min3, median last3, only DETECTED |
| 9 | Календарь | cap month-end/leap, FOLLOWING Mon–Fri, boundary carry |
| 10 | Связи планов | ADDITIONAL, instance replacement, full-horizon baseline, overdue, conflicts |
| 11 | Residual weekday mean | concrete matched payments only, complete-day denominators, missing excluded |
| 12 | Point forecast | exactly 30 days, component arithmetic, existing negative balance warning |
| 13 | Gap/buffer episodes | adjacent groups, no zero-buffer duplicates, scenario-only episodes |
| 14 | Nested calibration | separate origin input/scoring, mature cutoff and 7-day grid, no recursion |
| 15 | Historical errors | same point engine + separate `CalibrationScorer`, paired residual errors |
| 16 | Bootstrap/risk | whole trajectories, deterministic SHA/LCG48, clipping, daily/period probabilities, quantiles |
| 17 | Rolling-origin grid | requested cutoffs only, SKIPPED reasons, same engine, all-skipped rejection |
| 18 | Метрики | MAE/RMSE, horizons1…30, max-overlap one-to-one gaps, lead/date/shortfall, Brier/bins/coverage |
| 19 | Внутренний HTTP | 3 sync routes, identity headers, safe ProblemDetail, logs, CIDR and workload limits |
| 20 | Проверки и документация | unit + real HTTP + architecture, `.http`, fingerprint helper, run/deploy docs, check/bootJar |

## Приёмочные числа (синтетические)

- Calculate: min `-50000.00`, first gap `2026-10-29`, T+30 `0.00`, LIMITED, probability null.
- Backtest: 1 evaluated origin, 30 days, MAE `31000.00`, RMSE `35505.87`, COMPLETE при evaluateRisk=false.
- Bootstrap: независимый SHA-256/Java LCG48 вектор seed −4807795978131566497,
  989 breach-траекторий из 2000, probability `0.4945`; целые paired paths сохранены.
- HTTP tests проверяют success, `400/403/404/413/415/422/503`, malformed/unknown/null/enum,
  nested fingerprint/cutoff, repeatability, credentials, limits и отсутствие финансовых маркеров в логах.

## Локальная проверка

`./gradlew clean check bootJar` и smoke-запуск упакованного JAR прошли:
`/health` и все три POST вернули ожидаемые ответы. JUnit: 79 тестов без падений;
дополнительный Python fingerprint helper: 3 теста. Checkstyle и ArchUnit проходят.
Отчёт покрытия — `build/reports/jacoco/test/html/index.html`.

## Что не заявляется

Реальные данные/качество прогноза, production нагрузка, deployment firewall/CNI и реальный клиент
Orchestrator не проверены. Durable idempotency/сохранение результатов и реконструкция поздних revisions
— ответственность Orchestrator. История коммитов и PR оформляются отдельно от реализации.
