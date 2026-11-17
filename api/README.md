# API schema

`openapi.yaml` is the one description of the HTTP API. Java and TypeScript types are generated from it, and the build holds it to the server:

| Guard | What it checks |
|---|---|
| `ApiCoverageTest` | one operation per route in `docs/RBAC.md` (which `RbacMatrixTest` ties to the registered routes), with the same `x-bms-access`; every `$ref` resolves |
| `ApiSchemaTest` | boots the real app on SQLite and calls every operation, validating each JSON body, problem response and SSE payload against the schema; fails on drift or on an operation it never exercised |
| `RecordGeneratorTest` | the generated Java records compile and match the hand-written web records component by component (name, order, type) |

Response objects are closed (`additionalProperties: false`) with every field required, so a renamed, added or dropped field fails `ApiSchemaTest`. Change the schema in the same commit as the server.

## Java records

```bash
export MAVEN_OPTS="-Xmx448m"; export JAVA_TOOL_OPTIONS="-Xmx448m -XX:+UseSerialGC"
mvn -Papi-codegen -DskipTests process-test-classes
# -> target/generated-sources/openapi/dev/thiru/bms/api/generated/*.java
```

The generator is `src/test/java/dev/thiru/bms/api/RecordGenerator.java`, run by `exec-maven-plugin` in the `api-codegen` profile (off by default, so `mvn verify` is unchanged). It emits plain records with only `java.*` types, shaped like `web.Dtos`: primitives for required response fields, boxed types in request records, string enums as `String`, `Instant`/`LocalDate`/`LocalTime` for times. Mainstream generators emit mutable beans with Jackson and Jakarta annotations, which is why this one is in-repo. A copy of the current output is in `generated-sample/java/`.

Nothing uses the generated records yet. A slice switches when it moves (see `docs/restructure/PLAN.md`): add `target/generated-sources/openapi` as a source root with `build-helper-maven-plugin`, move generation to `generate-sources` (the generator then has to live outside `src/test`), and delete the matching entry from `RecordGeneratorTest.WEB_RECORDS` together with the hand-written record.

## TypeScript types

```bash
NODE_OPTIONS=--max-old-space-size=512 npx --yes openapi-typescript@7.4.4 api/openapi.yaml -o api/generated-sample/schema.d.ts
```

`generated-sample/schema.d.ts` is that output today. The React app will own the real copy:

1. In `web/package.json` add the pinned dev dependency and a script:

   ```json
   "devDependencies": { "openapi-typescript": "7.4.4" },
   "scripts": { "gen:api": "openapi-typescript ../api/openapi.yaml -o src/api/schema.d.ts" }
   ```

2. Commit `web/src/api/schema.d.ts`; CI runs `npm run gen:api && git diff --exit-code src/api/schema.d.ts` so a schema change without regenerated types fails.

3. Use the generated names instead of hand-written interfaces:

   ```ts
   import type { components, paths } from "./schema";

   export type Booking = components["schemas"]["Booking"];
   export type SeatMap = components["schemas"]["SeatMap"];
   type MoviesQuery = paths["/api/movies"]["get"]["parameters"]["query"];
   type Problem = components["schemas"]["Problem"];
   ```

   SSE payloads are `SeatMap` (`snapshot`), `SeatHeldEvent`, `SeatReleasedEvent`, `SeatBookedEvent` and `HeartbeatEvent`. A typed fetch wrapper such as `openapi-fetch` can take `paths` directly; that choice is the UI lane's.
