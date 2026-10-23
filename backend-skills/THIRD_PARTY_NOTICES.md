# Происхождение и лицензии

Проектные инструкции адаптированы из предоставленной пользователем копии Digital-Hustle/zapas и решений текущего обсуждения. Публичная доступность не заменяет лицензию на материалы; общей лицензии на будущий продукт пакет не задаёт.

- springboot-security: https://github.com/affaan-m/ecc, исходная версия ef648e0 по UPSTREAM.md, MIT. Инструкции существенно переработаны под JWT только на gateway, доверенный контекст и текущие пять сервисов. LICENSE.upstream и UPSTREAM.md сохранены.
- systematic-debugging, test-driven-development, verification-before-completion: https://github.com/obra/superpowers, исходная версия 8ca22db по UPSTREAM.md, MIT. Лицензии и атрибуция сохранены.

backend-spring, security-fintech и cashflow-architecture — проектные инструкции и адаптации. В этом пакете нет брокера/обязательного contract-first/ML/сервисных JWT. Эталоны и ссылки перечислены в docs/BACKEND_GUIDE.md; их код и лицензии перед копированием проверяются отдельно. Датасет и исходники эталонов не включены.

Formatting adaptation: documentation alternatives use distinct variable names; call-chain pseudocode is marked as text; Good/Bad example labels use Markdown instead of custom HTML. Upstream licenses retained.
