What organically grew in a month or two. Rough organization from basic to complex:

`org.mernst`
* [`.auto`](src/main/java/org/mernst/auto): java_library dependency targets that give you auto-value/annotation processing.
* [`.functional`](src/main/java/org/mernst/functional): functional interfaces allowed to throw checked exceptions.
* [`.collect`](src/main/java/org/mernst/collect): "Streamable", the more modern "Iterable".
* [`.json`](src/main/java/org/mernst/json): Jackson wrappers and simple Json output builders.
* [`.context`](src/main/java/org/mernst/context): Grpc context-propagating thread pool.
* [`.concurrent`](src/main/java/org/mernst/concurrent): "Recipes", lazy Futures.
* [`.server`](src/main/java/org/mernst/server): Guava Service + Guice-based server starter.
* [`.grpc`](src/main/java/org/mernst/grpc): Guice modules to bind grpc clients and servers.
* [`.http`](src/main/java/org/mernst/http): Guice modules to bind http clients and servers, w/ abstractions for http request handling, and result rendering.
* [`.metrics`](src/main/java/org/mernst/metrics): Guice modules to bind metrics exported to Google Cloud Monitoring.
