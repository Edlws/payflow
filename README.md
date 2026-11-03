# PayFlow — мини-платёжная система

PET-проект на Spring Boot: сервис переводов между счетами. Показывает, как собрать
платёжный сценарий, в котором деньги не теряются и не задваиваются: идемпотентные
запросы, блокировки строк в БД, транзакционный outbox, события в Kafka, кэш и
rate limit в Redis.

## Что реализовано

- Переводы между счетами с проверкой валюты, статуса счёта и достаточности средств.
- Идемпотентность по заголовку `Idempotency-Key`: повтор запроса возвращает исходный
  результат (HTTP 200 + `Idempotent-Replayed: true`), а не выполняет перевод заново.
- Pessimistic-блокировки обоих счетов в детерминированном порядке — защита от
  двойного списания и дедлоков при встречных переводах.
- Транзакционный outbox + публикация в Kafka: событие и изменение баланса фиксируются
  в одной транзакции БД.
- Kafka-консьюмер (демо-аудит/нотификации), retry и Dead Letter Topic.
- Redis: cache-aside для счетов, идемпотентность, распределённый rate limit (30
  переводов/мин на счёт).
- Flyway-миграции, валидация схемы Hibernate (`ddl-auto: validate`).
- Ошибки в формате RFC 7807 (`application/problem+json`) с `correlationId`.
- OpenAPI/Swagger UI, Actuator, Prometheus-метрики.
- Unit- и интеграционные тесты (Testcontainers: PostgreSQL + Kafka + Redis).

## Стек

Java 21 (собирается на JDK 23), Spring Boot 3.5, Spring Data JPA/Hibernate,
PostgreSQL 16, Flyway, Redis 7, Apache Kafka 3.9 (KRaft), Micrometer,
springdoc-openapi, JUnit 5, Mockito, Testcontainers.

## Архитектура

```
HTTP: POST /api/v1/transfers  (Idempotency-Key)
            │
            ▼
     TransferController
            │
            ▼
      TransferService ──── Redis: результат идемпотентности, rate limit
            │
            ▼
     TransferProcessor ──── @Transactional ──────────────────────┐
            │  SELECT ... FOR UPDATE (оба счёта, ORDER BY id)     │
            │  UPDATE balances                                    │
            │  INSERT transfer (COMPLETED / FAILED)               │
            │  INSERT outbox_event                                │
            ▼                                                     │
          COMMIT ◄────────────────────────────────────────────────┘
            │
            ├── evict payflow:cache:accounts:<id> (Redis)
            │
            └── OutboxPublisher (@Scheduled, FOR UPDATE SKIP LOCKED)
                        │
                        ▼
              Kafka: payments.transfer.events  ──►  TransferEventConsumer
                        │                              (аудит/нотификации)
                        └── ошибки после retry ──► payments.transfer.events.DLT
```

## Быстрый старт

Требования: JDK 21+ и Docker.

```bash
# 1. Инфраструктура: PostgreSQL, Redis, Kafka, Kafka UI
docker compose up -d

# 2. Приложение
./mvnw spring-boot:run
# Windows: mvnw.cmd spring-boot:run

# 3. Проверка
curl http://localhost:8085/actuator/health
```

- API: http://localhost:8085
- Swagger UI: http://localhost:8085/swagger-ui.html
- Kafka UI: http://localhost:8091
- PostgreSQL: `localhost:5433`, БД/пользователь/пароль — `payflow`
- Kafka (с хоста): `localhost:19092`

Порты специально сдвинуты (8085, 5433, 19092, 8091), чтобы не конфликтовать с
типовыми локальными установками. Приложение слушает `8085`; переопределить можно
через `SERVER_PORT`.

Демо-счета создаются миграцией `V4__seed_demo_accounts.sql`:

| ID | Владелец | Баланс | Валюта | Статус |
|---|---|---|---|---|
| `11111111-1111-1111-1111-111111111111` | Alice Johnson | 1000.00 | USD | ACTIVE |
| `22222222-2222-2222-2222-222222222222` | Bob Smith | 250.00 | USD | ACTIVE |
| `33333333-3333-3333-3333-333333333333` | Carol Danvers | 500.00 | USD | BLOCKED |
| `44444444-4444-4444-4444-444444444444` | Eve Adams | 750.00 | EUR | ACTIVE |

