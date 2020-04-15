load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")

# Maven

http_archive(
    name = "rules_jvm_external",
    sha256 = "62133c125bf4109dfd9d2af64830208356ce4ef8b165a6ef15bbff7460b35c3a",
    strip_prefix = "rules_jvm_external-3.0",
    url = "https://github.com/bazelbuild/rules_jvm_external/archive/3.0.zip",
)

load("@rules_jvm_external//:defs.bzl", "maven_install")

# Proto rules

git_repository(
    name = "com_google_protobuf",
    commit = "09745575a923640154bcf307fba8aedff47f240a",
    remote = "https://github.com/protocolbuffers/protobuf",
    shallow_since = "1558721209 -0700",
)

load("@com_google_protobuf//:protobuf_deps.bzl", "protobuf_deps")

protobuf_deps()

# GRPC rules

http_archive(
    name = "grpc_grpc_java",
    sha256 = "5a10cdb639456ec939be73d3f6a6dbd6f50fb6ee1d4a083d5b354a70774f68e3",
    strip_prefix = "grpc-java-1.27.1",
    urls = ["https://github.com/grpc/grpc-java/archive/v1.27.1.tar.gz"],
)

load("@grpc_grpc_java//:java_grpc_library.bzl", "java_grpc_library")

# Docker

http_archive(
    name = "io_bazel_rules_docker",
    sha256 = "dc97fccceacd4c6be14e800b2a00693d5e8d07f69ee187babfd04a80a9f8e250",
    strip_prefix = "rules_docker-0.14.1",
    urls = ["https://github.com/bazelbuild/rules_docker/releases/download/v0.14.1/rules_docker-v0.14.1.tar.gz"],
)

load(
    "@io_bazel_rules_docker//repositories:repositories.bzl",
    container_repositories = "repositories",
)

container_repositories()

load(
    "@io_bazel_rules_docker//java:image.bzl",
    _java_image_repos = "repositories",
)

_java_image_repos()

load("@io_bazel_rules_docker//container:container.bzl", "container_pull")

container_pull(
    name = "java11",
    #    digest = "sha256:c94feda039172152495b5cd60a350a03162fce4f8986b560ea555de4d276ce19",
    registry = "gcr.io",
    repository = "distroless/java",
    tag = "11",
)

# Cloud dbg

http_archive(
    name = "cloud_debugger",
    build_file = "@//:cloud_debugger.BUILD",
    sha256 = "678fb82501bf3ee358168063220d95b432199ba2bfe3018db6eb3eaf6be8ff50",
    urls = ["https://storage.googleapis.com/cloud-debugger/compute-java/debian-wheezy/cdbg_java_agent_gce.tar.gz"],
)

# 3p libs

maven_install(
    artifacts = [
        "com.google.guava:guava:28.2-jre",
        "com.google.inject:guice:4.2.2",
        "com.google.auto.value:auto-value:1.7",
        "com.google.auto.value:auto-value-annotations:1.7",
        "junit:junit:4.12",
        #
        "com.google.protobuf:protobuf-java-util:3.11.4",
        # Just for HttpUrl
        "com.squareup.okhttp3:okhttp:4.4.0",
        #
        "com.google.auth:google-auth-library-oauth2-http:0.20.0",
        #
        "io.grpc:grpc-all:1.27.2",
        "io.grpc:grpc-netty-shaded:1.27.2",
        "javax.annotation:javax.annotation-api:1.3.2",
        #
        "com.google.api-client:google-api-client:1.30.9",
        "com.google.http-client:google-http-client:1.34.2",
        "com.fasterxml.jackson.core:jackson-core:2.10.2",
        "com.fasterxml.jackson.core:jackson-annotations:2.10.2",
        "com.fasterxml.jackson.core:jackson-databind:2.10.2",
        "com.google.http-client:google-http-client-jackson2:jar:1.34.2",
        #
        "com.google.cloud:google-cloud-monitoring:1.100.0",
        "com.google.api.grpc:proto-google-common-protos:1.17.0",
        "com.google.api.grpc:proto-google-cloud-monitoring-v3:1.82.0",
        #
        "com.google.apis:google-api-services-sheets:v4-rev609-1.25.0",
    ],
    fetch_sources = True,
    generate_compat_repositories = True,
    maven_install_json = "//:maven_install.json",
    repositories = [
        "https://repo.maven.apache.org/maven2",
    ],
    version_conflict_policy = "pinned",
)

load("@maven//:defs.bzl", "pinned_maven_install")

pinned_maven_install()

load("@maven//:compat.bzl", "compat_repositories")

compat_repositories()
