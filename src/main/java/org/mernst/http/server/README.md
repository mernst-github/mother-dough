# HTTP Server Framework

Provides path-based routing and an Action abstraction with
injectable request entities, such as url, path, headers, parameters, ...
Uses JRE's com.sun.net.httpserver but does not expose its api.

HttpServiceModule binds a Service that provides the http server.
To register an action, it must be bound in the actions map binder.
This is achieved by installing possibly multiple subclasses of
ActionsModule:

```
install(new ActionsModule() {
  public void configureActions() {
    bindAction("/the/path").to(MyAction.class);
    bindAction("/the/directory/").to(MyOtherAction.class);
    redirectTemporarily("/", "/index.html");
  }
});
```

ActionsModule provides several convenience actions, such as redirects,
errors or classpath resources.

## Actions

Actions receive request information through injection. So actions should either:
* (preferred) be unscoped and have regular constructor parameters: `@Inject MyAction(@Path String path)`
* be singleton scoped and have provider parameters:  `@Inject MyAction(@Path Provider<String> path)`

If you lean towards the second form because you have something expensive request-independent 
initialization in your Action constructor, then that should probably be encapsulated
into an injected dependency which you can bind as singleton in turn:

```
bindAction("/path").to(MyAction.class);
@Inject MyAction(Dependency d), @Path String path) { ... }
bind(Dependency.class).in(Singleton.class);
```

At this point there's relatively little default behavior, an Action
gets to handle any request method. We might want to change that.

## Results

The action api is asynchronous, so Actions can execute IO without hoarding an HTTP handler thread.
Consequently Actions return a `Recipe<HttpResult>`. An HttpResult is a triple of (status code, headers, optional body).
To create a result, an action can use the static HttpResult factory methods:
```
return db.lookup().map(
  actualPath ->
    actualPath == null ?
      HttpResult.create(404) :
      HttpResult.temporaryRedirectTo(actualPath));
```

More complex results can be created via the `HttpResponder`. It implements
default request-aware behavior: 304 on ETag match. Since the body is lazy
and asynchronous itself this can save you from actually loading and serving
the bytes.

Example:
```
return db.lookup(path).map((contentType, etag, blobref)->
  responder.of(Body.of(contentType, etag, out -> <load and serve from blobref>)))
```

Responder will only load the blob when necessary. This is not a CDN replacement,
of course, you still have to have a serving instance and do the DB lookup.
