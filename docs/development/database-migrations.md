# Database migrations

Flyway SQL in `backend/src/main/resources/db/migration/` is the executable
application-schema authority. The physical
[catalogue diagram](../architecture/diagrams/mermaid/catalogue-persistence-model.mmd)
is explanatory and must follow the SQL, not replace it.

## Policy

- Use timestamp versions: `VYYYYMMDD_HHMMSS__description.sql`.
- Never edit an applied migration; correct it with a later migration.
- Use PostgreSQL constraints for durable integrity and indexes for evidenced access
  paths.
- Keep module table ownership explicit; modules do not query another module's tables.
- Hibernate schema generation remains disabled.
- The migration role owns DDL; the application runtime role receives only required
  DML privileges.
- Use expand/contract for incompatible changes. Application rollback is valid only
  while the previous code remains schema-compatible.
- Assess locks, duration, storage, backfill bounds, concurrency, and recovery for
  large or destructive changes.

Development seed SQL belongs under `db/dev-seed/`, must be deterministic and
idempotent for disposable use, and is excluded from production-image packaging.

## Validate

```bash
bash scripts/validate-migrations.sh
```

The gate creates a fresh PostgreSQL database, migrates from zero, checks migration
naming/checksums, constraints, seed determinism, and runtime privileges. Persistence
behaviour is tested against PostgreSQL/Testcontainers, not H2.

For a local migrated application:

```bash
bash scripts/local-dependencies.sh up
set -a
source backend/.env
set +a
APPLICATION_FLYWAY_ENABLED=true ./mvnw -pl backend spring-boot:run
```

Add `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/dev-seed` only when
the disposable demonstration catalogue is required. `flyway clean` and destructive
reset are prohibited for persistent environments. Backup/restore and deployment
ordering are owned by the [platform design](../architecture/deployment/mvp-platform-and-delivery.md).
