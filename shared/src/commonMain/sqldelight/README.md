# SQLDelight migrations

The current schema is described by the `.sq` files under
`com/example/notesai/db/`. Historical schema snapshots live in `databases/<version>.db`
and are committed to version control.

`NotesDatabase.Schema.version` is derived as `(number of .sqm migration files) + 1`.
With the Phase 1 sync-prep migration (`1.sqm`) in place, the current version is **2**.

## Changing the schema

1. **Update the `.sq` files** to describe the new schema.
2. **Add a migration** next to the `.sq` files, named after the version it upgrades
   *from*. To go from v1 to v2, create `1.sqm`:
   ```sql
   -- 1.sqm : v1 -> v2
   CREATE TABLE NewTable (...);
   ALTER TABLE NoteEntity ADD COLUMN newColumn TEXT NOT NULL DEFAULT '';
   ```
   SQLDelight runs `1.sqm` for any database still on version 1.
3. **Snapshot the new schema:**
   ```bash
   ./gradlew :shared:generateCommonMainNotesDatabaseSchema
   ```
   This emits `databases/2.db` (and leaves `1.db` untouched).
4. **Verify** that applying `1.sqm` to `1.db` reproduces the `.sq` schema:
   ```bash
   ./gradlew :shared:verifyCommonMainNotesDatabaseMigration
   ```
   This also runs as part of `check`, so CI catches drift.
5. **Commit** the `.sq` change, `1.sqm`, and `2.db` together.

## Notes

- `verifyMigrations = true` is set in `shared/build.gradle.kts`, so a mismatch between
  the migrations and the `.sq` files fails the build instead of silently shipping.
- The desktop driver factory uses the `JdbcSqliteDriver(url, schema = ...)` overload,
  and the Android/iOS drivers are constructed with `NotesDatabase.Schema`; all three
  therefore run `create` for new databases and `migrate` for existing ones.
- Databases created **before** migrations were enabled have `user_version = 0` with the
  tables already present, and will fail with "table already exists". Delete the local
  file once (`desktopApp/notes.db`, or uninstall the iOS/Android app) to re-baseline.
