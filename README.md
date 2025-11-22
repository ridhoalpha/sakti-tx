# SAKTI Transaction Coordinator v1.0.2

Distributed transaction management for SAKTI microservices.

## Quick Start

### 1. Add Dependency
```xml
<dependency>
    <groupId>id.go.kemenkeu.djpbn.sakti</groupId>
    <artifactId>sakti-tx-starter</artifactId>
    <version>1.0.2</version>
</dependency>

<!-- If using Dragonfly features -->
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.41.0</version>
</dependency>
```

### 2. Configure
```properties
sakti.tx.dragonfly.enabled=true
sakti.tx.dragonfly.url=redis://dragonfly:6379
sakti.tx.dragonfly.password=${DRAGONFLY_PASSWORD}
sakti.tx.lock.enabled=true
```

### 3. Use
```java
@Service
public class YourService {
    
    @SaktiLock(key = "'order:' + #orderId")
    @Transactional
    public OrderDto process(String orderId) {
        // Your code here
    }
}
```

## Features

- ✅ Distributed Lock (Dragonfly/Redisson)
- ✅ Cache Management
- ✅ Idempotency Protection
- ✅ Multi-DB Transaction (Compensating Rollback)
- ✅ Circuit Breaker
- ✅ Optional JMS Event Publishing
- ✅ All features independently configurable

## Documentation

See `/docs` for complete guide.