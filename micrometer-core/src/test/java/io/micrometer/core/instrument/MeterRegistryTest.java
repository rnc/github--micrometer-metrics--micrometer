/*
 * Copyright 2017 VMware, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.core.instrument;

import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.noop.NoopCounter;
import io.micrometer.core.instrument.noop.NoopTimer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link MeterRegistry}.
 *
 * @author Jon Schneider
 * @author Johnny Lim
 */
class MeterRegistryTest {
    private MeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void acceptMeterFilter() {
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                return id.getName().contains("jvm") ? MeterFilterReply.DENY : MeterFilterReply.NEUTRAL;
            }
        });

        assertThat(registry.counter("jvm.my.counter")).isInstanceOf(NoopCounter.class);
        assertThat(registry.counter("my.counter")).isNotInstanceOf(NoopCounter.class);
    }

    @Test
    void overridingAcceptMeterFilter() {
        registry.config().meterFilter(MeterFilter.accept(m -> m.getName().startsWith("jvm.important")));
        registry.config().meterFilter(MeterFilter.deny(m -> m.getName().startsWith("jvm")));
	
        assertThat(registry.counter("jvm.my.counter")).isInstanceOf(NoopCounter.class);
        assertThat(registry.counter("jvm.important.counter")).isNotInstanceOf(NoopCounter.class);
        assertThat(registry.counter("my.counter")).isNotInstanceOf(NoopCounter.class);
    }

    @Test
    void idTransformingMeterFilter() {
        registry.config().meterFilter(MeterFilter.ignoreTags("k1"));

        registry.counter("my.counter", "k1", "v1");
        registry.get("my.counter").counter();
        assertThat(registry.find("my.counter").tags("k1", "v1").counter()).isNull();
    }

    @Test
    void histogramConfigTransformingMeterFilter() {
        MeterRegistry registry = new SimpleMeterRegistry() {
            @Override
            protected Timer newTimer(@Nonnull Meter.Id id, DistributionStatisticConfig histogramConfig, PauseDetector pauseDetector) {
                assertThat(histogramConfig.isPublishingHistogram()).isTrue();
                return super.newTimer(id, histogramConfig, pauseDetector);
            }
        };

        registry.config().meterFilter(new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id mappedId, DistributionStatisticConfig config) {
                return DistributionStatisticConfig.builder()
                    .percentiles(0.95)
                    .percentilesHistogram(true)
                    .build()
                    .merge(config);
            }
        });

        registry.timer("my.timer");
    }

    @Test
    void noopMetersAfterRegistryClosed() {
        assertThat(registry.timer("my.timer.before")).isNotInstanceOf(NoopTimer.class);
        registry.close();

        assertThat(registry.isClosed()).isTrue();

        assertThat(registry.timer("my.timer.before")).isNotInstanceOf(NoopTimer.class);
        assertThat(registry.timer("my.timer.after")).isInstanceOf(NoopTimer.class);
    }

    @Test
    void removeMeters() {
        registry.counter("my.counter");

        Counter counter = registry.find("my.counter").counter();
        assertThat(counter).isNotNull();

        assertThat(registry.remove(counter)).isSameAs(counter);
        assertThat(registry.find("my.counter").counter()).isNull();
        assertThat(registry.remove(counter)).isNull();
    }

    @Test
    void removeMetersAffectedByMeterFilter() {
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                return id.withName("another.name");
            }
        });

        Counter counter = registry.counter("name");
        assertThat(registry.find("another.name").counter()).isSameAs(counter);
        assertThat(registry.remove(counter)).isSameAs(counter);
        assertThat(registry.find("another.name").counter()).isNull();
    }

    @Test
    void removeMetersAffectedByNonIdempotentMeterFilter() {
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                return id.withName("prefix." + id.getName());
            }
        });

        Counter counter = registry.counter("name");
        assertThat(registry.find("prefix.name").counter()).isSameAs(counter);
        assertThat(registry.remove(counter)).isSameAs(counter);
        assertThat(registry.find("prefix.name").counter()).isNull();
    }

    @Test
    void removeMetersWithSynthetics() {
        Timer timer = Timer.builder("my.timer")
                .publishPercentiles(0.95)
                .register(registry);

        assertThat(registry.getMeters()).hasSize(2);
        registry.remove(timer);
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void removeMetersWithSyntheticsAffectedByMeterFilter() {
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                return id.withName("another.name");
            }
        });

        Timer timer = Timer.builder("my.timer")
                .publishPercentiles(0.95)
                .register(registry);

        assertThat(registry.getMeters()).hasSize(2);
        registry.remove(timer);
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void clear() {
        registry.counter("my.counter");
        registry.counter("my.counter2");

        assertThat(registry.find("my.counter").counter()).isNotNull();
        assertThat(registry.find("my.counter2").counter()).isNotNull();

        registry.clear();

        assertThat(registry.find("my.counter").counter()).isNull();
        assertThat(registry.find("my.counter2").counter()).isNull();
    }

    @Test
    void gaugeRegistersGaugeOnceAndSubsequentGaugeCallsWillNotRegister() {
        registry.gauge("my.gauge", 1d);
        registry.gauge("my.gauge", 2d);

        assertThat(registry.get("my.gauge").gauge().value()).isEqualTo(1d);
    }

    @Test
    void shouldNotLetRegisteringMetersTwice() {
        registry.counter("my.dupe.meter");
        assertThatThrownBy(() -> registry.timer("my.dupe.meter"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("There is already a registered meter of a different type (CumulativeCounter vs. Timer) with the same name: my.dupe.meter")
                .hasNoCause();
    }

    @Test
    void openingScopeShouldSetSampleAsCurrent() {
        Timer.Builder timerBuilder = Timer.builder("test.timer");
        Timer.Sample sample = Timer.start(registry);
        Timer.Scope scope = registry.openNewScope(sample);

        assertThat(registry.getCurrentSample()).isSameAs(sample);

        scope.close();
        sample.stop(timerBuilder);

        assertThat(registry.getCurrentSample()).isNull();
    }

    @Test
    void timerRecordingHandlerShouldAddThePassedHandler() {
        TimerRecordingHandler<?> handler1 = mock(TimerRecordingHandler.class);
        TimerRecordingHandler<?> handler2 = mock(TimerRecordingHandler.class);

        registry.config().timerRecordingHandler(handler1);
        assertThat(registry.config().getTimerRecordingHandlers()).containsExactly(handler1);

        registry.config().timerRecordingHandler(handler2);
        assertThat(registry.config().getTimerRecordingHandlers()).containsExactlyInAnyOrder(handler1, handler2);
    }
}
