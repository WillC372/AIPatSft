package com.aquaassistant.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aquaassistant.intelligence.analysis.Anomaly;
import com.aquaassistant.intelligence.analysis.AnomalyDetector;
import com.aquaassistant.intelligence.analysis.Forecast;
import com.aquaassistant.intelligence.analysis.Forecaster;
import com.aquaassistant.intelligence.analysis.OutOfRangeRule;
import com.aquaassistant.intelligence.analysis.StatisticalOutlierRule;
import com.aquaassistant.intelligence.analysis.Statistics;
import com.aquaassistant.intelligence.analysis.PhAlterationRule;
import com.aquaassistant.intelligence.analysis.TrendAnalyzer;
import com.aquaassistant.intelligence.analysis.TrendResult;
import com.aquaassistant.measurement.Measurement;
import com.aquaassistant.profile.WaterProfile;
import com.aquaassistant.support.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalysisTest {

    private final WaterProfile pool = TestData.pool();
    private final AnomalyDetector detector = new AnomalyDetector(
        List.of(new OutOfRangeRule(), new PhAlterationRule(), new StatisticalOutlierRule()));

    @Test
    void regressionRecoversKnownLine() {
        Statistics.Regression r = Statistics.linearRegression(new double[] {0, 1, 2, 3}, new double[] {7, 7.1, 7.2, 7.3});
        assertThat(r.slope()).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.r2()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void detectsOutOfRangeSuddenChangeAndOutlier() {
        Instant start = Instant.now().minus(Duration.ofDays(2));
        List<Measurement> history = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            history.add(TestData.measurement(i, 7.4 + (i % 2) * 0.02, start.plus(Duration.ofHours(i)), pool));
        }
        Measurement spike = TestData.measurement(99, 8.6, start.plus(Duration.ofHours(11)).plus(Duration.ofMinutes(20)), pool);
        List<String> types = detector.detect(spike, history, pool).stream().map(Anomaly::type).toList();
        assertThat(types).containsExactlyInAnyOrder("OUT_OF_RANGE", "PH_ALTERATION", "STATISTICAL_OUTLIER");
    }

    /** H-003: the backend alarm and the analyses of this service give the same verdict for the same readings. */
    @Test
    void sameVerdictAsTheBackendAlarm() {
        Instant now = Instant.now();
        WaterProfile general = new WaterProfile(null, "Uso general", com.aquaassistant.profile.ProfileType.GENERAL,
            new com.aquaassistant.profile.PhRange(6.5, 8.5, 6.0, 9.0), "", "");
        PhAlterationRule rule = new PhAlterationRule();
        // 7.0 -> 8.1 in 5 minutes with the general profile: an alteration, but not dangerous (it stays in the ideal range).
        Anomaly a = rule.evaluate(TestData.measurement(2, 8.1, now, general),
            List.of(TestData.measurement(1, 7.0, now.minus(Duration.ofMinutes(5)), general)), general).orElseThrow();
        assertThat(a.severity()).isEqualTo(Anomaly.Severity.CAUTION);
        // Pool 7.5 -> 8.1: critical for a pool, so dangerous.
        Anomaly b = rule.evaluate(TestData.measurement(4, 8.1, now, pool),
            List.of(TestData.measurement(3, 7.5, now.minus(Duration.ofMinutes(5)), pool)), pool).orElseThrow();
        assertThat(b.severity()).isEqualTo(Anomaly.Severity.CRITICAL);
        // A gradual rise 7.0, 7.2, 7.4, 7.6 within the hour is seen too (the old rule only compared with the last one).
        List<Measurement> rising = List.of(TestData.measurement(5, 7.0, now.minus(Duration.ofMinutes(30)), general),
            TestData.measurement(6, 7.2, now.minus(Duration.ofMinutes(20)), general),
            TestData.measurement(7, 7.4, now.minus(Duration.ofMinutes(10)), general));
        assertThat(rule.evaluate(TestData.measurement(8, 7.6, now, general), rising, general)).isPresent();
        // H-056: a critical reading sounds the alarm even when the previous one is older than an hour.
        Anomaly c = rule.evaluate(TestData.measurement(10, 12.67, now, general),
            List.of(TestData.measurement(9, 7.3, now.minus(Duration.ofMinutes(76)), general)), general).orElseThrow();
        assertThat(c.severity()).isEqualTo(Anomaly.Severity.CRITICAL);
        assertThat(c.message()).startsWith("El pH subió de 7.30 a 12.67 en 1 hora y 16 minutos.");
    }

    @Test
    void stableHistoryProducesNoAnomalies() {
        Instant start = Instant.now().minus(Duration.ofDays(3));
        List<Measurement> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(TestData.measurement(i, 7.4 + (i % 3) * 0.02, start.plus(Duration.ofHours(3L * i)), pool));
        }
        assertThat(detector.scan(history, pool)).isEmpty();
    }

    @Test
    void trendNeedsMinimumDataAndDetectsDirection() {
        assertThat(new TrendAnalyzer().analyze(List.of(), List.of()).enoughData()).isFalse();
        Instant start = Instant.now().minus(Duration.ofDays(6));
        List<Measurement> rising = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            rising.add(TestData.measurement(i, 7.0 + 0.1 * i, start.plus(Duration.ofDays(i)), pool));
        }
        TrendResult trend = new TrendAnalyzer().analyze(rising, List.of());
        assertThat(trend.direction()).isEqualTo("RISING");
        assertThat(trend.slopePerDay()).isCloseTo(0.1, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void forecasterRefusesWithoutEnoughHistory() {
        Instant start = Instant.now().minus(Duration.ofHours(10));
        List<Measurement> few = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            few.add(TestData.measurement(i, 7.2, start.plus(Duration.ofMinutes(40L * i)), pool));
        }
        Forecast forecast = new Forecaster().forecast(few, 7, Instant.now());
        assertThat(forecast.available()).isFalse();
        assertThat(forecast.reason()).contains("días");
    }

    @Test
    void forecasterProjectsTrendWithUncertainty() {
        Instant now = Instant.now();
        Instant start = now.minus(Duration.ofDays(10));
        List<Measurement> history = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            double noise = (i % 2 == 0 ? 0.03 : -0.03);
            history.add(TestData.measurement(i, 7.0 + 0.05 * (i / 2.0) + noise, start.plus(Duration.ofHours(12L * i)), pool));
        }
        Forecast forecast = new Forecaster().forecast(history, 7, now);
        assertThat(forecast.available()).isTrue();
        assertThat(forecast.horizonDays()).isEqualTo(7);
        Forecast.Point last = forecast.points().get(6);
        assertThat(last.predicted()).isGreaterThan(7.5);
        assertThat(last.lower()).isLessThan(last.predicted());
        assertThat(last.upper()).isGreaterThan(last.predicted());
        double firstWidth = forecast.points().get(0).upper() - forecast.points().get(0).lower();
        assertThat(last.upper() - last.lower()).isGreaterThanOrEqualTo(firstWidth);
    }
}
