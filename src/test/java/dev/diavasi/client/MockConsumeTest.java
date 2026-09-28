package dev.diavasi.client;

import com.google.protobuf.ByteString;
import diavasi.data.v1.Data;
import diavasi.data.v1.DataPlaneGrpc;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consume session against an in-process data-plane stand-in.
 *
 * <p>A group named {@code sdk-missing} is protocol error 5. Any other group
 * yields record ids {@code 1} through {@code 8} in one batch. A bearer token
 * of {@code bad-token} is {@code UNAUTHENTICATED}.
 */
class MockConsumeTest {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Context.Key<String> AUTH_CTX = Context.key("authorization");

    private Server server;
    private ManagedChannel channel;
    private DataPlaneGrpc.DataPlaneStub stub;

    @BeforeEach
    void startFake() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(ServerInterceptors.intercept(new FakeDataPlane(), new AuthCapture()))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = DataPlaneGrpc.newStub(channel);
    }

    @AfterEach
    void stopFake() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    void freshGroupReturnsIdsAndAcks() throws Exception {
        DiavasiClient.Options options = new DiavasiClient.Options();
        options.groupId = "sdk";
        options.consumerId = "java-test";
        options.expectRecords = 8;
        DiavasiClient.Report report = DiavasiClient.consume(options, stub);
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), report.recordIds);
        assertEquals(List.of(1L), report.batchIds);
    }

    @Test
    void missingGroupIsProtocolError5() {
        DiavasiClient.Options options = new DiavasiClient.Options();
        options.groupId = "sdk-missing";
        options.consumerId = "java-missing";
        options.expectRecords = 1;
        DiavasiClient.ProtocolException error = assertThrows(
                DiavasiClient.ProtocolException.class,
                () -> DiavasiClient.consume(options, stub));
        assertEquals(5, error.code);
        assertTrue(error.getMessage().contains("not running"));
    }

    @Test
    void badTokenIsUnauthenticated() {
        DiavasiClient.Options options = new DiavasiClient.Options();
        options.groupId = "sdk";
        options.consumerId = "java-bad";
        options.expectRecords = 1;
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer bad-token");
        DataPlaneGrpc.DataPlaneStub authed =
                stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        DiavasiClient.CallException error = assertThrows(
                DiavasiClient.CallException.class,
                () -> DiavasiClient.consume(options, authed));
        assertEquals("UNAUTHENTICATED", error.status);
        assertTrue(error.getMessage().contains("unauthorized"));
    }

    @Test
    void protocolExceptionMessageIncludesCode() {
        DiavasiClient.ProtocolException error = new DiavasiClient.ProtocolException(5, "not running");
        assertEquals(5, error.code);
        assertEquals("protocol error 5: not running", error.getMessage());
    }

    private static final class AuthCapture implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            Context context = Context.current().withValue(AUTH_CTX, headers.get(AUTHORIZATION));
            return Contexts.interceptCall(context, call, headers, next);
        }
    }

    private static final class FakeDataPlane extends DataPlaneGrpc.DataPlaneImplBase {
        @Override
        public StreamObserver<Data.Envelope> consume(StreamObserver<Data.Envelope> responses) {
            return new StreamObserver<>() {
                @Override
                public void onNext(Data.Envelope value) {
                    switch (value.getBodyCase()) {
                        case HELLO -> responses.onNext(Data.Envelope.newBuilder()
                                .setVersion(1)
                                .setHelloAck(Data.HelloAck.newBuilder().setProtocolVersion(1))
                                .build());
                        case JOIN_GROUP -> {
                            if ("Bearer bad-token".equals(AUTH_CTX.get())) {
                                responses.onError(Status.UNAUTHENTICATED
                                        .withDescription("unauthorized")
                                        .asRuntimeException());
                                return;
                            }
                            if ("sdk-missing".equals(value.getJoinGroup().getGroupId())) {
                                responses.onNext(Data.Envelope.newBuilder()
                                        .setVersion(1)
                                        .setError(Data.ErrorMessage.newBuilder()
                                                .setCode(5)
                                                .setMessage("not running"))
                                        .build());
                                responses.onCompleted();
                                return;
                            }
                            responses.onNext(Data.Envelope.newBuilder()
                                    .setVersion(1)
                                    .setJoined(Data.Joined.newBuilder())
                                    .build());
                        }
                        case FLOW_CONTROL -> {
                            Data.RecordBatch.Builder batch = Data.RecordBatch.newBuilder().setBatchId(1);
                            for (long id = 1; id <= 8; id++) {
                                batch.addRecords(Data.Record.newBuilder()
                                        .setRecordId(id)
                                        .setPayload(ByteString.copyFromUtf8("abcdefgh")));
                            }
                            responses.onNext(Data.Envelope.newBuilder()
                                    .setVersion(1)
                                    .setRecordBatch(batch)
                                    .build());
                        }
                        case HEARTBEAT -> responses.onNext(Data.Envelope.newBuilder()
                                .setVersion(1)
                                .setHeartbeat(Data.Heartbeat.newBuilder())
                                .build());
                        case LEAVE -> responses.onCompleted();
                        default -> {
                        }
                    }
                }

                @Override
                public void onError(Throwable t) {
                    responses.onError(t);
                }

                @Override
                public void onCompleted() {
                    responses.onCompleted();
                }
            };
        }
    }
}