## Примеры API

Создать счёт (начальный баланс — только для демо):

```bash
curl -X POST http://localhost:8085/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"John Doe","email":"john@example.com","currency":"USD","initialBalance":300.00}'
```

Перевод Alice → Bob:

```bash
curl -X POST http://localhost:8085/api/v1/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: transfer-2026-11-03-0001' \
  -d '{"sourceAccountId":"11111111-1111-1111-1111-111111111111",
       "targetAccountId":"22222222-2222-2222-2222-222222222222",
       "amount":100.00}'
```

Ответ `201 Created`, статус перевода `COMPLETED`. Повтор с тем же ключом:

```bash
# 200 OK, заголовок Idempotent-Replayed: true, тот же transferId
# балансы не меняются повторно
```

Недостаточно средств — `422` с сохранённым FAILED-переводом в БД:

```json
{
  "type": "urn:payflow:error:transfer-failed",
  "title": "Transfer failed",
  "status": 422,
  "detail": "Insufficient funds: available 350.00 USD",
  "transferId": "…",
  "reason": "INSUFFICIENT_FUNDS",
  "correlationId": "…"
}
```

Другие эндпоинты:

```bash
# Баланс (кэшируется в Redis)
curl http://localhost:8085/api/v1/accounts/11111111-1111-1111-1111-111111111111

# История переводов с пагинацией
curl 'http://localhost:8085/api/v1/accounts/11111111-1111-1111-1111-111111111111/transfers?page=0&size=20'

# Перевод по id
curl http://localhost:8085/api/v1/transfers/<transferId>
```

Готовые запросы также лежат в [`http/requests.http`](http/requests.http).

## Как это работает

### Идемпотентность

Три уровня защиты:

1. Redis-lock `payflow:idempotency:lock:<key>` (SETNX, 30 с) — быстрый отсев
   параллельных дублей, в том числе между инстансами.
2. Redis-кэш результата `payflow:idempotency:result:<key>` (24 ч) — повтор отдаётся
   без обращения к БД.
3. Уникальный индекс `transfers.idempotency_key` — источник истины. Если два
   запроса всё же прошли одновременно, один получит `DataIntegrityViolationException`,
   после чего сервис вернёт уже сохранённый перевод.

Ключ должен быть 8–64 символа: буквы, цифры, `.`, `_`, `:`, `-`. При недоступности
Redis защита деградирует до уникального индекса, а не ломает запросы.

### Балансы и конкурентность

Оба счёта читаются одним запросом `SELECT ... FOR UPDATE` с `ORDER BY id`. Порядок
блокировок одинаков для встречных переводов A→B и B→A, поэтому дедлоки исключены.
Списание и зачисление происходят в одной транзакции с записью перевода.

Бизнес-отказы (недостаточно средств, заблокированный счёт, несовпадение валют)
не выбрасывают исключение: создаётся перевод `FAILED` с причиной и событием
`TRANSFER_FAILED`. Это даёт аудит и корректную идемпотентность — повтор с тем же
ключом вернёт тот же отказ.

### Outbox и Kafka

Событие пишется в таблицу `outbox_events` в той же транзакции, что и перевод.
Планировщик раз в секунду забирает пачку через `FOR UPDATE SKIP LOCKED` — это
безопасно при нескольких инстансах. После подтверждения брокера событие помечается
`PUBLISHED`; ошибки увеличивают `attempts`, после 10 попыток — статус `FAILED`.

Гарантия доставки — at-least-once: консьюмер должен быть идемпотентным по `eventId`
(демо-консьюмер только логирует и считает метрику). Ошибки обработки: 3 ретрая,
затем сообщение уходит в `payments.transfer.events.DLT`.

Пример события:

```json
{
  "eventId": "…",
  "eventType": "TRANSFER_COMPLETED",
  "transferId": "…",
  "sourceAccountId": "…",
  "targetAccountId": "…",
  "amount": 100.00,
  "currency": "USD",
  "status": "COMPLETED",
  "failureReason": null,
  "occurredAt": "2026-11-03T14:11:51.350048Z"
}
```

