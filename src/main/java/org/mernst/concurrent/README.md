A lazy alternative to `ListenableFuture`.

Properties:
* a recipe is not evaluating yet, it is more like a `Supplier<ListenableFuture>`.
* it does not remember its result.
* there is only one consumer.
