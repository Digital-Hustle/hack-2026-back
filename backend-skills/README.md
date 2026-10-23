# Полный пакет инструкций бэкенда

Внутри 7 скиллов, AGENTS.md/CLAUDE.md и техническая документация. Согласованы Auth/Profile, Gateway, PreProcessor, Orchestrator и Forecast; две БД; категоризация в PreProcessor; JWT только на Gateway; HTTP без брокера; простой прогноз и временная кросс-валидация без ML.

## Использование

Перенести **содержимое** этой папки в корень репозитория: AGENTS.md, CLAUDE.md, docs/ и скрытую .agents/. Если файлы уже существуют, заменить старую версию именно этого пакета, сохранив остальные инструкции проекта. Не оставлять рядом прежние backend-skils/ с противоречащими копиями.

Стык Orchestrator → ForecastService: [docs/ORCHESTRATOR_FORECAST_HANDOFF.md](docs/ORCHESTRATOR_FORECAST_HANDOFF.md) и [docs/forecast-examples/](docs/forecast-examples/).

В Finder скрытая .agents видна по Cmd+Shift+точка. Для просмотра правил начать с docs/BACKEND_GUIDE.md. Дополнительная установка скилла отдельно не нужна.

Пакет содержит инструкции, не реализацию приложения, исходники эталонов или датасет. Задачи, роли команды и дедлайны не включены. Лицензии сторонних навыков сохранены.
