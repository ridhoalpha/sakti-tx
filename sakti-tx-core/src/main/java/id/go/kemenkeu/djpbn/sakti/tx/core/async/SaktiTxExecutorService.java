package id.go.kemenkeu.djpbn.sakti.tx.core.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * ExecutorService that automatically propagates SAKTI TX context
 * Wraps all submitted tasks with SaktiTxCallable/SaktiTxRunnable
 */
public class SaktiTxExecutorService implements ExecutorService {
    
    private static final Logger log = LoggerFactory.getLogger(SaktiTxExecutorService.class);
    
    private final ExecutorService delegate;
    
    public SaktiTxExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
        log.info("Created SaktiTxExecutorService wrapping: {}", 
            delegate.getClass().getSimpleName());
    }
    
    @Override
    public void execute(Runnable command) {
        delegate.execute(new SaktiTxRunnable(command));
    }
    
    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(new SaktiTxCallable<>(task));
    }
    
    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(new SaktiTxRunnable(task), result);
    }
    
    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(new SaktiTxRunnable(task));
    }
    
    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) 
            throws InterruptedException {
        List<Callable<T>> wrapped = tasks.stream()
            .map(SaktiTxCallable::new)
            .collect(Collectors.toList());
        return delegate.invokeAll(wrapped);
    }
    
    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, 
                                         long timeout, TimeUnit unit) 
            throws InterruptedException {
        List<Callable<T>> wrapped = tasks.stream()
            .map(SaktiTxCallable::new)
            .collect(Collectors.toList());
        return delegate.invokeAll(wrapped, timeout, unit);
    }
    
    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) 
            throws InterruptedException, ExecutionException {
        List<Callable<T>> wrapped = tasks.stream()
            .map(SaktiTxCallable::new)
            .collect(Collectors.toList());
        return delegate.invokeAny(wrapped);
    }
    
    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, 
                          long timeout, TimeUnit unit) 
            throws InterruptedException, ExecutionException, TimeoutException {
        List<Callable<T>> wrapped = tasks.stream()
            .map(SaktiTxCallable::new)
            .collect(Collectors.toList());
        return delegate.invokeAny(wrapped, timeout, unit);
    }
    
    @Override
    public void shutdown() {
        delegate.shutdown();
    }
    
    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }
    
    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }
    
    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }
    
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) 
            throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}