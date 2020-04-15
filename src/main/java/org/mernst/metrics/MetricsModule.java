package org.mernst.metrics;

import com.google.inject.AbstractModule;
import com.google.inject.Binder;
import com.google.inject.BindingAnnotation;
import com.google.inject.Key;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;

import javax.inject.Singleton;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public abstract class MetricsModule extends AbstractModule {

  @Override
  protected void configure() {
    configureMetrics();
  }

  protected abstract void configureMetrics();

  protected void bindMetric(String type) {
    Key<Metric> key = Key.get(Metric.class, Names.named(type));
    binder().bind(key).toProvider(() -> new Metric(type)).in(Singleton.class);
    metricBinder(binder()).addBinding().to(key);
  }

  static Multibinder<Metric> metricBinder(Binder binder) {
    return Multibinder.newSetBinder(binder, Metric.class, Annotation.class);
  }

  @BindingAnnotation
  @Retention(RetentionPolicy.RUNTIME)
  @interface Annotation {}
}
