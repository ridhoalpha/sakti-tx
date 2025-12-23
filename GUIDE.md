# 🚀 SAKTI TX - Complete Usage Guide

## 📋 Table of Contents
1. [Setup & Configuration](#setup--configuration)
2. [Basic Usage](#basic-usage)
3. [Advanced Features](#advanced-features)
4. [Troubleshooting](#troubleshooting)

---

## 1️⃣ SETUP & CONFIGURATION

### Step 1: Install Dependency

Tambahkan dependency di `pom.xml`:

```xml
<dependency>
    <groupId>id.go.kemenkeu.djpbn.sakti</groupId>
    <artifactId>sakti-tx-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Step 2: Update Datasource Configuration

**Replace** 3 file konfigurasi datasource Anda:

#### 📄 `DatasourceConfigBen.java` (DB1 - Primary)
```java
package id.go.kemenkeu.djpbn.sakti.microservicessaktiben.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateSettings;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;

@AllArgsConstructor
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
    basePackages = "id.go.kemenkeu.djpbn.sakti.microservicessaktiben.repository",
    entityManagerFactoryRef = "db1EntityManagerFactory",
    transactionManagerRef = "db1TransactionManager"
)
public class DatasourceConfigBen {
    
    private final JpaProperties springJpaProperties;
    private final HibernateProperties springHibernateProperties;
    private final HibernateJpaProperties customHibernateProps;

    @Primary
    @Bean(name = "db1DataSource")
    @ConfigurationProperties(prefix = "spring.datasource.ben")
    public DataSource db1DataSource() {
        return DataSourceBuilder.create().build();
    }

    @Primary
    @Bean(name = "db1EntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean db1EntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("db1DataSource") DataSource dataSource) {
        
        return builder
            .dataSource(dataSource)
            .packages("id.go.kemenkeu.djpbn.sakti.microservicessaktiben.model")
            .persistenceUnit("db1")
            .properties(jpaProperties())
            .build();
    }

    // ✅ TAMBAHAN INI (CRITICAL!)
    @Primary
    @Bean(name = "db1EntityManager")
    public EntityManager db1EntityManager(
            @Qualifier("db1EntityManagerFactory") EntityManagerFactory emf) {
        return emf.createEntityManager();
    }

    @Primary
    @Bean(name = "db1TransactionManager")
    public PlatformTransactionManager db1TransactionManager(
            @Qualifier("db1EntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    public Map<String, Object> jpaProperties() {
        Map<String, Object> props = new HashMap<>();
        props.putAll(
            springHibernateProperties.determineHibernateProperties(
                springJpaProperties.getProperties(),
                new HibernateSettings()
            )
        );
        props.put("hibernate.hbm2ddl.auto", customHibernateProps.getHbm2ddlAuto());
        props.put("hibernate.show_sql", customHibernateProps.isShowSql());
        props.put("hibernate.jdbc.batch_size", customHibernateProps.getBatchSize());
        props.put("hibernate.order_inserts", customHibernateProps.isOrderInserts());
        props.put("hibernate.order_updates", customHibernateProps.isOrderUpdates());
        props.put("hibernate.jdbc.batch_versioned_data", customHibernateProps.isBatchVersionedData());
        props.put("hibernate.query.plan_cache_max_size", customHibernateProps.getQueryPlanCacheSize());
        props.put("hibernate.generate_statistics", customHibernateProps.isGenerateStatistics());
        props.put("hibernate.connection.isolation", customHibernateProps.getIsolation());
        props.put("hibernate.temp.use_jdbc_metadata_defaults", customHibernateProps.isUseJdbcMetadata());
        return props;
    }
}
```

**Lakukan hal yang sama untuk `DatasourceConfigGlp.java` (db2) dan `DatasourceConfigKom.java` (db3)**  
→ Hanya ganti `db1` → `db2` atau `db3`

### Step 3: Configure `application.properties`

Tambahkan konfigurasi SAKTI TX:

```properties
# ═══════════════════════════════════════════════════════════════════════════
# SAKTI TX CONFIGURATION
# ═══════════════════════════════════════════════════════════════════════════

# 1. Enable Dragonfly/Redis (WAJIB untuk distributed transaction)
sakti.tx.dragonfly.enabled=true
sakti.tx.dragonfly.url=redis://localhost:6379
sakti.tx.dragonfly.password=
sakti.tx.dragonfly.wait-for-sync=true
sakti.tx.dragonfly.wait-for-sync-timeout-ms=5000

# 2. Enable Distributed Transaction (WAJIB)
sakti.tx.multi-db.enabled=true
sakti.tx.multi-db.rollback-strategy=COMPENSATING
sakti.tx.multi-db.max-rollback-retries=3

# 3. Enable Recovery Worker (RECOMMENDED)
sakti.tx.multi-db.recovery.enabled=true
sakti.tx.multi-db.recovery.scan-interval-ms=60000
sakti.tx.multi-db.recovery.stall-timeout-ms=120000

# 4. Enable Distributed Lock (RECOMMENDED)
sakti.tx.lock.enabled=true
sakti.tx.lock.wait-time-ms=5000
sakti.tx.lock.lease-time-ms=30000

# 5. Enable Idempotency (OPTIONAL - good for financial transactions)
sakti.tx.idempotency.enabled=true
sakti.tx.idempotency.ttl-seconds=7200

# 6. Enable Cache (OPTIONAL - for performance)
sakti.tx.cache.enabled=true
sakti.tx.cache.default-ttl-seconds=600

# 7. Enable Circuit Breaker (RECOMMENDED)
sakti.tx.circuit-breaker.enabled=true
sakti.tx.circuit-breaker.failure-threshold=5

# 8. Enable Health Check (RECOMMENDED for production)
sakti.tx.health.enabled=true

# 9. Enable Admin API (OPTIONAL - disable in production without auth)
sakti.tx.multi-db.admin-api.enabled=false
```

---

## 2️⃣ BASIC USAGE

### ✅ Scenario 1: Simple Multi-DB Transaction

**Use Case:** Save Perintah Bayar yang menyimpan data ke 3 database (BEN, GLP, KOM)

#### Code Example:

```java
package id.go.kemenkeu.djpbn.sakti.microservicessaktiben.service.impl;

import id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SaktiDistributedTx;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PerintahBayarServiceImpl {
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ STEP 1: Add @SaktiDistributedTx annotation
    // ═══════════════════════════════════════════════════════════════════════════
    @Override
    @Transactional
    @SaktiDistributedTx  // ← ADD THIS!
    public PerintahBayarDto savePerintahBayarWeb(
        PerintahBayarDto wrapper, 
        List<AkunBelanjaDto> listAkunBelanja,
        List<AkunPotonganDto> listAkunPotongan, 
        List<BastNonkHeaderDto> listBast
    ) throws BenException {
        
        // ═══════════════════════════════════════════════════════════════════════════
        // ✅ STEP 2: Write your business logic normally (NO CHANGES NEEDED!)
        // ═══════════════════════════════════════════════════════════════════════════
        
        // Validasi business logic
        boolean isValid = validasiDropdown(wrapper);
        if (!isValid) {
            throw new BenException("Validasi gagal");
        }
        
        // Save ke DB BEN (db1)
        PerintahBayar pb = perintahBayarConverter.toSaveHeader(wrapper, listDetail);
        pb = perintahBayarRepository.save(pb);  // Auto-tracked by SAKTI TX
        
        // Save ke DB GLP (db2) - contoh
        BukuBesar bukuBesar = new BukuBesar();
        bukuBesar.setIdPb(pb.getId());
        bukuBesar.setJumlah(wrapper.getJumlah());
        bukuBesarRepository.save(bukuBesar);  // Auto-tracked by SAKTI TX
        
        // Save ke DB KOM (db3) - contoh
        Komitmen komitmen = new Komitmen();
        komitmen.setIdPb(pb.getId());
        komitmen.setNilaiKomitmen(wrapper.getJumlah());
        komitmenRepository.save(komitmen);  // Auto-tracked by SAKTI TX
        
        return perintahBayarConverter.toWrapperHeader(pb);
        
        // ═══════════════════════════════════════════════════════════════════════════
        // ✅ MAGIC HAPPENS HERE:
        // - If success: ALL databases committed
        // - If error: ALL databases rolled back automatically
        // - Transaction log saved to Redis for recovery
        // ═══════════════════════════════════════════════════════════════════════════
    }
}
```

### 🎯 What Happens Behind the Scenes?

```
1. Transaction starts
   ↓
2. SaktiDistributedTxAspect creates transaction context
   ↓
3. EntityOperationListener tracks ALL database operations:
   - perintahBayarRepository.save() → tracked
   - bukuBesarRepository.save() → tracked
   - komitmenRepository.save() → tracked
   ↓
4. Pre-commit validation:
   - Check all DB connections ✓
   - Validate transaction state ✓
   - Assess risks ✓
   ↓
5. Commit phase:
   - Commit DB1 (BEN) ✓
   - Commit DB2 (GLP) ✓
   - Commit DB3 (KOM) ✓
   ↓
6. Mark transaction as COMMITTED in Redis
   ↓
7. Success! 🎉
```

### ❌ What If Something Goes Wrong?

```
1. Error occurs (e.g., constraint violation in DB3)
   ↓
2. SaktiDistributedTxAspect catches error
   ↓
3. Rollback ALL Spring transactions
   ↓
4. CompensatingTransactionExecutor rollback in REVERSE order:
   - Delete from DB3 (KOM) ✓
   - Delete from DB2 (GLP) ✓
   - Delete from DB1 (BEN) ✓
   ↓
5. Mark transaction as ROLLED_BACK in Redis
   ↓
6. Throw exception to caller
```

---

## 3️⃣ ADVANCED FEATURES

### 🔒 Feature 1: Distributed Lock

Prevent concurrent access to the same resource across multiple pods.

```java
@Service
public class PerintahBayarServiceImpl {
    
    @SaktiDistributedTx(lockKey = "'perintah-bayar:' + #wrapper.id")
    @Transactional
    public PerintahBayarDto updatePerintahBayar(PerintahBayarDto wrapper) {
        // Only ONE pod can execute this at a time for the same wrapper.id
        // Other pods will wait or fail with LockAcquisitionException
        
        PerintahBayar pb = perintahBayarRepository.findById(wrapper.getId())
            .orElseThrow(() -> new BenException("Not found"));
        
        pb.setStatusValidasi(wrapper.getStatusValidasi());
        return perintahBayarConverter.toWrapperHeader(
            perintahBayarRepository.save(pb)
        );
    }
}
```

### 🔁 Feature 2: Idempotency

Prevent duplicate submissions (e.g., user double-clicks submit button).

```java
@Service
public class PerintahBayarServiceImpl {
    
    @SaktiDistributedTx
    @SaktiIdempotent(key = "'create-pb:' + #wrapper.noPerintahBayar")
    @Transactional
    public PerintahBayarDto createPerintahBayar(PerintahBayarDto wrapper) {
        // If user submits twice with same noPerintahBayar:
        // - First request: processed normally
        // - Second request: throws IdempotencyException
        
        PerintahBayar pb = perintahBayarConverter.toEntity(wrapper);
        pb = perintahBayarRepository.save(pb);
        
        return perintahBayarConverter.toWrapperHeader(pb);
    }
}
```

### 💾 Feature 3: Cache

Cache expensive queries to Redis.

```java
@Service
public class ReferensiService {
    
    @SaktiCache(
        key = "'ref:akun:' + #kodeAkun", 
        ttlSeconds = 3600
    )
    public AkunDto getAkunByKode(String kodeAkun) {
        // First call: query DB, save to Redis (1 hour TTL)
        // Next calls: return from Redis (no DB query)
        
        return akunRepository.findByKode(kodeAkun)
            .map(akunConverter::toDto)
            .orElse(null);
    }
}
```

### 🔧 Feature 4: Manual Transaction Control

For advanced scenarios where you need manual control.

```java
@Service
public class ComplexService {
    
    @Autowired
    private DistributedLockService lockService;
    
    public void processComplexTransaction() throws Exception {
        // Manual lock + idempotency
        lockService.executeWithLockAndIdempotency(
            "complex-tx:123",  // lock key
            "idemp:123",        // idempotency key
            () -> {
                // Your business logic here
                // This runs with distributed lock + idempotency protection
                
                perintahBayarRepository.save(pb);
                bukuBesarRepository.save(bb);
                
                return "Success";
            }
        );
    }
}
```

---

## 4️⃣ TROUBLESHOOTING

### ❌ Problem 1: "EntityManager not found"

**Solution:** Pastikan Anda sudah menambahkan bean `EntityManager` di datasource config:

```java
@Bean(name = "db1EntityManager")
public EntityManager db1EntityManager(
        @Qualifier("db1EntityManagerFactory") EntityManagerFactory emf) {
    return emf.createEntityManager();
}
```

### ❌ Problem 2: "Redis connection failed"

**Check:**
1. Redis/Dragonfly running? `redis-cli ping` → `PONG`
2. Connection URL correct? Check `sakti.tx.dragonfly.url`
3. Password correct? Check `sakti.tx.dragonfly.password`

**Fallback:** If Redis unavailable, SAKTI TX will:
- Disable lock/cache/idempotency features
- Continue with basic transaction only
- Log warnings

### ❌ Problem 3: "Transaction stuck in ROLLING_BACK"

**Solution:** Recovery worker will auto-retry. Check logs:

```bash
# Check recovery worker logs
grep "Recovery scan" application.log

# Manual retry via Admin API (if enabled)
curl -X POST http://localhost:8080/admin/transactions/retry/{txId}
```

### ❌ Problem 4: "Circuit breaker OPEN"

**Meaning:** Too many compensation failures (default: 5)

**Solution:**
1. Check DB connectivity
2. Check for schema changes
3. Reset circuit breaker:
```bash
curl -X POST http://localhost:8080/admin/transactions/force-scan
```

---

## 📊 MONITORING

### Health Check

```bash
# Check SAKTI TX health
curl http://localhost:9090/sakti-management/sakti-healthcheck
```

Response:
```json
{
  "status": "UP",
  "components": {
    "dragonfly": {
      "status": "UP",
      "details": {
        "dragonfly": "PONG",
        "circuitState": "CLOSED"
      }
    }
  }
}
```

### Metrics (Prometheus)

```bash
# View all SAKTI TX metrics
curl http://localhost:9090/actuator/prometheus | grep sakti_tx
```

Example metrics:
```
sakti_tx_total{status="committed"} 1523
sakti_tx_total{status="rolled_back"} 12
sakti_tx_success_rate 99.21
sakti_tx_duration_avg_ms 145.3
```

### Admin API (if enabled)

```bash
# List failed transactions
curl http://localhost:8080/admin/transactions/failed

# Get transaction details
curl http://localhost:8080/admin/transactions/{txId}

# Retry failed transaction
curl -X POST http://localhost:8080/admin/transactions/retry/{txId}

# Get recovery metrics
curl http://localhost:8080/admin/transactions/metrics
```

---

## 🎓 BEST PRACTICES

### ✅ DO:
1. **Always use `@SaktiDistributedTx`** on service methods that modify multiple databases
2. **Use `@SaktiLock`** for operations that modify shared resources
3. **Use `@SaktiIdempotent`** for user-triggered operations (submit buttons)
4. **Enable recovery worker** in production
5. **Monitor health checks** and metrics
6. **Test rollback scenarios** in staging

### ❌ DON'T:
1. **Don't use `@SaktiDistributedTx`** on read-only operations (overhead!)
2. **Don't disable circuit breaker** in production
3. **Don't enable admin API** without authentication in production
4. **Don't use very long TTLs** for idempotency (max 2 hours recommended)
5. **Don't ignore health check warnings**

---

## 🆘 NEED HELP?

### Log Analysis

Enable debug logging:
```properties
logging.level.id.go.kemenkeu.djpbn.sakti.tx=DEBUG
```

Look for these key log patterns:

```
✅ SUCCESS:
"Transaction COMMITTED"
"Operations: X"
"Duration: Xms"
"Risk Level: LOW"

❌ ROLLBACK:
"Transaction FAILED"
"Starting SMART COMPENSATING ROLLBACK"
"✓ Rollback completed successfully"

⚠ WARNING:
"Risk detected [NATIVE_SQL]"
"Long running transaction (>30s)"
"Circuit breaker OPEN"
```

---

## 🎉 Summary

**Yang Harus Anda Lakukan:**

1. ✅ Update 3 datasource config (tambah bean `EntityManager`)
2. ✅ Tambahkan konfigurasi SAKTI TX di `application.properties`
3. ✅ Tambahkan `@SaktiDistributedTx` pada method service yang multi-DB
4. ✅ Install & run Redis/Dragonfly
5. ✅ Test & monitor

**Keuntungan:**
- ✅ Automatic rollback on error
- ✅ Transaction recovery on crash
- ✅ Distributed lock & idempotency
- ✅ Built-in monitoring
- ✅ Zero boilerplate code

Good luck! 🚀