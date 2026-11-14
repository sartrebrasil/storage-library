package com.example.storage.spring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.TimeUnit;

/**
 * {@code storage.bytes}: um {@link DistributionSummary} (bytes por operação: {@code count}, {@code sum},
 * {@code max}) com as tags {@code operation}, {@code storage} e {@code direction} ({@code upload} ou
 * {@code download}). O registry é resolvido a cada chamada, pelo mesmo motivo do
 * {@code ObservationRegistry} na auto-configuração; sem registry, não registra.
 */
class MeterExtraMeters implements ExtraMeters {

    private final ObjectProvider<MeterRegistry> registry;

    MeterExtraMeters(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public void bytes(String operation, StorageId storage, String direction, long bytes) {
        MeterRegistry meters = registry.getIfAvailable();
        if (meters != null) {
            DistributionSummary.builder("storage.bytes").baseUnit("bytes")
                    .tag("operation", operation).tag("storage", storage.name()).tag("provider", storage.provider()).tag("direction", direction)
                    .register(meters).record(bytes);
        }
    }

    /** {@code storage.deleteAll.keys}: chaves de um {@code deleteAll}, com a tag {@code result} ({@code ok} ou {@code failed}). */
    @Override
    public void deletedKeys(StorageId storage, long ok, long failed) {
        MeterRegistry meters = registry.getIfAvailable();
        if (meters != null) {
            count(meters, storage, "ok", ok);
            count(meters, storage, "failed", failed);
        }
    }

    private static void count(MeterRegistry meters, StorageId storage, String result, long keys) {
        if (keys > 0) {
            Counter.builder("storage.deleteAll.keys").tag("storage", storage.name()).tag("provider", storage.provider()).tag("result", result)
                    .register(meters).increment(keys);
        }
    }

    /** {@code storage.operations.ttfb}: do pedido até {@code open}/{@code read} devolver o stream. */
    @Override
    public void ttfb(String operation, StorageId storage, long nanos) {
        MeterRegistry meters = registry.getIfAvailable();
        if (meters != null) {
            Timer.builder("storage.operations.ttfb").tag("operation", operation).tag("storage", storage.name()).tag("provider", storage.provider())
                    .register(meters).record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    /** {@code storage.multipart.parts}: cada tentativa de {@code uploadPart}; {@code count} = partes, {@code outcome} = success ou error. */
    @Override
    public void part(StorageId storage, String outcome, long nanos) {
        MeterRegistry meters = registry.getIfAvailable();
        if (meters != null) {
            Timer.builder("storage.multipart.parts").tag("storage", storage.name()).tag("provider", storage.provider()).tag("outcome", outcome)
                    .register(meters).record(nanos, TimeUnit.NANOSECONDS);
        }
    }
}
