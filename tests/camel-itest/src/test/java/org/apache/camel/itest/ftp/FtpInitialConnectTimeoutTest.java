/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.itest.ftp;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.SocketFactory;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.spi.Registry;
import org.apache.camel.support.SimpleRegistry;
import org.apache.camel.test.junit5.CamelTestSupport;
import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class FtpInitialConnectTimeoutTest extends CamelTestSupport {

    private static final int CONNECT_TIMEOUT = 11223;

    /**
     * Create the answer for the socket factory that causes a SocketTimeoutException to occur in connect.
     */
    private static class SocketAnswer implements Answer<Socket> {

        @Override
        public Socket answer(InvocationOnMock invocation) throws Throwable {
            final Socket socket = spy(new Socket());
            final AtomicBoolean timeout = new AtomicBoolean();

            try {
                doAnswer((Answer<InputStream>) invocation12 -> {
                    final InputStream stream = (InputStream) invocation12.callRealMethod();

                    InputStream inputStream = new InputStream() {
                        @Override
                        public int read() throws IOException {
                            if (timeout.get()) {
                                // emulate a timeout occurring in _getReply()
                                throw new SocketTimeoutException();
                            }
                            return stream.read();
                        }
                    };

                    return inputStream;
                }).when(socket).getInputStream();
            } catch (IOException ignored) {
            }

            try {
                doAnswer((Answer<Object>) invocation1 -> {
                    if ((Integer) invocation1.getArguments()[0] == CONNECT_TIMEOUT) {
                        // setting of connect timeout
                        timeout.set(true);
                    } else {
                        // non-connect timeout
                        timeout.set(false);
                    }
                    return invocation1.callRealMethod();
                }).when(socket).setSoTimeout(anyInt());
            } catch (SocketException e) {
                throw new RuntimeException(e);
            }
            return socket;
        }
    }

    private FakeFtpServer fakeFtpServer;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        fakeFtpServer = new FakeFtpServer();
        fakeFtpServer.setServerControlPort(0);
        fakeFtpServer.start();

        super.setUp();
    }

    @Override
    @AfterEach
    public void tearDown() throws Exception {
        super.tearDown();
        if (fakeFtpServer != null) {
            fakeFtpServer.stop();
        }
    }

    private FTPClient mockedClient() throws IOException {
        FTPClient client = new FTPClient();
        client.setSocketFactory(createSocketFactory());
        return client;
    }

    private SocketFactory createSocketFactory() throws IOException {
        SocketFactory socketFactory = mock(SocketFactory.class);
        when(socketFactory.createSocket()).thenAnswer(new SocketAnswer());
        return socketFactory;
    }

    @Override
    protected Registry createCamelRegistry() throws Exception {
        Registry registry = new SimpleRegistry();
        registry.bind("mocked", mockedClient());
        return registry;
    }

    @Test
    void testReConnect() throws Exception {
        // we should fail, but we are testing that we are not in a deadlock which could potentially happen
        getMockEndpoint("mock:done").expectedMessageCount(0);
        getMockEndpoint("mock:dead").expectedMessageCount(1);

        sendBody("direct:start", "test");

        MockEndpoint.assertIsSatisfied(context);
    }

    @Override
    protected RouteBuilder createRouteBuilder() {
        return new RouteBuilder() {
            @Override
            public void configure() {
                errorHandler(deadLetterChannel("mock:dead"));

                // using soTimeout=0 could potentially cause the ftp producer to dead-lock doing endless reconnection attempts
                // this is a test to ensure we have fixed that
                from("direct:start")
                        .to("ftp://localhost:" + fakeFtpServer.getServerControlPort()
                            + "?ftpClient=#mocked"
                            + "&soTimeout=0&"
                            + "connectTimeout=" + CONNECT_TIMEOUT)
                        .to("mock:done");
            }
        };
    }
}
