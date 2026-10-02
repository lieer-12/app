# Room schema snapshots

Room schema JSON snapshots are generated here by KAPT. Future schema changes must add an explicit `Migration` to `LifeManagerDatabase.MIGRATIONS`; destructive migration is not allowed.

Version 5 adds only `app_settings` (persisted preferences) and `app_maintenance`
(local data generation). The ten v4 business table definitions and fields remain
unchanged. `4 -> 5` creates these two tables and seeds id=1 defaults; earlier
migrations remain registered. No generation is imported from a user backup.

Phase 5 backup/restore and generation-based maintenance are not implemented yet.
The v5 snapshot is a schema artifact, not a functional acceptance result.
