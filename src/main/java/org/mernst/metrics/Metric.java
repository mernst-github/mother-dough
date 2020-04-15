package org.mernst.metrics;

import com.google.api.Distribution;
import com.google.api.MetricDescriptor;
import com.google.api.MonitoredResource;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.AtomicLongMap;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.TypedValue;
import com.google.protobuf.util.Timestamps;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;

public class Metric {
  @AutoValue
  abstract static class Key {
    abstract Map<String, String> labels();

    abstract int bucketIndex();
  }

  final String type;
  final AtomicLongMap<Key> counters = AtomicLongMap.create();

  Instant since = Instant.now();
  ImmutableMap<Map<String, String>, Map<Integer, Long>> previous = ImmutableMap.of();

  public Metric(String type) {
    this.type = type;
  }

  void record(Map<String, String> variables, long value, int delta) {
    counters.addAndGet(new AutoValue_Metric_Key(variables, bucketIndex(value)), delta);
  }

  public void record(Map<String, String> variables, long value) {
    record(variables, value, 1);
  }

  ImmutableList<TimeSeries> asTimeSeries(MonitoredResource resource) {
    Instant since = this.since;
    Instant now = Instant.now();
    this.since = now;

    ImmutableMap<Map<String, String>, Map<Integer, Long>> groups =
        counters.asMap().entrySet().stream()
            .collect(Collectors.groupingBy(e -> e.getKey().labels()))
            .entrySet()
            .stream()
            .collect(
                toImmutableMap(
                    Map.Entry::getKey,
                    e ->
                        e.getValue().stream()
                            .collect(
                                toImmutableMap(
                                    e2 -> e2.getKey().bucketIndex(), Map.Entry::getValue))));

    ImmutableMap<Map<String, String>, ImmutableList<Long>> changed =
        groups.entrySet().stream()
            .filter(
                e ->
                    Optional.ofNullable(previous.get(e.getKey()))
                        .map(p -> !p.equals(e.getValue()))
                        .orElse(true))
            .collect(
                toImmutableMap(
                    Map.Entry::getKey,
                    entry -> {
                      Map<Integer, Long> buckets = entry.getValue();
                      int numBuckets =
                          1 + buckets.keySet().stream().max(Integer::compareTo).orElse(-1);
                      return IntStream.range(0, bucketBoundaries.length)
                          .limit(numBuckets)
                          .mapToLong(i -> buckets.getOrDefault(i, 0L))
                          .boxed()
                          .collect(toImmutableList());
                    }));

    previous = groups;

    return timeSeries(resource, since, now, changed);
  }

  private ImmutableList<TimeSeries> timeSeries(
      MonitoredResource resource,
      Instant since,
      Instant now,
      Map<Map<String, String>, ImmutableList<Long>> groups) {
    return groups.entrySet().stream()
        .map(
            labelled ->
                TimeSeries.newBuilder()
                    .setMetric(
                        com.google.api.Metric.newBuilder()
                            .setType(type)
                            .putAllLabels(labelled.getKey())
                            .build())
                    .setResource(resource)
                    .setMetricKind(MetricDescriptor.MetricKind.CUMULATIVE)
                    .setValueType(MetricDescriptor.ValueType.DISTRIBUTION)
                    .addPoints(
                        Point.newBuilder()
                            .setInterval(
                                TimeInterval.newBuilder()
                                    .setStartTime(Timestamps.fromMillis(since.toEpochMilli()))
                                    .setEndTime(Timestamps.fromMillis(now.toEpochMilli()))
                                    .build())
                            .setValue(
                                TypedValue.newBuilder()
                                    .setDistributionValue(
                                        Distribution.newBuilder()
                                            .setBucketOptions(
                                                Distribution.BucketOptions.newBuilder()
                                                    .setExplicitBuckets(
                                                        Distribution.BucketOptions.Explicit
                                                            .newBuilder()
                                                            .addAllBounds(
                                                                () ->
                                                                    Arrays.stream(bucketBoundaries)
                                                                        .mapToObj(l -> (double) l)
                                                                        .iterator())
                                                            .build())
                                                    .build())
                                            .addAllBucketCounts(labelled.getValue())
                                            .setCount(
                                                labelled.getValue().stream()
                                                    .reduce(0L, Long::sum)))))
                    .build())
        .collect(toImmutableList());
  }

  static int bucketIndex(long value) {
    if (value < 0) {
      return 0;
    }
    if (value < 2) {
      return 1 + (int) value;
    }
    int base = 0;
    while (value > 20) {
      value = (value + 5) / 10;
      base += 18;
    }
    return 1 + base + (int) value;
  }

