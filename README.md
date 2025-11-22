# SAKTI Transaction Coordinator v1.0.2

Enterprise-grade distributed transaction management for SAKTI microservices.

## 🚀 Quick Start

### 1. Add Dependencies

```xml
<dependencies>
    <!-- SAKTI TX Starter (MANDATORY) -->
    <dependency>
        <groupId>id.go.kemenkeu.djpbn.sakti</groupId>
        <artifactId>sakti-tx-starter</artifactId>
        <version>1.0.2</version>
    </dependency>
    
    <!-- Redisson (REQUIRED if using lock/cache/idempotency) -->
    <dependency>
        <groupId>org.redisson</groupId>
        <artifactId>redisson-spring-boot-starter</artifactId>
        <version>3.41.0</version>
    </dependency>
    
    <!-- ActiveMQ Artemis (OPTIONAL - only if using JMS events) -->
    <dependency>
        <groupId>org.apache.activemq</groupId>
        <artifactId>artemis-jakarta-client</artifactId>
        <version>2.38.0</version>
    </dependency>
</dependencies>
```

### 2. Configure (Minimal - Lock Only)

```properties
sakti.tx.dragonfly.enabled=true
sakti.tx.dragonfly.url=redis://localhost:6379
sakti.tx.lock.enabled=true
```

### 3. Use Annotations

```java
@Service
public class YourService {
    
    @SaktiLock(key = "'order:' + #orderId")
    @Transactional
    public OrderDto processOrder(String orderId) {
        // Your code here - protected by distributed lock
        return result;
    }
}
```

---

## 📋 Features

| Feature | Requires Dragonfly | Default | Description |
|---------|-------------------|---------|-------------|
| **Distributed Lock** | ✅ Yes | Disabled | Prevent concurrent modifications |
| **Cache Manager** | ✅ Yes | Disabled | Distributed caching with TTL |
| **Idempotency** | ✅ Yes | Disabled | Prevent duplicate requests |
| **Multi-DB Transaction** | ❌ No | Disabled | Compensating rollback across DBs |
| **JMS Events** | ❌ No | Disabled | Publish events to ActiveMQ |
| **Circuit Breaker** | ❌ No | Enabled | Graceful degradation when Redis down |
| **Health Indicator** | ❌ No | Enabled | Actuator health endpoint |

---

## 🔧 Configuration Examples

### Example 1: Lock Only (Most Common)
```properties
sakti.tx.dragonfly.enabled=true
sakti.tx.dragonfly.url=redis://dragonfly-ha:6379
sakti.tx.dragonfly.password=${DRAGONFLY_PASSWORD}

sakti.tx.lock.enabled=true
sakti.tx.cache.enabled=false
sakti.tx.idempotency.enabled=false
```

### Example 2: Lock + Cache
```properties
sakti.tx.dragonfly.enabled=true
sakti.tx.dragonfly.url=redis://dragonfly-ha:6379

sakti.tx.lock.enabled=true
sakti.tx.cache.enabled=true
sakti.tx.cache.default-ttl-seconds=600
```

### Example 3: Full Stack
```properties
sakti.tx.dragonfly.enabled=true
sakti.tx.dragonfly.url=redis://dragonfly-ha:6379
sakti.tx.dragonfly.password=${DRAGONFLY_PASSWORD}

sakti.tx.lock.enabled=true
sakti.tx.cache.enabled=true
sakti.tx.idempotency.enabled=true
sakti.tx.jms.enabled=true
sakti.tx.jms.existing-factory-bean-name=artemisConnectionFactory
sakti.tx.multi-db.enabled=true
```

### Example 4: No Dragonfly (Fallback Mode)
```properties
sakti.tx.dragonfly.enabled=false
sakti.tx.multi-db.enabled=true  # Multi-DB works without Redis
sakti.tx.jms.enabled=true        # JMS works without Redis
```

---

## 🛠️ Troubleshooting

### ❌ Error: "Unsatisfied dependency ... IdempotencyManager"

**Cause:** `sakti.tx.idempotency.enabled=true` but `sakti.tx.dragonfly.enabled=false`

**Solution:**
```properties
# Option 1: Enable Dragonfly
sakti.tx.dragonfly.enabled=true
sakti.tx.dragonfly.url=redis://localhost:6379

# Option 2: Disable Idempotency
sakti.tx.idempotency.enabled=false
```

---

### ❌ Error: "Unsatisfied dependency ... LockManager"

**Cause:** `sakti.tx.lock.enabled=true` but `sakti.tx.dragonfly.enabled=false`

**Solution:**
```properties
# Option 1: Enable Dragonfly
sakti.tx.dragonfly.enabled=true

# Option 2: Disable Lock
sakti.tx.lock.enabled=false
```

---

### ❌ Error: "Cannot initialize Dragonfly connection"

**Cause:** Dragonfly/Redis is not reachable or credentials are wrong

**Check:**
```bash
# Test connectivity from your pod
kubectl exec -it your-pod -- redis-cli -h dragonfly-ha -p 6379 ping
# Expected: PONG

# With password
kubectl exec -it your-pod -- redis-cli -h dragonfly-ha -p 6379 -a yourpassword ping
```

**Solution:**
```properties
# Verify URL format
sakti.tx.dragonfly.url=redis://dragonfly-ha.sakti.svc.cluster.local:6379

# Check password
sakti.tx.dragonfly.password=${DRAGONFLY_PASSWORD}

# Increase timeout if network is slow
sakti.tx.dragonfly.connect-timeout=10000
```

