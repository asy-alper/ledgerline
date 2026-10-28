# LedgerLine

**A transactional wallet API built with Java 21 and PostgreSQL.**

LedgerLine is a REST backend for account management, money transfers, and transaction history. It focuses on the parts of financial software that must remain correct under concurrent requests, client retries, and failures: atomic balance updates, durable idempotency, access control, and an auditable ledger.

The application uses the JDK HTTP server and JDBC directly. Business rules are independent of the HTTP adapter, providing a clear foundation for a future Spring Boot migration.

## Features

- **Accounts:** create wallets in TRY, USD, or EUR; inspect balances and paginated transaction history.
- **Atomic transfers:** commit both balance changes, ledger entries, the idempotency record, and the audit event in one PostgreSQL transaction.
- **Concurrency control:** lock accounts in a consistent order and validate the current balance before moving funds.
- **Durable idempotency:** prevent duplicate money movement across retries, application restarts, and multiple application instances sharing the same database.
- **Compensating reversals:** correct completed transfers with an additional inverse transaction while preserving the original record.
- **Balanced ledger:** record two matching entries for each movement and validate account balances against the journal at commit.
- **Authentication and authorization:** register users, issue revocable sessions, enforce account ownership, and restrict administrative actions.
- **Administrative operations:** record externally verified settlements, freeze or close accounts, and reconcile balances against ledger entries.
- **Database migrations and testing:** version the schema with Flyway and verify behavior against real PostgreSQL and HTTP connections.

LedgerLine manages balances within its own database. External bank connectivity, card processing, foreign exchange, and payment-provider settlement are outside the current scope.

## Technology

- Java 21 and JDK `HttpServer`
- PostgreSQL with JDBC and HikariCP
- Flyway SQL migrations
- Jackson for JSON serialization
- Maven Wrapper
- JUnit 5 and Testcontainers
- Docker Compose and a GitHub Actions workflow

Spring, Spring Boot, and ORM frameworks are not used in this version.

## Getting started

### Option 1: Docker Compose

Install Docker with Compose support and start the Docker engine. A host JDK is not required for this option.

From the project root, open PowerShell and create the local configuration:

```powershell
Copy-Item .env.example .env
notepad .env
```

Replace the three `CHANGE_ME...` values with distinct passwords:

- `MIGRATION_DATABASE_PASSWORD`: database initialization and schema migration account.
- `DATABASE_PASSWORD`: restricted application database account.
- `BOOTSTRAP_ADMIN_PASSWORD`: initial application administrator; 12–128 characters.

Set `BOOTSTRAP_ADMIN_EMAIL`, then start the services:

```powershell
docker compose up --build -d
docker compose logs -f api
```

Compose starts PostgreSQL, applies migrations through a separate migration service, and runs the API as `ledgerline_app`. PostgreSQL data is stored in a named volume.

Check readiness:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/health/ready
```

Expected response:

```json
{"status":"UP"}
```

If another PostgreSQL server already uses host port 5432, change the database port mapping in `compose.yaml` to `127.0.0.1:5433:5432`. The internal application connection remains unchanged.

### Option 2: An existing PostgreSQL server

Install JDK 21 or later, configure `JAVA_HOME`, and make PostgreSQL 17 or 18 available. The Maven Wrapper downloads Maven when needed.

Using a PostgreSQL administrator, create a project database role:

```sql
CREATE ROLE ledgerline_migrator LOGIN PASSWORD 'replace-with-your-database-password';
```

Then execute this separately, outside a transaction:

```sql
CREATE DATABASE ledgerline OWNER ledgerline_migrator;
```

Copy `.env.example` to `.env` and configure the connection. `DATABASE_PASSWORD` must match the password of `DATABASE_USER`:

```dotenv
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/ledgerline
DATABASE_USER=ledgerline_migrator
DATABASE_PASSWORD=replace-with-your-database-password
DB_MIGRATE=true
BOOTSTRAP_ADMIN_EMAIL=admin@example.com
BOOTSTRAP_ADMIN_PASSWORD=replace-with-your-own-strong-admin-password
```

Start the application from PowerShell:

```powershell
.\run.ps1
```

The script loads `.env`, builds the application, runs unit tests, and starts the API. Java does not load `.env` automatically. When starting `dev.ledgerline.Main` from IntelliJ IDEA, configure the same environment variables in the run configuration.

Using the schema owner is convenient for local development. For deployment, separate migration and runtime credentials as described in the [operations guide](docs/OPERATIONS.md).

## API workflow

1. Register two users with `POST /v1/auth/register`.
2. Sign in with `POST /v1/auth/login` and retain each session token.
3. Create an account for each user with `POST /v1/accounts`. Accounts start at `0.00`.
4. As an administrator, record a verified settlement with `POST /v1/admin/credits`.
5. As the source account owner, submit `POST /v1/transfers` with a unique `Idempotency-Key`.
6. Read account history with `GET /v1/accounts/{id}/transactions`.
7. As an administrator, inspect `GET /v1/admin/reconciliation`.

Send monetary amounts as decimal strings:

```http
POST /v1/transfers
Authorization: Bearer <session-token>
Idempotency-Key: transfer-2026-000001
Content-Type: application/json

