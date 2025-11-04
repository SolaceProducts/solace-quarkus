package com.solace.quarkus.runtime.observability;

import java.util.Enumeration;

import jakarta.enterprise.inject.spi.CDI;

import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.statistics.StatType;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class SolaceMetricBinder {

    public void initMetrics() {
        var solaceSession = CDI.current().select(JCSMPSession.class).get();
        var registry = CDI.current().select(MeterRegistry.class).get();
        Enumeration<StatType> stats = StatType.elements();
        while (stats.hasMoreElements()) {
            StatType statType = stats.nextElement();
            Gauge.builder("solace.session." + statType.getLabel().toLowerCase().replace("_", "."),
                    () -> solaceSession.getSessionStats().getStat(statType))
                    .register(registry);
        }
    }

}
