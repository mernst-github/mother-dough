package org.mernst.http.server;

import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.google.common.io.Resources;
import org.mernst.concurrent.Plan;
import org.mernst.concurrent.Recipe;

import javax.inject.Provider;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;

public abstract class ResourceModule extends ActionModule {
  private final Class<?> baseClass;
  private final String resourceDirectory;

  public ResourceModule(String basePath, Class<?> baseClass, String resourceDirectory) {
    super(basePath);
    this.baseClass = baseClass;
    this.resourceDirectory = resourceDirectory;
  }

  public ResourceModule(String basePath, String resourceDirectory) {
    super(basePath);
    this.baseClass = getClass();
    this.resourceDirectory = resourceDirectory;
  }

  protected void bindResource(String relativePath, String contentType) {
    Provider<HttpResponder> responder = getProvider(HttpResponder.class);
    URL resource =
        Resources.getResource(baseClass, resourceDirectory.substring(1) + relativePath);

    HashingOutputStream hasher =
        new HashingOutputStream(Hashing.murmur3_128(0), OutputStream.nullOutputStream());
    try {
      Resources.copy(resource, hasher);
    } catch (IOException io) {
      throw new IllegalArgumentException(resource.toString(), io);
    }

    String eTag = String.format("\"%s\"", hasher.hash());
    HttpResult.Body body =
        HttpResult.Body.of(contentType, eTag, os -> Plan.of(() -> Resources.copy(resource, os)));
    bindAction(relativePath).toInstance(() -> Recipe.to(responder.get().ifUnmodified(body)));
  }
}
