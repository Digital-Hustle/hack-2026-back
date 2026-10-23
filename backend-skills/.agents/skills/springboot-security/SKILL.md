---
name: springboot-security
description: Spring Security для cashflow — проверка JWT на Gateway, доверенный principal внутри сети, method security, валидация и CSRF. Используй при реализации security-фильтров и авторизации.
metadata:
  origin: ECC
---

# Spring Security: адаптация проекта

Существенно адаптировано из исходного springboot-security; атрибуция и лицензия в UPSTREAM.md/LICENSE.upstream. Выбор архитектуры задаёт cashflow-architecture, детали docs/GATEWAY_AUTH.md и docs/SECURITY.md.

## Gateway

Валидировать подпись JWT, allowlist алгоритмов, issuer/audience, exp/nbf и принятую модель отзыва. На защищённом маршруте ошибка — 401. До формирования контекста удалить все клиентские X-User-*/X-Auth-* без учёта регистра/дублей. Установить проверенные X-User-Id/X-User-Roles, удалить Authorization и auth cookies перед бизнес-сервисами. Не включать TokenRelay.

## Бизнес-сервисы

Не настраивать oauth2ResourceServer/JwtDecoder/JWKS. Фильтр доверенного контекста создаёт Authentication/CurrentUser из канонических заголовков. Включить method security для @PreAuthorize; актуальные роли организации проверяет Orchestrator через CompanyAccess/CompanyScope, а не только наличие principal.

Фильтр имеет смысл только при закрытом сетевом ingress. Произвольный пользователь не должен иметь доступ к backend-порту. На внутренних маршрутах разрешены только принятые вызывающие; фоновые операции не получают фиктивный admin.

## Внешний cookie API и Auth

CSRF/Origin проверяются на внешней границе; auth endpoints жизненного цикла сессии получают только нужные cookies, бизнес-сервисы их не получают. Refresh хранить хешированно, ротировать атомарно; access не заменяет refresh. Не отключать внешнюю CSRF-защиту из-за отсутствия JWT внутри сети.

Bean Validation для DTO, параметризованные SQL, конфигурация из окружения, лимиты запросов/попыток. 401/403 оформлять единым ProblemDetail, не логировать токены и DTO целиком.

Проверки: истёкший/неверный JWT на Gateway; spoofed headers; отсутствие downstream токенов; 401 без principal; 403 роли; 404 чужого ресурса; CSRF/refresh и сетевые ограничения. JWT-фикстуры только на gateway, в сервисах — доверенный контекст.
