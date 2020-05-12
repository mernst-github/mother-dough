package org.mernst.http.server;

import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.google.common.io.Resources;
import org.mernst.collect.Streamable;
import org.mernst.concurrent.Plan;
import org.mernst.concurrent.Recipe;

import javax.inject.Provider;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public abstract class ResourceModule extends ActionModule {
  private final Class<?> baseClass;
  private final String resourceDirectory;
  private final Map<String, String> contentTypes = new HashMap<>(Codes.CONTENT_TYPES);

  public ResourceModule(String basePath, Class<?> baseClass, String resourceDirectory) {
    super(basePath);
    this.baseClass = baseClass;
    this.resourceDirectory = resourceDirectory;
  }

  public ResourceModule(String basePath, String resourceDirectory) {
    super(basePath);
    this.baseClass = null;
    this.resourceDirectory = resourceDirectory;
  }

  protected void mapExtension(String extension, String contentType) {
    contentTypes.put(extension, contentType);
  }

  protected ResourcesBinder bindResourcesAs(String contentType) {
    return new ResourcesBinder(contentType);
  }

  protected ResourcesBinder bindResources() {
    return new ResourcesBinder(null);
  }

  public class ResourcesBinder {
    ResourcesBinder(String contentType) {
      this.contentType = contentType;
    }

    final String contentType;

    public void at(String path) {
      String contentType =
          Optional.ofNullable(this.contentType)
              .orElseGet(
                  () ->
                      Optional.of(path.lastIndexOf('.'))
                          .filter(i -> i != -1)
                          .map(i -> path.substring(i + 1))
                          .map(contentTypes::get)
                          .orElseThrow(
                              () ->
                                  new IllegalArgumentException(
                                      "Unable to guess content type from path " + path)));
      URL resource =
          baseClass == null
              ? Resources.getResource(resourceDirectory.substring(1) + path)
              : Resources.getResource(baseClass, resourceDirectory.substring(1) + path);

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

      Provider<HttpResponder> responder = getProvider(HttpResponder.class);
      bindAction(path).toInstance(() -> Recipe.to(responder.get().ifUnmodified(body)));
    }

    public void at(Streamable<String> paths) {
      paths.stream().forEach(this::at);
    }

    public void at(String... paths) {
      at(Streamable.of(paths));
    }
  }
}
