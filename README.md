# BookMyShow on Jolt

A movie ticketing app (cities, movies, shows, seat holds, payments, tickets) built on [Jolt](https://github.com/THIRU-1074/custom-Http-Server), an Express-style Java HTTP framework. It is Jolt's reference app: every framework gap it hits becomes a Jolt issue, gets fixed upstream, and is listed in [docs/JOLT_GAPS.md](docs/JOLT_GAPS.md).

Status: bootstrap. The contract is [docs/SPEC.md](docs/SPEC.md).

## Run

Requires JDK 21+ and Maven. Jolt resolves from [JitPack](https://jitpack.io).

```bash
mvn -B verify          # tests, formatting check, coverage report in target/site/jacoco
mvn -q compile exec:java
curl http://127.0.0.1:8080/api/health
```

The server binds to loopback on `PORT` (default 8080). Set `JOLT_BIND_ADDRESS=0.0.0.0` to expose it. All settings are listed in SPEC section 9.

## Layout

```
src/main/java/dev/thiru/bms/
  domain/            entities, rules, ports (JDK only)
  application/       use cases
  adapters/db/       SQLite repositories on jolt-db
  adapters/payment/  mock gateway and webhook signing
  web/               route modules, HTTP records, error mappers
  Main.java          composition root
```

`ArchitectureTest` enforces the layer rules. Rules for contributors and agents are in [AGENTS.md](AGENTS.md).

## License

MIT
