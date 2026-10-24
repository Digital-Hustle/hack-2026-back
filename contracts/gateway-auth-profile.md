# Gateway ↔ Auth/Profile

Источник истины: [OpenAPI](../docs/openapi/auth-profile.yaml), [API guide](../docs/AUTH_PROFILE_API.md), [Gateway routes](../services/gateway-server/src/main/resources/application.yml).

## Методы (публичные пути сохраняются без RewritePath)

| HTTP | Путь после /api/v1/auth-profile | Успех | Требования |
|---|---|---|---|
| GET | /auth/csrf | 200 CsrfTokenRs | Разрешённый Origin; устанавливает CSRF cookie |
| POST | /auth/code | 202 CodeChallengeRs | CSRF/Origin; email |
| POST | /auth/verify | 200 AuthSessionRs | CSRF/Origin; challengeId+code; Set-Cookie access+refresh |
| POST | /auth/refresh | 200 AuthSessionRs | Refresh cookie + CSRF/Origin; ротация |
| POST | /auth/logout | 204 | CSRF/Origin; очистка cookies |
| GET | /auth/sessions | 200 SessionsRs | Access JWT → доверенный principal |
| DELETE | /auth/sessions/{sessionId} | 204 | Access + CSRF/Origin |
| DELETE | /auth/sessions | 204 | Access + CSRF/Origin |
| GET | /profile/me | 200 ProfileRs | Access |
| PUT | /profile/me | 200 ProfileRs | Access + CSRF/Origin, version optimistic locking |

Auth/Profile определяет пользователя по **доверенным** `X-User-Id` и `X-Auth-Session-Id` после Gateway, не по входному UUID или JWT в теле. Gateway удаляет внешние `X-User-*`/`X-Auth-*`, `Authorization`, access-cookie; на lifecycle пересылает только разрешённые cookies. Защищённая запись дополнительно вызывает внутренний CSRF bootstrap в Auth/Profile для проверки привязки токена и cookie.

## Пример browser-flow

```http
GET https://localhost/api/v1/auth-profile/auth/csrf
Origin: https://localhost
```

Ответ: `200`, `Set-Cookie: __Host-cashflow-csrf=...; Secure; HttpOnly; SameSite=Lax`; тело `{"token":"<csrf-from-response>","headerName":"X-CSRF-Token"}`.

```http
POST https://localhost/api/v1/auth-profile/auth/code
Origin: https://localhost
Content-Type: application/json
X-CSRF-Token: <csrf-from-bootstrap>
Cookie: __Host-cashflow-csrf=<cookie>

{"email":"user@example.com"}
```

Ответ 202:

```json
{"challengeId":"11111111-1111-4111-8111-111111111111","expiresAt":"2026-10-24T09:05:00Z","resendAvailableAt":"2026-10-24T09:01:00Z","codeLength":6}
```

```http
POST https://localhost/api/v1/auth-profile/auth/verify
Origin: https://localhost
Content-Type: application/json
X-CSRF-Token: <csrf-from-bootstrap>
Cookie: __Host-cashflow-csrf=<cookie>

{"challengeId":"11111111-1111-4111-8111-111111111111","code":"012345"}
```

Ответ 200 содержит `sessionId`, `accessExpiresAt`, `refreshExpiresAt`, `user: {id,email,displayName,version,createdAt,updatedAt}`. JWT и refresh **только** в отдельных Set-Cookie; в JSON их нет.

```http
GET https://localhost/api/v1/auth-profile/profile/me
Cookie: __Host-cashflow-access=<jwt>
```

```http
PUT https://localhost/api/v1/auth-profile/profile/me
Origin: https://localhost
Cookie: __Host-cashflow-access=<jwt>; __Host-cashflow-csrf=<cookie>
X-CSRF-Token: <csrf>
Content-Type: application/json

{"displayName":"Иван Петров","version":0}
```

`version` относится к ProfileRs (не HTTP If-Match); конфликт → `409 PROFILE_VERSION_CONFLICT`. `refresh` требует refresh cookie, не access; уже использованный refresh → `401 REFRESH_REUSED`. Клиент не должен автоматически повторять запрос refresh при неизвестном сетевом исходе.

## Ошибки и особенности

`application/problem+json`: `type,title,status,detail,instance,code,traceId,errors[]`; `Cache-Control: no-store`, `X-Trace-Id`, `Retry-After` при 429. `401` — отсутствующая/невалидная аутентификация, `403` — Origin/CSRF, `409` — версия, `429` — OTP rate limit, `503` — доставка/зависимость. `Origin` и cookies в примерах выше **схематические**: обычный браузер прикрепляет их сам с `credentials: "include"`. Внутренний Auth endpoint не выставляется наружу.
