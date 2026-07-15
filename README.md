<p align="center">
  <img src="assets/hero.svg" width="900" alt="Java Roadmap — путь Java-разработчика от первого класса до продакшн-надёжности">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-F89820?style=flat-square" alt="Java 21">
  <img src="https://img.shields.io/badge/модулей-10-4F8DFB?style=flat-square" alt="10 модулей">
  <img src="https://img.shields.io/badge/уроков-47-A78BFA?style=flat-square" alt="47 уроков">
  <img src="https://img.shields.io/badge/reliability-14%20уроков-FB7185?style=flat-square" alt="Reliability">
  <img src="https://img.shields.io/badge/license-MIT-34D399?style=flat-square" alt="MIT">
</p>

<p align="center">
  <b>План изучения Java с нуля до уровня, на котором пишут и держат живой продакшн.</b><br>
  Синтаксис и ООП → коллекции и потоки → ORM и Spring → приёмы надёжности, которые отличают инженера от джуна.
</p>

<p align="center">
  <a href="https://roadmap.sh/r?id=65c9ad4bd789a518cf2f4cde">Визуальный roadmap на roadmap.sh</a>
</p>

<p align="center">
  <img src="assets/journey.svg" width="900" alt="Маршрут обучения: Fundamentals → Java Core → Hibernate → Spring → Инструменты → Reliability">
</p>

<details>
<summary><b>Как проходить</b></summary>

<br>

- Модули пронумерованы и идут по нарастающей: каждый следующий опирается на предыдущий
- Внутри урока: сначала теория и схема, потом код, потом практическая задача под конец
- Не пропускай практику. Читать про `Stream API` и написать свой пайплайн это разные навыки
- Модуль «Надёжность» бери после Spring, когда уже есть, на что накладывать эти приёмы
- Готовишься к собесу, сразу загляни в модуль [09 · Собеседование](09-interview/) — там частые вопросы с разбором

</details>

---

## Карта роадмапа

| # | Модуль | О чём | Уровень |
|---|--------|-------|---------|
| 01 | [Java Fundamentals](01-fundamentals/) | синтаксис, конструкции, введение в ООП | с нуля |
| 02 | [Java Core](02-java-core/) | исключения, обобщения, коллекции, сеть, JDBC, Stream API | middle |
| 03 | [Инструменты](03-tools/) | git, Maven, JUnit, Mockito | middle |
| 04 | [Хранение данных](04-persistence/) | Hibernate, Criteria API, базы данных | middle |
| 05 | [Spring](05-spring/) | IoC/DI, Boot, Data JPA, Security, Cloud | middle |
| 06 | [Проектирование](06-design/) | паттерны, REST, архитектура | middle → senior |
| 07 | [Надёжность](07-reliability/) | идемпотентность, ретраи, outbox, saga и ещё 10 приёмов | senior |
| 08 | [Инфраструктура](08-infrastructure/) | CI/CD, Docker, Kubernetes | middle |
| 09 | [Собеседование](09-interview/) | подготовка к интервью Java Dev и Team Lead | все |
| 10 | [Лидерство и рост](10-leadership/) | roadmap тимлида, индивидуальный план развития | lead |

---

## 01 · Java Fundamentals

<sub>7 уроков · с нуля · [индекс модуля →](01-fundamentals/)</sub>

1. [Введение в платформу Java](01-fundamentals/01-intro-platform.md)
2. [Основные конструкции](01-fundamentals/02-control-flow.md)
3. [Практика](01-fundamentals/03-practice.md)
4. [Крестики-нолики в процедурном стиле](01-fundamentals/04-tic-tac-toe.md)
5. [Введение в ООП](01-fundamentals/05-oop-intro.md)
6. [Продвинутое ООП](01-fundamentals/06-oop-advanced.md)
7. [Практика ООП и работа со строками](01-fundamentals/07-strings-practice.md)

## 02 · Java Core

<sub>9 уроков · middle · [индекс модуля →](02-java-core/)</sub>

1. [Объектно-ориентированное программирование](02-java-core/01-oop.md)
2. [Обработка исключений](02-java-core/02-exceptions.md)
3. [Обобщения (generics)](02-java-core/03-generics.md)
4. [Коллекции](02-java-core/04-collections.md)
5. [Средства ввода-вывода](02-java-core/05-io.md)
6. [Работа с сетью](02-java-core/06-networking.md)
7. [Работа с JSON](02-java-core/07-json.md)
8. [JDBC](02-java-core/08-jdbc.md)
9. [Java Stream API](02-java-core/09-streams.md)

## 03 · Инструменты

