package id.go.kemenkeu.djpbn.sakti.tx.core.wrapper;

@FunctionalInterface
public interface CheckedSupplier<T> {
    T get() throws Exception;
}
