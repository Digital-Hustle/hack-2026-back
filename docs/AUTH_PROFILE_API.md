# Auth/Profile: контракт для фронта и Gateway

Версия 0.1.0. Вход — email и одноразовый шестизначный код, без пароля.
Публичный контракт: [OpenAPI](openapi/auth-profile.yaml). Java-интерфейсы и DTO:
[`services/auth-profile`](../services/auth-profile).

Контракт реализован запускаемым сервисом: PostgreSQL/Liquibase, SMTP OTP, выпуск JWT,
ротация refresh, сессии, CSRF и профиль. Настройка и границы интеграции с отдельно
разрабатываемым Gateway описаны в [инструкции запуска](AUTH_PROFILE_RUNTIME.md).
Контрактные тесты дополнены интеграционными; сетевую изоляцию deployment проверяют отдельно.

## Адреса и маршруты

Базовый URL — origin Gateway. Все пути ниже начинаются с `/api/v1/auth-profile`.
Gateway сохраняет полный путь: не применять `StripPrefix` или `RewritePath` для этого маршрута.
Сервис не публикуется непосредственно в интернет. На фронте нет URL внутреннего Auth/Profile.

| Метод | Путь после префикса | Успех | Доступ |
|---|---|---|---|
| GET | `/auth/csrf` | 200 `CsrfTokenRs` | Публичный |
| POST | `/auth/code` | 202 `CodeChallengeRs` | CSRF + Origin |
| POST | `/auth/verify` | 200 `AuthSessionRs` | CSRF + Origin |
| POST | `/auth/refresh` | 200 `AuthSessionRs` | Refresh cookie + CSRF + Origin |
| POST | `/auth/logout` | 204 | CSRF + Origin; access/refresh могут отсутствовать |
| GET | `/auth/sessions` | 200 `SessionsRs` | Access cookie |
| DELETE | `/auth/sessions/{sessionId}` | 204 | Access cookie + CSRF + Origin |
| DELETE | `/auth/sessions` | 204 | Access cookie + CSRF + Origin |
| GET | `/profile/me` | 200 `ProfileRs` | Access cookie |
| PUT | `/profile/me` | 200 `ProfileRs` | Access cookie + CSRF + Origin |

GET CSRF, code, verify, refresh и logout обходят обязательную JWT-проверку Gateway,
в том числе если браузер прислал истёкшую или некорректную access cookie.
Невалидная access cookie на защищённом маршруте возвращает 401.
Публичность auth-маршрутов не отменяет CSRF, проверку Origin и лимиты.
OPTIONS обрабатывает Gateway как CORS preflight без аутентификации.

## Cookies и CSRF

Токены не возвращаются в JSON и не сохраняются фронтом в localStorage.
Gateway читает access JWT из cookie, Auth/Profile выдаёт cookies через отдельные заголовки `Set-Cookie`:

| Имя | Path | Атрибуты | Назначение |
|---|---|---|---|
| `__Host-cashflow-access` | `/` | HttpOnly; Secure; SameSite=Lax; без Domain | Access JWT |
| `__Secure-cashflow-refresh` | `/api/v1/auth-profile/auth` | HttpOnly; Secure; SameSite=Lax; без Domain | Непрозрачный refresh |
| `__Host-cashflow-csrf` | `/` | HttpOnly; Secure; SameSite=Lax; без Domain | Привязка CSRF-токена |

Max-Age/Expires access и refresh соответствуют `accessExpiresAt` и `refreshExpiresAt` ответа.
Конкретные TTL и лимиты настраиваются сервером; значения в примерах не являются SLA.
Удаление cookie использует то же имя, Path и атрибуты, `Max-Age=0` и истёкший Expires.
Gateway сохраняет отдельные `Set-Cookie`, не объединяет их через запятую и не переписывает Path на внутренний путь.

1. При открытии приложения вызвать `GET /auth/csrf` с `credentials: "include"`.
2. Сохранить `token` из JSON в памяти. `headerName` всегда `X-CSRF-Token`.
3. Все POST/PUT/DELETE отправлять с этим заголовком и `credentials: "include"`.
4. Браузер самостоятельно передаёт cookies и Origin; JS не задаёт Cookie/Origin вручную.