{
  "sourceAccountId": "<source-account-uuid>",
  "destinationAccountId": "<destination-account-uuid>",
  "amount": "125.50",
  "description": "Invoice payment"
}
```

Amounts use `BigDecimal` and PostgreSQL `numeric(19,2)`. JSON numbers, scientific notation, nonpositive amounts, and amounts with more than two decimal places are rejected. Transfers require matching account currencies.

The complete contract is in [openapi.yaml](src/main/resources/openapi.yaml), also served at `/openapi.yaml`. The [HTTP request collection](docs/API_EXAMPLES.http) includes registration, authentication, funding, transfers, replay, history, and reversal examples.

## Transaction guarantees

### Atomicity and concurrent spending

A transfer runs on one JDBC connection inside one database transaction. Source and destination accounts are locked in deterministic UUID order. Ownership, account status, currency, and available balance are checked after acquiring the locks.

An exception rolls back the entire financial transaction. Customer balances cannot become negative. Deferred database checks require exactly two matching journal entries and verify that stored balances equal the journal totals.

### Idempotency and retries

Financial requests require an `Idempotency-Key` containing 8–128 characters from `A–Z`, `a–z`, `0–9`, `.`, `_`, `:`, and `-`.

- A new successful request returns `201` and `Idempotent-Replayed: false`.
- Repeating the same normalized request with the same key returns the existing transfer ID, `200`, and `Idempotent-Replayed: true`.
- Reusing a key for different normalized content returns `409 IDEMPOTENCY_CONFLICT`.
- Uncommitted, failed transactions do not consume the key.

Keys are scoped to the authenticated actor, shared across financial operations, and retained indefinitely. A replay returns the existing transfer with its current status; a later reversal can therefore be reflected in the response.

After a timeout or an uncertain connection failure, retry with the **same key and request content**. A new key represents a new instruction. External funding references are also globally unique; use a provider namespace such as `bank-a:settlement-1234`.

### Rollback and reversal

Rollback discards an unfinished transaction. Reversal compensates for a previously completed transaction by appending an inverse movement.

Only administrators can reverse a transfer, and each original transfer can be reversed once. The recipient must still have sufficient funds. Frozen accounts can participate in an administrative reversal; closed accounts cannot. Original transfers and ledger entries remain in the financial history.

## Authentication and security

- Registration creates `USER` accounts; administrative roles cannot be selected through the API.
- Only the source account owner can initiate a customer transfer.
- Account details and per-account transaction history require account ownership or administrative access.
- Individual transfer records can be read by the source or destination account owner, or an administrator.
- Passwords use PBKDF2-HMAC-SHA256 with a random salt and 600,000 iterations.
- Sessions use 256-bit random tokens. Only token hashes are stored; sessions expire after one hour and can be revoked by signing out.
- Login and registration limits are persisted in PostgreSQL and shared across application instances.
- JSON bodies are limited to 16 KiB; unknown or duplicate fields are rejected.
- Financial history is protected against updates, deletion, and truncation.

`BOOTSTRAP_ADMIN_*` creates an initial administrator. It does not promote an existing regular user or reset an existing administrator's password.

For deployment, configure HTTPS, a restricted runtime database role, backups, and the explicit trusted-proxy allowlist when applicable. See the [operations guide](docs/OPERATIONS.md) for configuration and operating constraints.

## Build and test

On Windows:

```powershell
.\mvnw.cmd test
.\mvnw.cmd -Pintegration verify
```

On macOS or Linux:

```bash
chmod +x mvnw
./mvnw test
./mvnw -Pintegration verify
```

`test` runs unit tests. The `integration` profile additionally runs PostgreSQL and HTTP integration tests. By default, Testcontainers starts PostgreSQL and requires a working Docker engine.

To use an existing, dedicated test database instead:

```powershell
$env:TEST_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:5432/ledgerline_test'
$env:TEST_DATABASE_USER = 'your-test-database-owner'
$env:TEST_DATABASE_PASSWORD = 'your-test-database-password'
.\mvnw.cmd -Pintegration verify
```

The test role must be able to create and drop schemas in that database. Each suite uses its own randomly named schema and removes it afterward. Integration verification fails if the required database or Docker environment is unavailable.

The verified baseline contains **46 passing tests: 2 unit tests and 44 integration tests**, covering concurrency, rollback, idempotency, reversals, authorization, session lifecycle, rate limits, and proxy handling. See the [verification report](docs/TEST_REPORT.md) for the tested environment and scope.

The GitHub Actions workflow is configured to run verification against PostgreSQL after publication. Local Java/PostgreSQL and HTTP verification was completed; Docker Compose execution and hosted CI have not been verified in the delivery environment.

## Project structure

```text
src/main/java/dev/ledgerline/
  Main.java                  Application startup and shutdown
  Config.java                Environment-based configuration
  api/                       HTTP routing, JSON, validation, and errors
  auth/                      Password hashing, sessions, and rate limits
  db/                        Connection pooling and transaction boundaries
  ledger/                    Accounts, transfers, reversals, and reconciliation
src/main/resources/
  db/migration/              Versioned schema and database constraints
  openapi.yaml               API contract
src/test/java/               Unit and PostgreSQL/HTTP integration tests
docs/                       Design, operations, and request examples
```

## Documentation and next steps

- [Architecture](docs/ARCHITECTURE.md): transaction boundaries, locking, ledger invariants, and design tradeoffs.
- [Operations](docs/OPERATIONS.md): database roles, configuration, trusted proxies, shutdown, and backups.
- [HTTP examples](docs/API_EXAMPLES.http): executable requests for the main workflows.
- [Verification report](docs/TEST_REPORT.md): test coverage and validation results.
- [Learning path](docs/LEARNING_PATH.md): implementation walkthrough and a staged Spring Boot migration plan.


Planned development includes a Spring Boot HTTP adapter, improved observability, cursor-based history pagination, and payment-provider adapters. These are future extensions rather than features of the current release.

Ledger consistency checks recompute journal totals, and funding operations share a treasury account per currency. Capacity testing with realistic history sizes is required before deploying a high-volume workload.