### Redis

| Ключ | Назначение | TTL |
|---|---|---|
| `payflow:cache:accounts::<id>` | `AccountResponse` в JSON | 10 мин |
| `payflow:idempotency:result:<key>` | id выполненного перевода | 24 ч |
| `payflow:idempotency:lock:<key>` | lock на время обработки | 30 с |
| `payflow:ratelimit:transfer:<accountId>:<minute>` | счётчик попыток | 2 мин |

Кэш инвалидируется после успешного перевода (для обоих счетов). Ошибки Redis при
чтении/записи кэша логируются и не ломают запрос — данные берутся из БД. Rate limit
при недоступности Redis пропускает запросы (fail-open).

### Ошибки

Все ошибки — RFC 7807 Problem Details с полями `type`, `title`, `status`, `detail`,
`instance`, `timestamp` и `correlationId`. `X-Request-Id` из запроса (или
сгенерированный) прокидывается в MDC и в ответ.

| Код | Когда |
|---|---|
| 400 | невалидное тело/параметры, отсутствует `Idempotency-Key` |
| 404 | счёт или перевод не найден |
| 409 | email уже занят, параллельный дубль ещё выполняется |
| 422 | недостаточно средств, счёт заблокирован, несовпадение валют, перевод на себя |
| 429 | превышен rate limit |
| 500 | непредвиденная ошибка |

## Конфигурация

Переменные окружения:

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `SERVER_PORT` | `8085` | HTTP-порт |
| `DB_URL` | `jdbc:postgresql://localhost:5433/payflow` | PostgreSQL |
| `DB_USER` / `DB_PASSWORD` | `payflow` / `payflow` | доступ к БД |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:19092` | Kafka |
| `KAFKA_CONSUMER_GROUP` | `payflow-notifier` | группа консьюмера |
| `OUTBOX_ENABLED` | `true` | включить публикацию outbox |
| `RATE_LIMIT_ENABLED` | `true` | включить rate limit |

Остальные настройки (`app.*`) — в `src/main/resources/application.yml`:
интервалы outbox, TTL кэша/идемпотентности, лимит переводов.

## Тесты

```bash
./mvnw test
```

- `TransferProcessorTest` — unit-тесты бизнес-логики (Mockito, без Docker).
- `TransferApiIntegrationTest` — поднимает PostgreSQL, Kafka и Redis через
  Testcontainers, проверяет полный сценарий: перевод, повтор по идемпотентности,
  балансы, событие в Kafka, отказ при недостатке средств.
  Требует запущенный Docker; без Docker тест автоматически пропускается
  (`@Testcontainers(disabledWithoutDocker = true)`).

## Структура проекта

```
src/main/java/com/petproject/payflow/
├── api/            REST-контроллеры, DTO, обработка ошибок (ProblemDetail)
├── config/         свойства, Redis-кэш, Kafka, OpenAPI
├── domain/         Account, Transfer, OutboxEvent, enum'ы
├── messaging/      TransferEvent, OutboxWriter, OutboxPublisher, консьюмер
├── repository/     Spring Data JPA
├── service/        переводы, идемпотентность, rate limit
└── web/            correlation id фильтр
src/main/resources/db/migration/   Flyway V1–V4
```

## Ограничения и возможные улучшения

- Нет аутентификации/авторизации и мультиарендности — API открыт.
- Начальный баланс при создании счёта — демо-упрощение; в реальной системе нужен
  отдельный контролируемый процесс пополнения.
- Нет конвертации валют: перевод возможен только между счетами в одной валюте.
- События доставляются at-least-once; для «ровно один раз» нужна идемпотентность
  консьюмера и/или транзакционный продюсер.
- Один брокер, replication factor 1 — только для локальной разработки.
- Публикация outbox синхронно ждёт подтверждения брокера внутри транзакции; при
  больших батчах строки блокируются дольше. Под нагрузкой стоит уменьшить батч и
  таймаут или вынести отправку в отдельный пул.
- Направления развития: отдельный сервис нотификаций, ledger/двойная запись,
  reconciliation, метрики и трейсинг (OpenTelemetry), OAuth2, идемпотентный
  консьюмер, Testcontainers-профиль в CI.