  private static long[] bucketBoundaries = {
    0L,
    1L,
    2L,
    3L,
    4L,
    5L,
    6L,
    7L,
    8L,
    9L,
    10L,
    11L,
    12L,
    13L,
    14L,
    15L,
    16L,
    17L,
    18L,
    19L,
    20L,
    30L,
    40L,
    50L,
    60L,
    70L,
    80L,
    90L,
    100L,
    110L,
    120L,
    130L,
    140L,
    150L,
    160L,
    170L,
    180L,
    190L,
    200L,
    300L,
    400L,
    500L,
    600L,
    700L,
    800L,
    900L,
    1000L,
    1100L,
    1200L,
    1300L,
    1400L,
    1500L,
    1600L,
    1700L,
    1800L,
    1900L,
    2000L,
    3000L,
    4000L,
    5000L,
    6000L,
    7000L,
    8000L,
    9000L,
    10000L,
    11000L,
    12000L,
    13000L,
    14000L,
    15000L,
    16000L,
    17000L,
    18000L,
    19000L,
    20000L,
    30000L,
    40000L,
    50000L,
    60000L,
    70000L,
    80000L,
    90000L,
    100000L,
    110000L,
    120000L,
    130000L,
    140000L,
    150000L,
    160000L,
    170000L,
    180000L,
    190000L,
    200000L,
    300000L,
    400000L,
    500000L,
    600000L,
    700000L,
    800000L,
    900000L,
    1000000L,
    1100000L,
    1200000L,
    1300000L,
    1400000L,
    1500000L,
    1600000L,
    1700000L,
    1800000L,
    1900000L,
    2000000L,
    3000000L,
    4000000L,
    5000000L,
    6000000L,
    7000000L,
    8000000L,
    9000000L,
    10000000L,
    11000000L,
    12000000L,
    13000000L,
    14000000L,
    15000000L,
    16000000L,
    17000000L,
    18000000L,
    19000000L,
    20000000L,
    30000000L,
    40000000L,
    50000000L,
    60000000L,
    70000000L,
    80000000L,
    90000000L,
    100000000L,
    110000000L,
    120000000L,
    130000000L,
    140000000L,
    150000000L,
    160000000L,
    170000000L,
    180000000L,
    190000000L,
    200000000L,
    300000000L,
    400000000L,
    500000000L,
    600000000L,
    700000000L,
    800000000L,
    900000000L,
    1000000000L,
    1100000000L,
    1200000000L,
    1300000000L,
    1400000000L,
    1500000000L,
    1600000000L,
    1700000000L,
    1800000000L,
    1900000000L,
    2000000000L,
    3000000000L,
    4000000000L,
    5000000000L,
    6000000000L,
    7000000000L,
    8000000000L,
    9000000000L,
    10000000000L,
    11000000000L,
    12000000000L,
    13000000000L,
    14000000000L,
    15000000000L,
    16000000000L,
    17000000000L,
    18000000000L,
    19000000000L,
    20000000000L,
    30000000000L,
    40000000000L,
    50000000000L,
    60000000000L,
    70000000000L,
    80000000000L,
    90000000000L,
    100000000000L,
    110000000000L,
    120000000000L,
    130000000000L,
    140000000000L,
    150000000000L,
    160000000000L,
    170000000000L,
    180000000000L,
    190000000000L,
    200000000000L,
    300000000000L,
    400000000000L,
    500000000000L,
    600000000000L,
    700000000000L,
    800000000000L,
    900000000000L,
    1000000000000L,
    1100000000000L,
    1200000000000L,
    1300000000000L,
    1400000000000L,
    1500000000000L,
    1600000000000L,
    1700000000000L,
    1800000000000L,
    1900000000000L,
    2000000000000L,
    3000000000000L,
    4000000000000L,
    5000000000000L,
    6000000000000L,
    7000000000000L,
    8000000000000L,
    9000000000000L,
    10000000000000L,
    11000000000000L,
    12000000000000L,
    13000000000000L,
    14000000000000L,
    15000000000000L,
    16000000000000L,
    17000000000000L,
    18000000000000L,
    19000000000000L,
    20000000000000L,
    30000000000000L,
    40000000000000L,
    50000000000000L,
    60000000000000L,
    70000000000000L,
    80000000000000L,
    90000000000000L,
    100000000000000L,
    110000000000000L,
    120000000000000L,
    130000000000000L,
    140000000000000L,
    150000000000000L,
    160000000000000L,
    170000000000000L,
    180000000000000L,
    190000000000000L,
    200000000000000L,
    300000000000000L,
    400000000000000L,
    500000000000000L,
    600000000000000L,
    700000000000000L,
    800000000000000L,
    900000000000000L,
    1000000000000000L,
    1100000000000000L,
    1200000000000000L,
    1300000000000000L,
    1400000000000000L,
    1500000000000000L,
    1600000000000000L,
    1700000000000000L,
    1800000000000000L,
    1900000000000000L,
    2000000000000000L,
    3000000000000000L,
    4000000000000000L,
    5000000000000000L,
    6000000000000000L,
    7000000000000000L,
    8000000000000000L,
    9000000000000000L,
    10000000000000000L,
    11000000000000000L,
    12000000000000000L,
    13000000000000000L,
    14000000000000000L,
    15000000000000000L,
    16000000000000000L,
    17000000000000000L,
    18000000000000000L,
    19000000000000000L,
    20000000000000000L,
    30000000000000000L,
    40000000000000000L,
    50000000000000000L,
    60000000000000000L,
    70000000000000000L,
    80000000000000000L,
    90000000000000000L,
    100000000000000000L,
    110000000000000000L,
    120000000000000000L,
    130000000000000000L,
    140000000000000000L,
    150000000000000000L,
    160000000000000000L,
    170000000000000000L,
    180000000000000000L,
    190000000000000000L,
    200000000000000000L,
    300000000000000000L,
    400000000000000000L,
    500000000000000000L,
    600000000000000000L,
    700000000000000000L,
    800000000000000000L,
    900000000000000000L,
    1000000000000000000L,
    1100000000000000000L,
    1200000000000000000L,
    1300000000000000000L,
    1400000000000000000L,
    1500000000000000000L,
    1600000000000000000L,
    1700000000000000000L,
    1800000000000000000L,
    1900000000000000000L,
    2000000000000000000L,
    3000000000000000000L,
    4000000000000000000L,
    5000000000000000000L,
    6000000000000000000L,
    7000000000000000000L,
    8000000000000000000L,
    9000000000000000000L,
  };
}
