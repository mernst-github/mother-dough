Some framework functionality is based off the grpc context (for example the "current" http exchange).
This package provides a pooled executor that properly propagates the
context across its tasks, so it should be used everywhere. It also
provides a context guice scope and annotation.
