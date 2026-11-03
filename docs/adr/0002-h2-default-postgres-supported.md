# H2 file mode by default, PostgreSQL as a second supported engine

A default install is one process with one data directory (H2 database file, media, published sites) so a self-hoster runs a single container and backs up by copying a folder; PostgreSQL is supported for larger installs. Same shape as slidev-polls: jOOQ for data access, Flyway owns the schema, and the schema stays portable between the two engines (no Postgres-only types or functional/partial indexes).

## Considered Options

- PostgreSQL only — rejected: forces a database server on every personal install.
- Embedded H2 only — rejected: no path to several replicas or managed databases.
