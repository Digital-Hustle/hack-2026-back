# Бэкенд: архитектура и навыки

## Скиллы

| Навык | Назначение |
|---|---|
| cashflow-architecture | Пять сервисов, две БД, CSV и категоризация, факт/план, прогноз на 30 дней и временная кросс-валидация |
| backend-spring | Java/Spring: feature-first, слои, контроллеры, DTO, MapStruct, БД, миграции, HTTP |
| security-fintech | Границы доверия, организации/роли, безопасный импорт, деньги, секреты и логи |
| springboot-security | Gateway JWT и доверенный principal в сервисах, method security, CSRF |
| test-driven-development | Реализация поведения через проверяемые тесты |
| systematic-debugging | Поиск первопричины и проверка исправления |
| verification-before-completion | Проверки перед заявлением о завершении |

Все находятся в `.agents/skills/<name>/SKILL.md`. Сначала читать cashflow-architecture, затем тематический навык. Архитектурные подробности не дублируются в каждом файле.

## Сервисы и БД

Auth + Profile — своя БД. Orchestrator — своя БД всех бизнес-данных. Gateway, PreProcessor и Forecast без БД. PreProcessor категоризует и передаёт подготовленные операции Orchestrator; Orchestrator сохраняет и вызывает Forecast. Forecast считает, Orchestrator хранит результат и отдаёт фронту.

JWT проверяет Gateway, дальше только доверенный контекст без токенов; граница доверия — закрытая сеть. Брокер, outbox/inbox, отдельные contract-first файлы и ML не требуются.

## Архитектура кода

Feature-first, внутри фичи application/command/query/model/repository/gateway. Controller interface + тонкая implementation. DTO — Java records, MapStruct для преобразований, Bean Validation. Не создавать repository/транзакции в stateless сервисах без необходимости. Подробности и пример — backend-spring/references/.

## Эталоны

- [certis-api](https://github.com/DanyaChetvyrtov/certis-api) — основной источник паттернов: структура фич, command/query, сессии и авторизация, миграции и архитектурные тесты. Auth/Profile и Gateway адаптировать после изучения фактического кода; проверку JWT в каждом сервисе не переносить.
- [sueta-rnd-back](https://github.com/Digital-Hustle/sueta-rnd-back), event-ms — контроллер-интерфейс/реализация, DTO records и MapStruct. Брокер и событийную инфраструктуру не переносить.
- [hackathon-spring-2026-backend](https://github.com/Digital-Hustle/hackathon-spring-2026-backend) — исторические замечания аудита, не эталон копирования приложения.

Ссылки взяты из SOURCES.md исходной репы. Код и закреплённые commits эталонов не включены; их актуальная реализация и лицензия перед копированием проверяются отдельно.
