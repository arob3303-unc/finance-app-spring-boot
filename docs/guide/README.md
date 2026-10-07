# Building a Banking Transaction API — Guide

A guided build of a double-entry banking application in Spring Boot + Angular. The goal is
understanding, not just a working app: every milestone explains the mechanism before using
it, and each one has you build half of it yourself.

## Milestones

| # | Doc | Topic | Status |
|---|---|---|---|
| 0 | [00-how-a-request-works.md](00-how-a-request-works.md) | The request lifecycle, the four layers, Flyway vs `ddl-auto` | **done** |
| 1 | `01-domain-model.md` | `customer` / `account` / `transfer` / `ledger_entry`; money as `BigDecimal`; UUID keys | next |
| 2 | `02-rest-apis-and-verbs.md` | GET/POST/PUT/PATCH/DELETE properly; DTOs; validation; status codes; pagination | |
| 3 | `03-transfers-and-transactions.md` | `@Transactional` and atomicity — the heart of the app | |
| 4 | `04-angular-fundamentals.md`, `05-frontend-talks-to-backend.md` | Components, services, HttpClient, routing, forms, CORS | |
| 5 | `06-auth-jwt-and-security.md` | Spring Security filter chain, JWT, 401 vs 403, Angular interceptors | |
| 6 | `07-concurrency-and-locking.md` | Reproduce a real lost-update bug, then fix it three ways | |
| 7 | `08-idempotency-and-reversals.md` | Idempotency keys; the immutable ledger; reversals | |
| 8 | `09-testing.md` | Mockito, `@WebMvcTest`, Testcontainers, the ledger invariant test | |

## Running the app

```powershell
# 1. Database
docker compose up -d

# 2. API  (both env vars are explained in doc 00, section 7)
$env:JAVA_HOME = "C:\Users\dirtb\.jdks\openjdk-23.0.2"
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
.\mvnw.cmd spring-boot:run
```

| What | Where |
|---|---|
| API | http://localhost:8081 |
| Swagger UI | http://localhost:8081/swagger-ui.html |
| OpenAPI JSON | http://localhost:8081/v3/api-docs |
| Postgres | `localhost:5332`, db `finance`, user `finance-app-idea` |
| psql | `docker exec -it postgres-spring-boot psql -U finance-app-idea -d finance` |

## Conventions in this guide

- **I write one worked example** of each new pattern, heavily commented. **You write the
  siblings** following it, and we review the difference. Sections marked *"You write"* are
  yours — the point is to not be handed the answer.
- Every milestone ends with a **checkpoint** (how to prove it works) and a **break it on
  purpose** list. The second one is not optional padding; causing an error is how you learn
  to recognize it.
- Stack: Spring Boot 4.1.1, Spring Framework 7.0.9, Hibernate 7.4.5, Flyway 12.4.0,
  Postgres 18.6, springdoc 3.1.1, Java 21 target.
- **Most Spring tutorials online target Boot 2.x or 3.x.** Boot 4 moved or removed a lot
  (`spring-boot-starter-web`, `WebSecurityConfigurerAdapter`, `antMatchers()`, and
  per-integration autoconfig modules). The guide flags these traps as we hit them.
