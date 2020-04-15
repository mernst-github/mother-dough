package org.mernst.metrics;

import com.google.api.MonitoredResource;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.monitoring.v3.TimeSeries;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.collect.Iterables.getOnlyElement;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(JUnit4.class)
public class MetricTest {
  @Test
  public void buckets() {
    assertEquals(1, Metric.bucketIndex(0));
    assertEquals(2, Metric.bucketIndex(1));
    assertEquals(20, Metric.bucketIndex(19));
    assertEquals(21, Metric.bucketIndex(20));
    assertEquals(21, Metric.bucketIndex(24));
    assertEquals(22, Metric.bucketIndex(25));
    assertEquals(22, Metric.bucketIndex(34));
    assertEquals(23, Metric.bucketIndex(35));
    assertEquals(23, Metric.bucketIndex(44));
    assertEquals(29, Metric.bucketIndex(99));
    assertEquals(39, Metric.bucketIndex(199));
    assertEquals(40, Metric.bucketIndex(299));
  }

  @Test
  public void request() {
    Metric sut = new Metric("metricName");
    ImmutableMap<String, String> m1 = ImmutableMap.of("l1", "v1", "l2", "v2");
    ImmutableMap<String, String> m2 = ImmutableMap.of("l1", "v1", "l2", "v3");

    sut.record(m1, 1);
    sut.record(m2, 1);
    sut.record(m2, 2);
    sut.record(m2, 2);

    Map<Map<String, String>, List<TimeSeries>> tss = map(sut);
    assertEquals(
        ImmutableList.of(0L, 0L, 1L),
        getOnlyElement(getOnlyElement(tss.get(m1)).getPointsList())
            .getValue()
            .getDistributionValue()
            .getBucketCountsList());

    assertEquals(
        ImmutableList.of(0L, 0L, 1L, 2L),
        getOnlyElement(getOnlyElement(tss.get(m2)).getPointsList())
            .getValue()
            .getDistributionValue()
            .getBucketCountsList());

    assertTrue(
        sut.asTimeSeries(
                MonitoredResource.newBuilder()
                    .setType("global")
                    .putAllLabels(ImmutableMap.of("project_id", "myproject"))
                    .build())
            .isEmpty());
  }

  static Map<Map<String, String>, List<TimeSeries>> map(Metric sut) {
    ImmutableList<TimeSeries> createTimeSeriesRequest =
        sut.asTimeSeries(
            MonitoredResource.newBuilder()
                .setType("global")
                .putAllLabels(ImmutableMap.of("project_id", "myproject"))
                .build());
    return createTimeSeriesRequest.stream()
        .collect(Collectors.groupingBy(ts -> ts.getMetric().getLabelsMap()));
  }
}
