package id.go.kemenkeu.djpbn.sakti.tx.starter.annotation;

import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog.OperationType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation untuk tracking database operations di service layer.
 * 
 * Mendukung SEMUA jenis operasi:
 * 1. Entity operations (INSERT, UPDATE, DELETE)
 * 2. Bulk operations (BULK_UPDATE, BULK_DELETE)
 * 3. Native queries (@Query nativeQuery=true)
 * 4. Stored procedures (@Procedure)
 * 
 * USAGE EXAMPLES:
 * 
 * <pre>
 * {@code
 * // ═══════════════════════════════════════════════════════════
 * // 1. SIMPLE ENTITY OPERATIONS
 * // ═══════════════════════════════════════════════════════════
 * @TrackOperation(type = OperationType.INSERT, datasource = "saktidb")
 * public Account createAccount(Account account) {
 *     return accountRepository.save(account);
 * }
 * 
 * @TrackOperation(type = OperationType.UPDATE, datasource = "saktidb")
 * public Account updateBalance(Account account) {
 *     account.setBalance(account.getBalance() + 100);
 *     return accountRepository.save(account);
 * }
 * 
 * @TrackOperation(type = OperationType.DELETE, datasource = "saktidb")
 * public void deleteAccount(Account account) {
 *     accountRepository.delete(account);
 * }
 * 
 * // ═══════════════════════════════════════════════════════════
 * // 2. BULK OPERATIONS (requires snapshots)
 * // ═══════════════════════════════════════════════════════════
 * @TrackOperation(
 *     type = OperationType.BULK_UPDATE,
 *     datasource = "saktidb",
 *     entityClass = Account.class,
 *     description = "Bulk deactivate accounts by region"
 * )
 * public int deactivateAccountsByRegion(String region) {
 *     // MUST take snapshot BEFORE bulk operation
 *     List<Account> affected = accountRepository.findByRegion(region);
 *     DistributedTransactionContext.get().recordBulkOperation(
 *         "saktidb", 
 *         OperationType.BULK_UPDATE,
 *         Account.class.getName(),
 *         affected,
 *         "UPDATE account SET active=0 WHERE region='" + region + "'"
 *     );
 *     
 *     return accountRepository.deactivateByRegion(region);
 * }
 * 
 * @TrackOperation(
 *     type = OperationType.BULK_DELETE,
 *     datasource = "saktidb",
 *     entityClass = Account.class
 * )
 * public int deleteZeroBalanceAccounts() {
 *     // Snapshot before delete
 *     List<Account> toDelete = accountRepository.findByBalance(BigDecimal.ZERO);
 *     DistributedTransactionContext.get().recordBulkOperation(
 *         "saktidb",
 *         OperationType.BULK_DELETE,
 *         Account.class.getName(),
 *         toDelete,
 *         "DELETE FROM account WHERE balance = 0"
 *     );
 *     
 *     return accountRepository.deleteByBalance(BigDecimal.ZERO);
 * }
 * 
 * // ═══════════════════════════════════════════════════════════
 * // 3. NATIVE QUERY (with inverse query)
 * // ═══════════════════════════════════════════════════════════
 * @TrackOperation(
 *     type = OperationType.NATIVE_QUERY,
 *     datasource = "saktidb",
 *     entityClass = Account.class,
 *     inverseQuery = "UPDATE account SET balance = balance - :amount WHERE id = :accountId"
 * )
 * public void addBonus(Long accountId, BigDecimal amount) {
 *     // Take snapshot before
 *     Account snapshot = accountRepository.findById(accountId).orElse(null);
 *     
 *     // Execute native query
 *     accountRepository.addBalance(accountId, amount);
 *     
 *     // Record to transaction log
 *     DistributedTransactionContext.get().recordNativeQuery(
 *         "saktidb",
 *         Account.class.getName(),
 *         accountId,
 *         snapshot,
 *         "UPDATE account SET balance = balance + " + amount + " WHERE id = " + accountId,
 *         "UPDATE account SET balance = balance - " + amount + " WHERE id = " + accountId,
 *         Map.of("accountId", accountId, "amount", amount)
 *     );
 * }
 * 
 * // Soft delete example
 * @TrackOperation(
 *     type = OperationType.NATIVE_QUERY,
 *     datasource = "saktidb",
 *     entityClass = Account.class,
 *     inverseQuery = "UPDATE account SET deleted = 0 WHERE id = :accountId"
 * )
 * public void softDeleteAccount(Long accountId) {
 *     Account snapshot = accountRepository.findById(accountId).orElse(null);
 *     
 *     accountRepository.softDelete(accountId);
 *     
 *     DistributedTransactionContext.get().recordNativeQuery(
 *         "saktidb",
 *         Account.class.getName(),
 *         accountId,
 *         snapshot,
 *         "UPDATE account SET deleted = 1 WHERE id = " + accountId,
 *         "UPDATE account SET deleted = 0 WHERE id = " + accountId,
 *         Map.of("accountId", accountId)
 *     );
 * }
 * 
 * // ═══════════════════════════════════════════════════════════
 * // 4. STORED PROCEDURE (with inverse procedure)
 * // ═══════════════════════════════════════════════════════════
 * @TrackOperation(
 *     type = OperationType.STORED_PROCEDURE,
 *     datasource = "saktidb",
 *     entityClass = Account.class,
 *     inverseProcedure = "sp_revert_monthly_interest"
 * )
 * public void applyMonthlyInterest(String month) {
 *     // Snapshot affected accounts
 *     List<Account> affected = accountRepository.findAll();
 *     
 *     // Execute procedure
 *     accountRepository.callApplyMonthlyInterest(month);
 *     
 *     // Record to transaction log
 *     DistributedTransactionContext.get().recordStoredProcedure(
 *         "saktidb",
 *         "sp_apply_monthly_interest",
 *         "sp_revert_monthly_interest",
 *         Map.of("month", month),
 *         affected
 *     );
 * }
 * }
 * </pre>
 * 
 * IMPORTANT NOTES:
 * - Hanya bekerja dalam konteks @SaktiDistributedTx
 * - Method harus menerima atau return entity yang akan di-track
 * - Entity harus memiliki @Id annotation
 * - Snapshot akan diambil SEBELUM operasi untuk UPDATE/DELETE
 * - Bulk operations WAJIB manual snapshot sebelum operasi
 * - Native query HARUS provide inverse query untuk rollback
 * - Stored procedure HARUS provide inverse procedure atau snapshot
 * 
 * @see ServiceOperationInterceptor
 * @see OperationType
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TrackOperation {
    
    /**
     * Tipe operasi yang akan di-track.
     * 
     * - INSERT: untuk save() entity baru
     * - UPDATE: untuk save() entity existing
     * - DELETE: untuk delete() entity
     * - BULK_UPDATE: untuk UPDATE ... WHERE ... (JPQL/Native)
     * - BULK_DELETE: untuk DELETE ... WHERE ... (JPQL/Native)
     * - NATIVE_QUERY: untuk @Query(nativeQuery=true) @Modifying
     * - STORED_PROCEDURE: untuk @Procedure atau CALL ...
     * 
     * @return tipe operasi
     */
    OperationType type();
    
    /**
     * Nama datasource/EntityManager yang digunakan.
     * 
     * Harus match dengan bean name EntityManager di Spring context.
     * Default: "entityManagerFactory"
     * 
     * Examples:
     * - "saktidb" → EntityManager untuk SAKTI database
     * - "spandb" → EntityManager untuk SPAN database
     * - "entityManagerFactory" → Default EntityManager
     * 
     * @return nama datasource
     */
    String datasource() default "entityManagerFactory";
    
    /**
     * Entity class yang terlibat dalam operasi.
     * 
     * WAJIB untuk:
     * - BULK_UPDATE
     * - BULK_DELETE
     * - NATIVE_QUERY (jika tidak bisa auto-detect dari parameter)
     * - STORED_PROCEDURE
     * 
     * Optional untuk: INSERT, UPDATE, DELETE (bisa auto-detect dari parameter)
     * 
     * @return entity class
     */
    Class<?> entityClass() default Object.class;
    
    /**
     * Inverse query untuk compensation.
     * 
     * WAJIB untuk: NATIVE_QUERY
     * 
     * Query ini akan dieksekusi saat rollback untuk membalikkan efek dari query asli.
     * 
     * Examples:
     * - Original: "UPDATE account SET balance = balance + 100 WHERE id = ?"
     * - Inverse:  "UPDATE account SET balance = balance - 100 WHERE id = ?"
     * 
     * - Original: "UPDATE account SET deleted = 1 WHERE id = ?"
     * - Inverse:  "UPDATE account SET deleted = 0 WHERE id = ?"
     * 
     * Tips: Gunakan named parameters (:paramName) untuk binding
     * 
     * @return inverse query
     */
    String inverseQuery() default "";
    
    /**
     * Inverse stored procedure untuk compensation.
     * 
     * WAJIB untuk: STORED_PROCEDURE (jika tidak provide snapshot)
     * 
     * Procedure ini akan dipanggil saat rollback untuk membalikkan efek dari procedure asli.
     * 
     * Examples:
     * - Original procedure: sp_apply_monthly_interest
     * - Inverse procedure:  sp_revert_monthly_interest
     * 
     * - Original procedure: sp_close_fiscal_year
     * - Inverse procedure:  sp_reopen_fiscal_year
     * 
     * @return inverse procedure name
     */
    String inverseProcedure() default "";
    
    /**
     * Deskripsi operasi (optional).
     * Untuk logging dan debugging purposes.
     * 
     * @return deskripsi operasi
     */
    String description() default "";
    
    /**
     * Apakah operasi ini memerlukan manual snapshot?
     * 
     * Set true untuk:
     * - BULK_UPDATE
     * - BULK_DELETE
     * - Complex NATIVE_QUERY
     * - STORED_PROCEDURE (jika tidak ada inverse procedure)
     * 
     * Jika true, developer WAJIB manual call:
     * DistributedTransactionContext.get().recordBulkOperation(...)
     * ATAU
     * DistributedTransactionContext.get().recordNativeQuery(...)
     * 
     * @return true jika butuh manual snapshot
     */
    boolean requiresManualSnapshot() default false;
}