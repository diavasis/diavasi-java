# Java client

[![CI](https://github.com/diavasis/diavasi-java/actions/workflows/ci.yml/badge.svg)](https://github.com/diavasis/diavasi-java/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/dev.diavasi/diavasi-client.svg)](https://central.sonatype.com/artifact/dev.diavasi/diavasi-client)
[![license](https://img.shields.io/github/license/diavasis/diavasi-java)](https://github.com/diavasis/diavasi-java/blob/main/LICENSE)

`dev.diavasi.client.DiavasiClient` is a thin client of `diavasi.data.v1`, built with grpc-java. `consume` opens a TLS stream, sends the bearer token, Hello version 1, then JoinGroup, and acks each batch. The client stores no cursor and does not dedupe on `record_id`. A dropped stream is how unacked batches return. Reconnect with the same consumer id and the server replays them.

`proto/data.proto` in this repository is the copy of `diavasi.data.v1` from [github.com/diavasis/diavasi](https://github.com/diavasis/diavasi) tag `v0.13.0`. Maven coordinates are `dev.diavasi:diavasi-client:0.1.0`. The published bytecode targets Java 17. CI runs the tests on Temurin 17, 21, 25, and 27. The Gradle daemon always uses JDK 21, because Gradle cannot yet run on Java 27. Each matrix job passes `-PtestJavaVersion` so the test JVM is the matrix release.

## Install

```gradle
implementation "dev.diavasi:diavasi-client:0.1.0"
```

Build from this repository with JDK 17 or newer and Gradle on `PATH`:

```bash
gradle installDist
```

That compiles the library, generates the proto stubs, and installs the example at `build/install/diavasi-client/bin/diavasi-client`. The compile step uses `--release 17`, so a newer JDK still produces Java 17 class files. Gradle's toolchain error `Cannot find a Java installation ... languageVersion=21` means an older pin; this tree no longer requires JDK 21 to build.

## Library

```java
import dev.diavasi.client.DiavasiClient;

var options = new DiavasiClient.Options();
options.addr = "127.0.0.1:7710";
options.ca = "/tmp/diavasi-sdk/dataplane-ca.crt";
options.token = "sdk-demo";
options.groupId = "demo";
options.consumerId = "java";
options.expectRecords = 8;
DiavasiClient.Report report = DiavasiClient.consume(options);
```

`maxInFlight` defaults to 1. `haltAfterAcks` closes after that many acks and does not send Leave. `expectRecords` sends Leave once that many records are acked.

`ProtocolException` carries codes 1 through 8: bad version, bad state, unknown ack, duplicate ack, group not running, unsupported, internal, heartbeat timeout. `CallException` is a gRPC status. A bad token is `UNAUTHENTICATED` with message `unauthorized`. A group that is not running is protocol code 5.

## Run

Start the server from the [diavasi](https://github.com/diavasis/diavasi) repository:

```bash
cargo build -p diavasi
export PATH="$PWD/target/debug:$PATH"
mkdir -p /tmp/diavasi-sdk
diavasi serve --bind 127.0.0.1:7700 --data-bind 127.0.0.1:7710 \
  --store /tmp/diavasi-sdk/state --token sdk-demo
```

The data-plane CA is `/tmp/diavasi-sdk/dataplane-ca.crt`. In a second terminal, pause, delete, create, and start a synthetic group, then run the example:

```bash
curl -fsS -X POST -H "Authorization: Bearer sdk-demo" \
  http://127.0.0.1:7700/v1/groups/demo/pause || true
curl -fsS -X DELETE -H "Authorization: Bearer sdk-demo" \
  http://127.0.0.1:7700/v1/groups/demo || true
curl -fsS -H "Authorization: Bearer sdk-demo" -H "content-type: application/json" \
  -d '{"group_id":"demo","total_records":8,"payload_size":8,"max_buffer_records":64,"max_buffer_bytes":65536,"batch_max_records":4,"batch_timeout_ms":200,"ordering_contract":"synthetic-u64"}' \
  http://127.0.0.1:7700/v1/groups
curl -fsS -X POST -H "Authorization: Bearer sdk-demo" \
  http://127.0.0.1:7700/v1/groups/demo/start

gradle installDist
./build/install/diavasi-client/bin/diavasi-client \
  --addr 127.0.0.1:7710 --ca /tmp/diavasi-sdk/dataplane-ca.crt \
  --token sdk-demo --group demo --consumer java --total 8
```

Pause, delete, create, and start the group before another run. Delete returns 409 while it is running, and start resumes the cursor. A finished synthetic group leaves the client waiting on heartbeats. Pause returns 409 when the group is already stopped, and delete returns 404 when it is already gone. The create and start that follow are what matter.

Flags: `--addr`, `--ca`, `--token`, `--group`, `--consumer`, `--total`, `--max-in-flight` (default 1), `--halt-after`. The last occurrence of a flag wins. The program prints `record_ids` and `batch_ids`.

## Docker

### Compose

From the server repository, Compose builds the Java image from GitHub `diavasis/diavasi-java` and starts a local `diavasi` server for it:

```bash
docker compose -f clients/docker-compose.yml --profile java up --build --abort-on-container-exit
```

That context is the default branch on GitHub. Uncommitted changes in this working tree are not in the image until they are pushed. `--abort-on-container-exit` stops the stack when the Java consumer finishes.

### Local image against a host server

Build the Dockerfile in this repository, then run it against a server already listening on the host. Reset group `demo` first if it is already consumed:

```bash
docker build -t diavasi-java .
docker run --rm --network host \
  -e DIAVASI_DATA_ADDR=127.0.0.1:7710 \
  -e DIAVASI_CA=/ca/dataplane-ca.crt \
  -e DIAVASI_API_TOKEN=sdk-demo \
  -v /tmp/diavasi-sdk/dataplane-ca.crt:/ca/dataplane-ca.crt:ro \
  diavasi-java
```

`--network host` lets the container reach `127.0.0.1` on the host. The image waits until `DIAVASI_CA` exists, then runs `diavasi-client` for group `demo` as consumer `java` with `--total 8`. Success prints `record_ids` and `batch_ids`. The build image is `gradle:8.10-jdk21`; the jar still targets Java 17.

## Test

`gradle test` always runs the in-process mock suite. Those tests drive `DiavasiClient` against a fake `DataPlane` on an in-process channel: a fresh group returns record ids 1 through 8, `sdk-missing` is protocol error 5, and a bearer token of `bad-token` is `UNAUTHENTICATED`.

The live-server tests return without asserting until `DIAVASI_DATA_ADDR`, `DIAVASI_CA`, and `DIAVASI_API_TOKEN` are set. With those set, one test consumes `DIAVASI_TOTAL` records (default 8) from `DIAVASI_GROUP` (default `sdk`) as consumer `java-test`. That group must be unconsumed. A finished group leaves the test waiting on heartbeats. The other test joins `sdk-missing` and expects protocol error 5.

```bash
curl -fsS -X POST -H "Authorization: Bearer sdk-demo" \
  http://127.0.0.1:7700/v1/groups/sdk/pause || true
curl -fsS -X DELETE -H "Authorization: Bearer sdk-demo" \
  http://127.0.0.1:7700/v1/groups/sdk || true
curl -fsS -H "Authorization: Bearer sdk-demo" -H "content-type: application/json" \
  -d '{"group_id":"sdk","total_records":8,"payload_size":8,"max_buffer_records":64,"max_buffer_bytes":65536,"batch_max_records":4,"batch_timeout_ms":200,"ordering_contract":"synthetic-u64"}' \
  http://127.0.0.1:7700/v1/groups
curl -fsS -X POST -H "Authorization: Bearer sdk-demo" \
  http://127.0.0.1:7700/v1/groups/sdk/start

DIAVASI_DATA_ADDR=127.0.0.1:7710 \
DIAVASI_CA=/tmp/diavasi-sdk/dataplane-ca.crt \
DIAVASI_API_TOKEN=sdk-demo \
DIAVASI_GROUP=sdk \
DIAVASI_TOTAL=8 \
  gradle test
```

CI runs that suite without a server on Temurin 17, 21, 25, and 27, so the env-gated tests are skipped there. The daemon stays on JDK 21; `-PtestJavaVersion` selects the test JVM.
