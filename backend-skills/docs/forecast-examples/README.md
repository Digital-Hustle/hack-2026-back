# Синтетические примеры для стыка Orchestrator → Forecast

Это fixtures формы запросов. Они не содержат данных организаторов, не являются результатом работы
реального Forecast и не доказывают качество модели.

| Файл | Назначение |
|---|---|
| `calculate.json` | Полный `ForecastSnapshot` для `/internal/v1/forecast/calculate` |
| `calibration-origin.json` | Один `CalibrationOriginSnapshot` с 7 train-днями и 30 scoring-днями |
| `backtest.json` | `BacktestSnapshot` с одним origin для `/internal/v1/forecast/backtest` |
| `recurring-detection.json` | `RecurringDetectionInput` для `/internal/v1/forecast/recurring-detection` |

## Ожидаемая арифметика

`calculate.json`:

- `startBalance` = `100000.00`;
- в истории каждый день `inflow=10000.00`, `outflow=8000.00`;
- один `ADDITIONAL` план `OUT 160000.00` на `2026-10-29` (T+5);
- минимум баланса `-50000.00`, первый разрыв `2026-10-29`, баланс на T+30 `0.00`;
- качество `LIMITED` из-за одного наблюдения на weekday;
- `calibrationOrigins=[]`, поэтому вероятность риска недоступна (`null`), а не `0`.

`backtest.json`:

- один origin `2026-09-01`, 7 дней train-истории;
- точечный прогноз прибавляет `2000.00` в день;
- фактические scoring-дни подтверждены как нулевые, EOD постоянный `100000.00`;
- ошибка горизонта `h` равна `2000.00 × h`, MAE по 30 дням `31000.00`;
- `evaluateRisk=false`.

`calibration-origin.json`:

- input cutoff `2026-08-01`, 7 полных train-дней;
- scoring `2026-08-02…2026-08-31`, ровно 30 подтверждённых нулевых дней;
- `input.calibrationOrigins=[]`;
- одного origin недостаточно для доступного риска: нужно не меньше `minCalibrationOrigins` (12 в примере).

`recurring-detection.json`:

- семь ежедневных списаний одному контрагенту;
- сами по себе они не доказывают `WEEKLY` или `MONTHLY`; корректный результат может содержать пустой
  список кандидатов либо требовать дополнительного периода наблюдений.

## Важно

- Каждый ForecastSnapshot содержит `knowledgeCutoff` (исключительная Instant-граница известности)
  и `timezone` компании. Для historical inputs используется начало T+1; текущий расчёт использует
  момент сборки. Эти поля участвуют в fingerprint. Версии планов/регулярностей допускают 0.

- UUID, `snapshotId` и `fingerprint` — **структурные заглушки**. Fingerprint из всех нулей не является
  настоящим SHA-256 и не должен приниматься реализацией как вычисленный.
- Перед отправкой реального запроса Orchestrator обязан вычислить fingerprint по каноническому профилю
  из `ORCHESTRATOR_FORECAST_HANDOFF.md`.
- Даты и суммы выбраны только для проверки контракта и арифметики. Не использовать эти файлы как
  production seed, демо-датасет или подтверждение точности прогноза.
- `historyFrom` в `calculate.json` короче `windowWeeks`, чтобы fixture был компактным. Это допустимый
  low-data сценарий, а не рекомендуемая длина истории.
