# Room schema snapshots

Room schema JSON snapshots are generated here by KAPT. Future schema changes must add an explicit `Migration` to `LifeManagerDatabase.MIGRATIONS`; destructive migration is not allowed.

Version 5 adds only `app_settings` (persisted preferences) and `app_maintenance`
(local data generation). The ten v4 business table definitions and fields remain
unchanged. `4 -> 5` creates these two tables and seeds id=1 defaults; earlier
migrations remain registered. No generation is imported from a user backup.

Phase 5 backup/restore now uses the fixed v5 business schema in a single Room
transaction, together with settings replacement and local generation advancement.
Clear removes business rows but preserves settings. No schema v6 or migration is
needed: the schema itself has not changed. Functional verification is recorded
separately in docs/phase5-acceptance.md; a schema snapshot is not acceptance proof.
