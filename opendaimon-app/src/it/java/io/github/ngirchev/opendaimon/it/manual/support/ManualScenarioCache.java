package io.github.ngirchev.opendaimon.it.manual.support;

/**
 * Lazily runs an expensive manual scenario once per test instance and reuses
 * the captured result for assertions in multiple test methods.
 */
public final class ManualScenarioCache<T> {

    private final ThrowingSupplier<T> supplier;
    private T value;

    private ManualScenarioCache(ThrowingSupplier<T> supplier) {
        this.supplier = supplier;
    }

    public static <T> ManualScenarioCache<T> of(ThrowingSupplier<T> supplier) {
        return new ManualScenarioCache<>(supplier);
    }

    public T get() throws Exception {
        if (value == null) {
            value = supplier.get();
        }
        return value;
    }

    public void clear() {
        value = null;
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {

        T get() throws Exception;
    }
}