Bootstrap устанавливает CSRF cookie, переиспользуя действующую привязку, чтобы не ломать другие вкладки.
Токен должен быть криптографически связан с cookie; одной проверки наличия заголовка недостаточно.
Gateway, а также auth endpoints жизненного цикла сессии проверяют CSRF-привязку и точное совпадение
Origin с настроенным allowlist. Без корректного Origin или CSRF — 403 `CSRF_INVALID`.
GET CSRF также ограничивается разрешёнными origin; произвольный сайт не получает доступ к ответу.
Verify и refresh не меняют действующую CSRF-привязку; после logout/delete-all фронт получает её заново.

Deployment для этого контракта — HTTPS и один site для фронта и Gateway.
При разных origin одного site Gateway задаёт конкретный `Access-Control-Allow-Origin`,
`Access-Control-Allow-Credentials: true`, `Vary: Origin`, разрешает `Content-Type`, `X-CSRF-Token`
и методы таблицы. В `Access-Control-Expose-Headers` входят `Retry-After` и `X-Trace-Id`.
Wildcard origin с credentials запрещён. Для локальной разработки использовать HTTPS proxy;
cross-site cookies с `SameSite=None` потребуют отдельного согласованного изменения конфигурации.

## Вход и создание пользователя

`POST /auth/code`:

```json
{"email":"user@example.com"}
```

Ответ 202:

```json
{
  "challengeId":"11111111-1111-4111-8111-111111111111",
  "expiresAt":"2026-10-24T09:05:00Z",
  "resendAvailableAt":"2026-10-24T09:01:00Z",
  "codeLength":6
}
```

Одинаковый сценарий для нового и существующего email: ответ не сообщает, зарегистрирован ли человек.
Клиент передаёт email без внешних пробелов. Сервер приводит регистр по единой политике поиска.
Пустые/невалидные адреса и длина больше 254 отклоняются. Клиент может предварительно убрать пробелы.
До `resendAvailableAt` повторный запрос возвращает 429 и `Retry-After` в секундах.
Разрешённая повторная отправка создаёт новый challenge и инвалидирует прежний для этого email.
Лимиты применяются по адресу и источнику запросов, независимо от существования пользователя.
Недоступная доставка — 503, а не успешная фиктивная отправка. Код никогда не попадает в ответ или логи.

`POST /auth/verify`:

```json
{"challengeId":"11111111-1111-4111-8111-111111111111","code":"012345"}
```

Код — строка из шести ASCII-цифр; ведущие нули сохраняются. Успех — 200 `AuthSessionRs`
и установка access/refresh cookies. Пользователь и первоначальный профиль создаются при первой
успешной проверке; отдельного публичного register нет. Создание по нормализованному email атомарно.
При новом профиле `displayName` равен null, `version` равна 0.

```json
{
  "sessionId":"22222222-2222-4222-8222-222222222222",
  "accessExpiresAt":"2026-10-24T09:15:00Z",
  "refreshExpiresAt":"2026-11-23T09:00:00Z",
  "user":{
    "id":"33333333-3333-4333-8333-333333333333",
    "email":"user@example.com",
    "displayName":null,
    "version":0,
    "createdAt":"2026-10-24T09:00:00Z",
    "updatedAt":"2026-10-24T09:00:00Z"
  }
}
```

Неизвестный/использованный challenge или неверный код — 401 `CODE_INVALID`;
просроченный — 401 `CODE_EXPIRED`. Превышение числа попыток — 429 `CODE_ATTEMPTS_EXCEEDED`.
Счётчик неверных попыток сохраняется даже при возврате ошибки. Успешный код используется один раз.

## Обновление и завершение сессий

`POST /auth/refresh` не имеет тела. Auth читает только refresh cookie,
атомарно ротирует её и устанавливает новые access/refresh cookies. Ответ — 200 `AuthSessionRs`.
Каждый успешный вход создаёт отдельную сессию; refresh сохраняет её `sessionId`, меняя credential.
Access JWT не принимается вместо refresh. Недействительный/просроченный/отсутствующий refresh —
401 `REFRESH_INVALID`; повтор использованного refresh — 401 `REFRESH_REUSED` с отзывом семейства.
При этих ошибках access/refresh cookies очищаются.