<sub>4 урока · [индекс модуля →](03-tools/)</sub>

1. [Git](03-tools/01-git.md)
2. [Maven](03-tools/02-maven.md)
3. [JUnit](03-tools/03-junit.md)
4. [Mockito](03-tools/04-mockito.md)

## 04 · Хранение данных

<sub>3 урока · middle · [индекс модуля →](04-persistence/)</sub>

1. [Hibernate](04-persistence/01-hibernate.md)
2. [Hibernate Criteria](04-persistence/02-hibernate-criteria.md)
3. [Базы данных: что читать](04-persistence/03-databases.md)

## 05 · Spring

<sub>5 уроков · middle · [индекс модуля →](05-spring/)</sub>

1. [Spring Core (IoC/DI)](05-spring/01-core.md)
2. [Spring Boot 3](05-spring/02-boot.md)
3. [Spring Data JPA](05-spring/03-data.md)
4. [Spring Security](05-spring/04-security.md)
5. [Spring Cloud](05-spring/05-cloud.md)

## 06 · Проектирование

<sub>5 уроков · middle → senior · [индекс модуля →](06-design/)</sub>

1. [Порождающие паттерны](06-design/01-patterns-creational.md)
2. [Структурные паттерны](06-design/02-patterns-structural.md)
3. [Поведенческие паттерны](06-design/03-patterns-behavioral.md)
4. [REST API](06-design/04-rest.md)
5. [Архитектура](06-design/05-architecture.md)

## 07 · Надёжность и архитектура

<sub>14 уроков · senior · [обзор раздела со схемами →](07-reliability/)</sub>

Продакшн ломается не на алгоритмах, а на сети, дублях, гонках и отказах. Каждый урок построен одинаково: проблема из реального продакшна → теория → механика → пример на Java → грабли → практическая задача под этот приём.

| # | Приём | О чём |
|---|-------|-------|
| 1 | [Идемпотентность](07-reliability/01-idempotency.md) | повтор запроса не должен списывать деньги дважды |
| 2 | [Ретраи](07-reliability/02-retries.md) | безопасно повторять при временных сбоях |
| 3 | [Backoff и jitter](07-reliability/03-backoff-jitter.md) | не превратить ретраи в retry storm |
| 4 | [Rate limiting](07-reliability/04-rate-limiting.md) | ограничить частоту, защитить от перегрузки |
| 5 | [Таймауты](07-reliability/05-timeouts.md) | зависший вызов не должен держать поток вечно |
| 6 | [Circuit Breaker](07-reliability/06-circuit-breaker.md) | fail fast вместо каскадного отказа |
| 7 | [Bulkhead](07-reliability/07-bulkhead.md) | изолировать ресурсы по секциям |
| 8 | [Dead Letter Queue](07-reliability/08-dead-letter-queue.md) | ядовитое сообщение не блокирует очередь |
| 9 | [Дедупликация](07-reliability/09-deduplication.md) | at-least-once без двойной обработки |
| 10 | [Transactional Outbox](07-reliability/10-transactional-outbox.md) | решение проблемы dual-write |
| 11 | [Реконциляция](07-reliability/11-reconciliation.md) | фоновая сверка и починка расхождений |
| 12 | [Saga](07-reliability/12-saga.md) | распределённые транзакции через компенсации |
| 13 | [Кэширование и инвалидация](07-reliability/13-caching.md) | быстро, без stale и cache stampede |
| 14 | [Claim и lease](07-reliability/14-claim-lease.md) | воркеры разбирают таблицу задач без долгих локов |

## 08 · Инфраструктура

<sub>CI/CD · Docker · Kubernetes · [индекс модуля →](08-infrastructure/)</sub>

1. [Инфраструктура и DevOps](08-infrastructure/01-infrastructure.md)

## 09 · Собеседование

<sub>подготовка к интервью · [индекс модуля →](09-interview/)</sub>

1. [Собеседование Java Dev](09-interview/java.md)
2. [Собеседование Team Lead](09-interview/team-lead.md)

## 10 · Лидерство и рост

<sub>для тех, кто растёт в лида · [индекс модуля →](10-leadership/)</sub>

1. [Roadmap тимлида](10-leadership/teamlead-roadmap.md)
2. [Индивидуальный план развития (ИПР)](10-leadership/ipr.md)

---

## Содействие

Нашёл ошибку или знаешь, как объяснить тему понятнее, заводи issue или присылай pull request. Правки в текст, новые задачи и схемы приветствуются. Подробности в [CONTRIBUTING.md](CONTRIBUTING.md).

## Лицензия

Проект под лицензией MIT, подробности в файле [LICENSE](LICENSE).
