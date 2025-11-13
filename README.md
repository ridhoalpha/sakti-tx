# sakti-tx (v1.0.0)

This project is a multi-module Maven library implementing the Application-Level Transaction Coordinator (ALTC) pattern with Redisson/Dragonfly locking, idempotency, JMS publisher, tx-log and recovery worker. It includes an example Spring Boot app demonstrating usage.

## Build
From /mnt/data/sakti-tx-1.0.0 run:

```
mvn -DskipTests clean install
```

## Example
Start the example app (it uses in-memory H2 DB for demonstration):

```
cd sakti-tx-example
mvn -DskipTests spring-boot:run
```

The example registers three Datasources (maindb, db1, db2) and demonstrates `@SaktiTransactional` usage in `SampleService`.

## Notes
- Configure `sakti.tx` properties in `application.yml` as needed (Redis/Dragonfly address, lock/ttl values, JMS broker).
- For production, provide a RedissonClient configured to your Dragonfly operator cluster and a pooled JMS ConnectionFactory.
- This code is compatible with Java 8 runtime and later.