Фронт объединяет одновременные refresh в один запрос (single flight), в том числе координирует
вкладки через Web Locks/BroadcastChannel. Refresh не повторяется автоматически при сетевой ошибке:
исходный запрос мог уже заменить cookie. При 401 на защищённом запросе допускается один refresh
и один повтор безопасного чтения. Записывающий запрос не повторять автоматически, если исход неясен.
После отказа refresh — переход к входу. Ошибки code/verify не запускают refresh.

`POST /auth/logout` отзывает семейство предъявленного refresh и очищает access/refresh/CSRF cookies.
Без refresh или с уже недействительным refresh всё равно возвращает 204 после проверки CSRF/Origin.
Отдельный logout нужен потому, что JWT уже может быть просрочен.

`GET /auth/sessions` возвращает все активные сессии текущего пользователя:
`{"items":[{"id":"...","createdAt":"...","lastSeenAt":"...","expiresAt":"...","current":true}],"total":1}`.
Сортировка: `createdAt DESC, id ASC`; `total` равен размеру полного списка, пагинации здесь нет.
IP, refresh и JWT не раскрываются. `current` определяется по sid доверенного контекста.
`lastSeenAt` — последняя активность, наблюдавшаяся Auth/Profile (включая refresh);
обычные запросы к другим микросервисам это поле не обновляют. `expiresAt` — срок текущего refresh сессии.

`DELETE /auth/sessions/{sessionId}` отзывает только свою сессию; чужая/отсутствующая — 404
`SESSION_NOT_FOUND`. При отзыве текущей сессии очищаются все три cookies.
`DELETE /auth/sessions` отзывает все сессии пользователя, включая текущую, и очищает все три cookies.
Отзыв немедленно запрещает refresh. Без дополнительной проверки отзыва на Gateway уже выпущенный
access JWT для остальных сервисов может действовать до exp; контракт не обещает мгновенный глобальный logout.
Auth/Profile проверяет принадлежность и активность sid при работе с профилем и списком сессий.

## Профиль

`GET /profile/me` возвращает `ProfileRs`. Пользователь определяется из доверенного principal;
публичных методов чтения чужого профиля по ID нет. Email только для чтения; его смена потребует
отдельного подтверждаемого сценария. Организации, ИНН и роли компаний принадлежат Orchestrator.

`PUT /profile/me` заменяет редактируемую часть профиля:

```json
{"displayName":"Иван Петров","version":0}
```

`displayName` обязателен: 1–100 символов и хотя бы один непробельный символ.
`version` — обязательное неотрицательное целое, полученное из последнего `ProfileRs`;
при успехе оно увеличивается на один. Несовпадение версии — 409 `PROFILE_VERSION_CONFLICT`:
фронт перечитывает профиль и предлагает повторить изменение. Обновление в БД должно сравнивать
версию атомарно. Null, пропущенные поля и неизвестные поля (`id`, `email`, `roles`, `companyId` и др.) — 400.
Неизвестные поля отклоняются во всех JSON-запросах. Преобразование чисел в строки и дробей в целые отключается.
Java-реализация должна настроить строгий Jackson, ISO-8601 UTC для Instant и сохранить явный null `displayName`.
Готовая настройка находится в `AuthProfileJson.configure(ObjectMapper)` и проверяется HTTP-тестами библиотеки.

## Ошибки

Все ошибки, включая Gateway, — `application/problem+json` с `Cache-Control: no-store`.
`code` стабилен для ветвления фронта; `title`/`detail` — только для отображения, не для парсинга.

```json
{
  "type":"about:blank",
  "title":"Validation failed",
  "status":400,
  "detail":"Request validation failed",
  "instance":"/api/v1/auth-profile/profile/me",
  "code":"VALIDATION_ERROR",
  "traceId":"e2f819a9c0d54c68",
  "errors":[{"field":"displayName","code":"NotBlank","message":"Must not be blank"}]
}
```

