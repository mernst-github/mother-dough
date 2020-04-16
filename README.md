What organically grew in a month or two. Rough organization from basic to complex:

`org.mernst`
* `.auto`: java_library dependency targets that give you auto-value/annotation processing.
* `.functional`: functional interfaces allowed to throw checked exceptions.
* `.collect`: "Streamable", the more modern "Iterable".
* `.json`: Jackson wrappers and simple Json output builders.
* `.context`: Grpc context-propagating thread pool.
* `.concurrent`: "Recipes", lazy Futures.
* `.server`: Guava Service + Guice-based server starter.
* `.grpc`: Guice modules to bind grpc clients and servers.
* `.http`: Guice modules to bind http clients and servers, w/ abstractions for http request handling, and result rendering.
* `.metrics`: Guice modules to bind metrics exported to Google Cloud Monitoring.
