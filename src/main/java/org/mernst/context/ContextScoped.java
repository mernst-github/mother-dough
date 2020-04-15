package org.mernst.context;

import com.google.inject.ScopeAnnotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@ScopeAnnotation
@Retention(RetentionPolicy.RUNTIME)
public @interface ContextScoped {}