`errors` всегда массив, без rejectedValue; тела, адреса, коды и токены в ошибки/логи не попадают.
Все ответы имеют `X-Trace-Id`, все ответы Auth/Profile — `Cache-Control: no-store`.
Для 429 обязателен `Retry-After` — целое число секунд до новой попытки.
Основные статусы: 400 формат/валидация, 401 нет аутентификации/неверный credential,
403 CSRF, 404 чужая/отсутствующая сессия, 409 версия, 415 Content-Type, 429 лимит,
503 недоступная зависимость, 500 внутренняя ошибка. Для неизвестного code фронт показывает общую ошибку.

## Внутренняя граница Gateway → Auth/Profile

- Только Gateway имеет сетевой доступ к HTTP ingress Auth/Profile; backend-порт наружу не публикуется.
  Compose Auth/Profile не публикует backend/DB порты; разрешённых участников сети проверяют при deployment.
- JWT выпускает Auth, проверяет только Gateway: подпись, allowlist алгоритмов, iss/aud, exp/nbf.
  `sub` — UUID пользователя, `sid` — UUID сессии; оба обязательны на защищённых маршрутах.
- Gateway удаляет все входящие `X-User-*`/`X-Auth-*` без учёта регистра и дубли,
  затем устанавливает ровно один `X-User-Id` и `X-Auth-Session-Id` из проверенного JWT.
- Auth/Profile строит `CurrentUser(userId, sessionId)` из доверенного контекста;
  отсутствующий/некорректный контекст — 401. JwtDecoder/JWKS/resource server в нём нет.
- Gateway удаляет `Authorization` и access cookie перед Auth/Profile.
  Для csrf/code/verify пропускает только CSRF cookie; для refresh/logout — только refresh и CSRF cookies.
  Остальные cookies не пересылаются. На профиль и защищённые sessions cookies не пересылаются:
  CSRF/Origin уже проверены на Gateway, идентификация идёт через контекст.
- На публичных auth-маршрутах клиентские заголовки контекста также удаляются, principal не создаётся.
  Не маршрутизировать `/internal/**`, не включать TokenRelay. Trace/Origin/CSRF передаются по allowlist.

## Пример браузерного запроса

```ts
const base = "/api/v1/auth-profile";
const csrfResponse = await fetch(`${base}/auth/csrf`, { credentials: "include" });
if (!csrfResponse.ok) throw await csrfResponse.json();
const { token, headerName } = await csrfResponse.json();

const response = await fetch(`${base}/auth/code`, {
  method: "POST",
  credentials: "include",
  headers: { "Content-Type": "application/json", [headerName]: token },
  body: JSON.stringify({ email: "user@example.com" }),
});
if (!response.ok) throw await response.json();
const challenge = await response.json();
// Далее verify с challenge.challengeId и введённым пользователем строковым кодом.
```

## Подключение Java API

Сервис находится в Gradle-модуле `:services:auth-profile`. Тонкие `@RestController` реализации
реализуют `AuthController`/`ProfileController`, возвращают DTO через application use cases и
устанавливают нужные заголовки/cookies в `ResponseEntity`. `@AuthenticationPrincipal CurrentUser`
заполняется фильтром доверенного контекста. Он не должен создаваться из тела запроса.
`ApiProblemRs` задаёт JSON для общего exception handler и security handlers, а не заменяет их.

Подключить JSON-настройки при старте Spring Boot:

```java
@Bean
Jackson2ObjectMapperBuilderCustomizer authProfileJsonCustomizer() {
    return builder -> builder.postConfigurer(AuthProfileJson::configure);
}
```

Проверки библиотеки: реальные Spring MVC mappings/Bean Validation, сериализация DTO,
валидность OpenAPI и проверка payload по JSON Schema. Интеграционные тесты сервиса проверяют
OTP/refresh гонки, права/сессии, CSRF/cookies, JWT и доверенный контекст.
Подмену внешних заголовков на Gateway и фактическую изоляцию сети проверяют при его интеграции.

Эталон структуры интерфейсов изучен в [certis-api](https://github.com/DanyaChetvyrtov/certis-api/tree/master/src/main/kotlin/ru/digitalhustle/certis/api/controller).
Код и специфичные поля профиля оттуда не копировались. Согласованные типы API:
[OpenAPI 3.1](https://spec.openapis.org/oas/v3.1.0.html),
[Spring MVC mappings](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-controller/ann-requestmapping.html).
