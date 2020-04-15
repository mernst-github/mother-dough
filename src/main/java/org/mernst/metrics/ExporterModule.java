package org.mernst.metrics;

import com.google.api.MonitoredResource;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.google.common.util.concurrent.Service;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.multibindings.ProvidesIntoSet;
import com.google.monitoring.v3.CreateTimeSeriesRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public class ExporterModule extends AbstractModule {

  private final String instanceId;

  public ExporterModule() {
    instanceId = String.valueOf(Duration.between(Instant.EPOCH, Instant.now()).toNanos());
  }

  @Override
  protected void configure() {
    MetricsModule.metricBinder(binder());
  }

  @ProvidesIntoSet
  Service exporter(MonitoredResource resource, @MetricsModule.Annotation Set<Metric> metrics)
      throws Exception {
    MetricServiceClient metricServiceClient = MetricServiceClient.create();
    return new AbstractScheduledService() {
      @Override
      protected Scheduler scheduler() {
        return Scheduler.newFixedDelaySchedule(Duration.ofSeconds(60), Duration.ofSeconds(60));
      }

      @Override
      protected void runOneIteration() {
        Streams.stream(
                Iterables.partition(
                    () ->
                        metrics.stream()
                            .map(m -> m.asTimeSeries(resource))
                            .flatMap(Collection::stream)
                            .iterator(),
                    200))
            .map(
                tsl ->
                    CreateTimeSeriesRequest.newBuilder()
                        .setName("projects/" + resource.getLabelsOrThrow("project_id"))
                        .addAllTimeSeries(tsl)
                        .build())
            .forEach(
                ts -> {
                  System.out.println(
                      "Exporting " + ts.getTimeSeriesCount() + " time series to " + resource);
                  try {
                    metricServiceClient.createTimeSeries(ts);
                    System.out.println("Export done");
                  } catch (Exception e) {
                    System.out.println("Failed to export:");
                    e.printStackTrace();
                  }
                });
      }
    };
  }

  @Provides
  MonitoredResource monitoredResource() {
    String projectId =
        getMetadata("/computeMetadata/v1/project/project-id").orElse("invertible-vine-268215");
    return getenv("K_SERVICE")
        .map(
            unused ->
                MonitoredResource.newBuilder()
                    .setType("generic_task")
                    .putAllLabels(
                        ImmutableMap.of(
                            "project_id",
                            projectId,
                            "location",
                            getMetadata("/computeMetadata/v1/instance/zone")
                                .map(zone -> zone.split("/")[3])
                                .orElse("local"),
                            "namespace",
                            getenv("K_SERVICE").orElse("frontend"),
                            "job",
                            getenv("K_REVISION").orElse("dev"),
                            "task_id",
                            instanceId))
                    .build())
        .orElseGet(
            () ->
                MonitoredResource.newBuilder()
                    .setType("global")
                    .putAllLabels(ImmutableMap.of("project_id", projectId))
                    .build());
  }

  private Optional<String> getenv(String name) {
    return Optional.ofNullable(System.getenv(name));
  }

  private Optional<String> getMetadata(String path) {
    if (System.getenv("K_SERVICE") == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new OkHttpClient()
              .newCall(
                  new Request.Builder()
                      .url("http://metadata.google.internal" + path)
                      .header("Metadata-Flavor", "Google")
                      .build())
              .execute()
              .body()
              .string());
    } catch (IOException e) {
      throw new StatusRuntimeException(Status.INTERNAL.withCause(e));
    }
  }
}