---

### ❌ Error: "Circuit breaker OPEN"

**Cause:** Dragonfly failed health check 5 times (default threshold)

**Check Application Logs:**
```
🔴 Circuit breaker OPEN - Dragonfly unavailable after 5 failures
   → All lock/cache/idempotency operations will be bypassed
   → Will retry after 30000ms
```

**Solution:**
```properties
# Option 1: Fix Dragonfly connectivity (RECOMMENDED)
# Check Dragonfly pod status, network policies, etc.

# Option 2: Disable circuit breaker temporarily (NOT RECOMMENDED)
sakti.tx.circuit-breaker.enabled=false

# Option 3: Increase threshold for flaky networks
sakti.tx.circuit-breaker.failure-threshold=10
sakti.tx.circuit-breaker.recovery-timeout-ms=60000
```

---

### ❌ Error: "JMS enabled but no ConnectionFactory available"

**Cause:** `sakti.tx.jms.enabled=true` but no JMS ConnectionFactory found

**Solution:**
```properties
# Option 1: Reuse existing ConnectionFactory (RECOMMENDED)
sakti.tx.jms.existing-factory-bean-name=artemisConnectionFactory

# Option 2: Provide broker URL
sakti.tx.jms.broker-url=tcp://artemis-service:61616
sakti.tx.jms.user=admin
sakti.tx.jms.password=admin

# Option 3: Disable JMS
sakti.tx.jms.enabled=false
```

**Also add dependency:**
```xml
<dependency>
    <groupId>org.apache.activemq</groupId>
    <artifactId>artemis-jakarta-client</artifactId>
    <version>2.38.0</version>
</dependency>
```

---

### ⚠️ Warning: "Lock disabled or circuit open - executing without lock"

**Cause:** Either:
- `sakti.tx.lock.enabled=false`
- Circuit breaker is OPEN (Dragonfly down)

**Behavior:** Method executes WITHOUT distributed lock (⚠️ risk of race condition)

**Check:**
```bash
# Check health endpoint
curl http://localhost:8080/actuator/health | jq .components.dragonfly

# Expected when healthy:
{
  "status": "UP",
  "details": {
    "dragonfly": "PONG",
    "circuitState": "CLOSED"
  }
}

# When circuit is OPEN:
{
  "status": "DOWN",
  "details": {
    "circuitState": "OPEN",
    "consecutiveFailures": 5
  }
}
```

**Solution:**
- Fix Dragonfly connectivity
- Wait for circuit breaker to recover (default 30 seconds)
- Check `consecutiveFailures` count

---

### 🔍 Startup Configuration Validation

When your app starts, check logs for configuration summary:

```
═══════════════════════════════════════════════════════════
🚀 SAKTI Transaction Coordinator v1.0.2 - Initializing...
═══════════════════════════════════════════════════════════
✅ Dragonfly/Redis - ENABLED (redis://dragonfly-ha:6379)
✅ Distributed Lock - ENABLED (ready)
✅ Cache Manager - ENABLED (ready)
✅ Idempotency - ENABLED (ready)
✅ Circuit Breaker - ENABLED (threshold=5)
⚪ JMS Events - DISABLED
✅ Multi-DB Transaction - ENABLED (COMPENSATING)
✅ Health Indicator - ENABLED (actuator)
═══════════════════════════════════════════════════════════
```

**If you see errors:**
```
❌ CONFIGURATION ERROR: sakti.tx.lock.enabled=true requires sakti.tx.dragonfly.enabled=true
   → Solution: Set sakti.tx.dragonfly.enabled=true OR sakti.tx.lock.enabled=false
```

---

## 📊 Feature Dependency Matrix

```
Feature                | Requires Dragonfly | Requires JMS | Requires Multi-DB
-----------------------|--------------------|--------------|------------------
Distributed Lock       | ✅ YES             | ❌ NO        | ❌ NO
Cache Manager          | ✅ YES             | ❌ NO        | ❌ NO
Idempotency            | ✅ YES             | ❌ NO        | ❌ NO
Multi-DB Transaction   | ❌ NO              | ❌ NO        | ❌ NO
JMS Events             | ❌ NO              | ✅ YES       | ❌ NO
Circuit Breaker        | ✅ YES*            | ❌ NO        | ❌ NO

* Circuit breaker monitors Dragonfly health
```

---

## 🎯 Valid Configuration Combinations

| Scenario | Dragonfly | Lock | Cache | Idempotency | JMS | Multi-DB | Valid? |
|----------|-----------|------|-------|-------------|-----|----------|--------|
| Disabled All | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ Yes |
| Lock Only | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ Yes |
| Lock + Cache | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ✅ Yes |
| Full Stack | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ Yes |
| Multi-DB Only | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ Yes |
| JMS Only | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ Yes |
| **INVALID** | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ **NO** |
| **INVALID** | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ **NO** |
| **INVALID** | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ **NO** |

---

## 📞 Support

If you still encounter issues:

1. **Check application logs** at startup for configuration validation
2. **Check health endpoint**: `GET /actuator/health`
3. **Verify dependencies** in your `pom.xml`
4. **Test Dragonfly connectivity** from your pod
5. **Check circuit breaker state** in logs/health endpoint

---

## 📚 See Also

- `/docs/USAGE_EXAMPLES.md` - Complete code examples
- `/docs/PERFORMANCE_TUNING.md` - Production optimization
- `application.properties-example` - All valid configuration scenarios