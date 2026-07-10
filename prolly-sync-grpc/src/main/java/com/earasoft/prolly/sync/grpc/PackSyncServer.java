/*
 * Copyright 2026 Earasoft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.earasoft.prolly.sync.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Lifecycle wrapper hosting a {@link PackSyncService} on a port.
 *
 * @apiNote No authentication ships here — pass {@link ServerInterceptor}s (an auth check, logging,
 *     metrics) at {@link #start}; authentication policy belongs to the host product. Port {@code 0}
 *     binds an ephemeral port; read it back with {@link #port()}.
 * @implNote Netty via {@code grpc-netty-shaded} (the transport the classpath carries); {@link
 *     #close()} initiates a graceful shutdown and waits up to 10 seconds before forcing.
 */
public final class PackSyncServer implements AutoCloseable {

    private final Server server;

    private PackSyncServer(Server server) {
        this.server = server;
    }

    /** Host {@code resolver}'s repos on {@code port} with {@code limits} at the boundary. */
    public static PackSyncServer start(
            int port,
            RepoResolver resolver,
            PackLimits limits,
            List<ServerInterceptor> interceptors)
            throws IOException {
        PackSyncService service = new PackSyncService(resolver, limits);
        Server server =
                ServerBuilder.forPort(port)
                        .addService(ServerInterceptors.intercept(service, interceptors))
                        .build()
                        .start();
        return new PackSyncServer(server);
    }

    /** The bound port (useful with an ephemeral {@code port 0}). */
    public int port() {
        return server.getPort();
    }

    /** Block until the server terminates (for standalone hosts). */
    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    @Override
    public void close() {
        server.shutdown();
        try {
            if (!server.awaitTermination(10, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
