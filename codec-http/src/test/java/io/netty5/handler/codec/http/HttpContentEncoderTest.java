/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty5.handler.codec.http;

import io.netty5.buffer.api.Buffer;
import io.netty5.buffer.api.BufferAllocator;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.embedded.EmbeddedChannel;
import io.netty5.handler.codec.CodecException;
import io.netty5.handler.codec.DecoderResult;
import io.netty5.handler.codec.EncoderException;
import io.netty5.handler.codec.compression.CompressionException;
import io.netty5.handler.codec.compression.Compressor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.netty5.buffer.api.DefaultBufferAllocators.preferredAllocator;
import static io.netty5.handler.codec.http.HttpMethod.GET;
import static io.netty5.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpContentEncoderTest {

    private static final class TestEncoder extends HttpContentEncoder {
        @Override
        protected Result beginEncode(HttpResponse httpResponse, String acceptEncoding) {
            return new Result("test", new Compressor() {
                private boolean finished;
                @Override
                public Buffer compress(Buffer input, BufferAllocator allocator) throws CompressionException {
                    Buffer out = allocator.copyOf(String.valueOf(input.readableBytes()), US_ASCII);
                    input.skipReadableBytes(input.readableBytes());
                    return out;
                }

                @Override
                public Buffer finish(BufferAllocator allocator) {
                    finished = true;
                    return allocator.allocate(0);
                }

                @Override
                public boolean isFinished() {
                    return finished;
                }

                @Override
                public void close() {
                    finished = true;
                }

                @Override
                public boolean isClosed() {
                    return finished;
                }
            });
        }
    }

    @Test
    public void testSplitContent() {
        EmbeddedChannel ch = new EmbeddedChannel(new TestEncoder());
        ch.writeInbound(new DefaultFullHttpRequest(HTTP_1_1, GET, "/", preferredAllocator().allocate(0)));

        ch.writeOutbound(new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK));
        ch.writeOutbound(new DefaultHttpContent(preferredAllocator().allocate(3).writeBytes(new byte[3])));
        ch.writeOutbound(new DefaultHttpContent(preferredAllocator().allocate(2).writeBytes(new byte[2])));
        ch.writeOutbound(new DefaultLastHttpContent(preferredAllocator().copyOf(new byte[1])));

        assertEncodedResponse(ch);

        HttpContent<?> chunk;
        chunk = ch.readOutbound();
        assertThat(chunk.payload().toString(US_ASCII)).isEqualTo("3");
        chunk.close();

        chunk = ch.readOutbound();
        assertThat(chunk.payload().toString(US_ASCII)).isEqualTo("2");
        chunk.close();

        chunk = ch.readOutbound();
        assertThat(chunk.payload().toString(US_ASCII)).isEqualTo("1");
        chunk.close();

        chunk = ch.readOutbound();
        assertThat(chunk.payload().readableBytes()).isZero();
        assertThat(chunk).isInstanceOf(LastHttpContent.class);
        chunk.close();

        assertThat((Object) ch.readOutbound()).isNull();
    }

    @Test
    public void testChunkedContent() {
        EmbeddedChannel ch = new EmbeddedChannel(new TestEncoder());
        ch.writeInbound(new DefaultFullHttpRequest(HTTP_1_1, GET, "/", preferredAllocator().allocate(0)));

        HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK);
        res.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        ch.writeOutbound(res);

        assertEncodedResponse(ch);

        ch.writeOutbound(new DefaultHttpContent(preferredAllocator().allocate(3).writeBytes(new byte[3])));
        ch.writeOutbound(new DefaultHttpContent(preferredAllocator().allocate(2).writeBytes(new byte[2])));
        ch.writeOutbound(new DefaultLastHttpContent(preferredAllocator().copyOf(new byte[1])));

        HttpContent<?> chunk;
        chunk = ch.readOutbound();
        assertThat(chunk.payload().toString(US_ASCII)).isEqualTo("3");
        chunk.close();

        chunk = ch.readOutbound();
        assertThat(chunk.payload().toString(US_ASCII)).isEqualTo("2");
        chunk.close();

        chunk = ch.readOutbound();
        assertThat(chunk.payload().toString(US_ASCII)).isEqualTo("1");
        assertThat(chunk).isInstanceOf(HttpContent.class);
        chunk.close();

        chunk = ch.readOutbound();
        assertThat(chunk.payload().readableBytes()).isZero();
        assertThat(chunk).isInstanceOf(LastHttpContent.class);
        chunk.close();

        assertThat((Object) ch.readOutbound()).isNull();
    }

    @Test
    public void testChunkedContentWithTrailingHeader() {
        EmbeddedChannel ch = new EmbeddedChannel(new TestEncoder());
        ch.writeInbound(new DefaultFullHttpRequest(HTTP_1_1, GET, "/", preferredAllocator().allocate(0)));

        HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK);
        res.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        ch.writeOutbound(res);

        assertEncodedResponse(ch);

        ch.writeOutbound(new DefaultHttpContent(preferredAllocator().allocate(3).writeBytes(new byte[3])));
        ch.writeOutbound(new DefaultHttpContent(preferredAllocator().allocate(2).writeBytes(new byte[2])));
        LastHttpContent<?> content = new DefaultLastHttpContent(preferredAllocator().copyOf(new byte[1]));
        content.trailingHeaders().set("X-Test", "Netty");
        ch.writeOutbound(content);

        HttpContent<?> chunk;
        chunk = ch.readOutbound();
        assertThat(chunk.payload().toString(US_ASCII)).isEqualTo("3");
        chunk.close();

        chunk = ch.readOutbound();
        assertThat(chunk.payload().toString(US_ASCII)).isEqualTo("2");
        chunk.close();

        chunk = ch.readOutbound();
        assertThat(chunk.payload().toString(US_ASCII)).isEqualTo("1");
        assertThat(chunk).isInstanceOf(HttpContent.class);
        chunk.close();

        chunk = ch.readOutbound();
        assertThat(chunk.payload().readableBytes()).isZero();
        assertThat(chunk).isInstanceOf(LastHttpContent.class);
        assertEquals("Netty", ((LastHttpContent<?>) chunk).trailingHeaders().get("X-Test"));
        assertEquals(DecoderResult.success(), res.decoderResult());
        chunk.close();

        assertThat((Object) ch.readOutbound()).isNull();
    }

    @Test
    public void testFullContentWithContentLength() {
        EmbeddedChannel ch = new EmbeddedChannel(new TestEncoder());
        ch.writeInbound(new DefaultFullHttpRequest(HTTP_1_1, GET, "/", preferredAllocator().allocate(0)));

        FullHttpResponse fullRes = new DefaultFullHttpResponse(
                HTTP_1_1, HttpResponseStatus.OK, preferredAllocator().copyOf(new byte[42]));
        fullRes.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(42));
        ch.writeOutbound(fullRes);

        HttpResponse res = ch.readOutbound();
        assertThat(res).isNotInstanceOf(HttpContent.class);
        assertThat(res.headers().get(HttpHeaderNames.TRANSFER_ENCODING)).isNull();
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("2");
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("test");

        try (HttpContent<?> c = ch.readOutbound()) {
            assertThat(c.payload().readableBytes()).isEqualTo(2);
            assertThat(c.payload().toString(US_ASCII)).isEqualTo("42");
        }

        try (LastHttpContent<?> last = ch.readOutbound()) {
            assertThat(last.payload().readableBytes()).isZero();
        }

        assertThat((Object) ch.readOutbound()).isNull();
    }

    @Test
    public void testFullContent() {
        EmbeddedChannel ch = new EmbeddedChannel(new TestEncoder());
        ch.writeInbound(new DefaultFullHttpRequest(HTTP_1_1, GET, "/", preferredAllocator().allocate(0)));

        FullHttpResponse res = new DefaultFullHttpResponse(
                HTTP_1_1, HttpResponseStatus.OK, preferredAllocator().copyOf(new byte[42]));
        ch.writeOutbound(res);

        assertEncodedResponse(ch);
        try (HttpContent<?> c = ch.readOutbound()) {
            assertThat(c.payload().readableBytes()).isEqualTo(2);
            assertThat(c.payload().toString(US_ASCII)).isEqualTo("42");
        }

        try (LastHttpContent<?> last = ch.readOutbound()) {
            assertThat(last.payload().readableBytes()).isZero();
        }

        assertThat((Object) ch.readOutbound()).isNull();
    }

    /**
     * If the length of the content is unknown, {@link HttpContentEncoder} should not skip encoding the content
     * even if the actual length is turned out to be 0.
     */
    @Test
    public void testEmptySplitContent() {
        EmbeddedChannel ch = new EmbeddedChannel(new TestEncoder());
        ch.writeInbound(new DefaultFullHttpRequest(HTTP_1_1, GET, "/", preferredAllocator().allocate(0)));

        ch.writeOutbound(new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK));
        assertEncodedResponse(ch);

        ch.writeOutbound(new EmptyLastHttpContent(preferredAllocator()));
        HttpContent<?> chunk = ch.readOutbound();
        assertThat(chunk.payload().toString(US_ASCII)).isEqualTo("0");
        assertThat(chunk).isInstanceOf(HttpContent.class);
        chunk.close();

        chunk = ch.readOutbound();
        assertThat(chunk.payload().readableBytes()).isZero();
        assertThat(chunk).isInstanceOf(LastHttpContent.class);
        chunk.close();

        assertThat((Object) ch.readOutbound()).isNull();
    }

    /**
     * If the length of the content is 0 for sure, {@link HttpContentEncoder} should skip encoding.
     */
    @Test
    public void testEmptyFullContent() {
        EmbeddedChannel ch = new EmbeddedChannel(new TestEncoder());
        ch.writeInbound(new DefaultFullHttpRequest(HTTP_1_1, GET, "/", preferredAllocator().allocate(0)));

        FullHttpResponse res = new DefaultFullHttpResponse(
                HTTP_1_1, HttpResponseStatus.OK, preferredAllocator().allocate(0));
        ch.writeOutbound(res);

        Object o = ch.readOutbound();
        assertThat(o).isInstanceOf(FullHttpResponse.class);

        res = (FullHttpResponse) o;
        assertThat(res.headers().get(HttpHeaderNames.TRANSFER_ENCODING)).isNull();

        // Content encoding shouldn't be modified.
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
        assertThat(res.payload().readableBytes()).isZero();
        assertThat(res.payload().toString(US_ASCII)).isEmpty();
        res.close();

        assertThat((Object) ch.readOutbound()).isNull();
    }

    @Test
    public void testEmptyFullContentWithTrailer() {
        EmbeddedChannel ch = new EmbeddedChannel(new TestEncoder());
        ch.writeInbound(new DefaultFullHttpRequest(HTTP_1_1, GET, "/", preferredAllocator().allocate(0)));

        FullHttpResponse res = new DefaultFullHttpResponse(
                HTTP_1_1, HttpResponseStatus.OK, preferredAllocator().allocate(0));
        res.trailingHeaders().set("X-Test", "Netty");
        ch.writeOutbound(res);

        Object o = ch.readOutbound();
        assertThat(o).isInstanceOf(FullHttpResponse.class);

        res = (FullHttpResponse) o;
        assertThat(res.headers().get(HttpHeaderNames.TRANSFER_ENCODING)).isNull();

        // Content encoding shouldn't be modified.
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
        assertThat(res.payload().readableBytes()).isZero();
        assertThat(res.payload().toString(US_ASCII)).isEmpty();
        assertEquals("Netty", res.trailingHeaders().get("X-Test"));
        assertEquals(DecoderResult.success(), res.decoderResult());
        assertThat((Object) ch.readOutbound()).isNull();
    }

    @Test
    public void testEmptyHeadResponse() {
        EmbeddedChannel ch = new EmbeddedChannel(new TestEncoder());
        HttpRequest req = new DefaultFullHttpRequest(HTTP_1_1, HttpMethod.HEAD, "/", preferredAllocator().allocate(0));
        ch.writeInbound(req);

        HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK);
        res.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        ch.writeOutbound(res);
        ch.writeOutbound(new EmptyLastHttpContent(preferredAllocator()));

        assertEmptyResponse(ch);
    }

    @Test
    public void testHttp304Response() {
        EmbeddedChannel ch = new EmbeddedChannel(new TestEncoder());
        HttpRequest req = new DefaultFullHttpRequest(HTTP_1_1, GET, "/", preferredAllocator().allocate(0));
        req.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
        ch.writeInbound(req);

        HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.NOT_MODIFIED);
        res.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        ch.writeOutbound(res);
        ch.writeOutbound(new EmptyLastHttpContent(preferredAllocator()));

        assertEmptyResponse(ch);
    }

    @Test
    public void testConnect200Response() {
        EmbeddedChannel ch = new EmbeddedChannel(new TestEncoder());
        HttpRequest req = new DefaultFullHttpRequest(HTTP_1_1, HttpMethod.CONNECT, "google.com:80",
                                                     preferredAllocator().allocate(0));
        ch.writeInbound(req);

        HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK);
        res.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        ch.writeOutbound(res);
        ch.writeOutbound(new EmptyLastHttpContent(preferredAllocator()));

        assertEmptyResponse(ch);
    }

    @Test
    public void testConnectFailureResponse() {
        String content = "Not allowed by configuration";

        EmbeddedChannel ch = new EmbeddedChannel(new TestEncoder());
        HttpRequest req = new DefaultFullHttpRequest(HTTP_1_1, HttpMethod.CONNECT, "google.com:80",
                                                     preferredAllocator().allocate(0));
        ch.writeInbound(req);

        HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.METHOD_NOT_ALLOWED);
        res.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        ch.writeOutbound(res);
        ch.writeOutbound(new DefaultHttpContent(preferredAllocator().copyOf(content, StandardCharsets.UTF_8)));
        ch.writeOutbound(new EmptyLastHttpContent(preferredAllocator()));

        assertEncodedResponse(ch);
        Object o = ch.readOutbound();
        assertThat(o).isInstanceOf(HttpContent.class);
        HttpContent<?> chunk = (HttpContent<?>) o;
        assertThat(chunk.payload().toString(US_ASCII)).isEqualTo("28");
        chunk.close();

        chunk = ch.readOutbound();
        assertThat(chunk.payload().readableBytes()).isGreaterThan(0);
        assertThat(chunk.payload().toString(US_ASCII)).isEqualTo("0");
        chunk.close();

        chunk = ch.readOutbound();
        assertThat(chunk).isInstanceOf(LastHttpContent.class);
        chunk.close();
        assertThat((Object) ch.readOutbound()).isNull();
    }

    @Test
    public void testHttp1_0() {
        EmbeddedChannel ch = new EmbeddedChannel(new TestEncoder());
        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_0, GET, "/", preferredAllocator().allocate(0));
        assertTrue(ch.writeInbound(req));

        HttpResponse res = new DefaultHttpResponse(HttpVersion.HTTP_1_0, HttpResponseStatus.OK);
        res.headers().set(HttpHeaderNames.CONTENT_LENGTH, HttpHeaderValues.ZERO);
        assertTrue(ch.writeOutbound(res));
        final EmptyLastHttpContent lastContent = new EmptyLastHttpContent(preferredAllocator());
        assertTrue(ch.writeOutbound(lastContent));
        assertTrue(ch.finish());

        FullHttpRequest request = ch.readInbound();
        assertTrue(request.isAccessible());
        request.close();
        assertNull(ch.readInbound());

        HttpResponse response = ch.readOutbound();
        assertEquals(res, response);

        LastHttpContent<?> content = ch.readOutbound();
        assertEquals(lastContent, content);
        content.close();
        assertNull(ch.readOutbound());
    }

    @Test
    public void testCleanupThrows() {
        HttpContentEncoder encoder = new HttpContentEncoder() {
            @Override
            protected Result beginEncode(HttpResponse httpResponse, String acceptEncoding) {
                return new Result("myencoding", new Compressor() {

                    @Override
                    public Buffer compress(Buffer input, BufferAllocator allocator) throws CompressionException {
                        return input.copy();
                    }

                    @Override
                    public Buffer finish(BufferAllocator allocator) {
                        return allocator.allocate(0);
                    }

                    @Override
                    public boolean isFinished() {
                        return false;
                    }

                    @Override
                    public boolean isClosed() {
                        return false;
                    }

                    @Override
                    public void close() {
                        throw new EncoderException();
                    }
                });
            }
        };

        final AtomicBoolean channelInactiveCalled = new AtomicBoolean();
        EmbeddedChannel channel = new EmbeddedChannel(encoder, new ChannelHandler() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                assertTrue(channelInactiveCalled.compareAndSet(false, true));
                ctx.fireChannelInactive();
            }
        });
        assertTrue(channel.writeInbound(new DefaultFullHttpRequest(
                HTTP_1_1, GET, "/", preferredAllocator().allocate(0))));
        assertTrue(channel.writeOutbound(new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK)));
        HttpContent<?> content = new DefaultHttpContent(preferredAllocator().copyOf(new byte[10]));
        assertTrue(channel.writeOutbound(content));
        assertFalse(content.isAccessible());
        assertThrows(CodecException.class, channel::finishAndReleaseAll);

        assertTrue(channelInactiveCalled.get());
        assertFalse(content.isAccessible());
    }

    private static void assertEmptyResponse(EmbeddedChannel ch) {
        Object o = ch.readOutbound();
        assertThat(o).isInstanceOf(HttpResponse.class);

        HttpResponse res = (HttpResponse) o;
        assertThat(res).isNotInstanceOf(HttpContent.class);
        assertThat(res.headers().get(HttpHeaderNames.TRANSFER_ENCODING)).isEqualToIgnoringCase("chunked");
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isNull();

        try (HttpContent<?> chunk = ch.readOutbound()) {
            assertThat(chunk).isInstanceOf(LastHttpContent.class);
        }
        assertThat((Object) ch.readOutbound()).isNull();
    }

    private static void assertEncodedResponse(EmbeddedChannel ch) {
        Object o = ch.readOutbound();
        assertThat(o).isInstanceOf(HttpResponse.class);

        HttpResponse res = (HttpResponse) o;
        assertThat(res).isNotInstanceOf(HttpContent.class);
        assertThat(res.headers().get(HttpHeaderNames.TRANSFER_ENCODING)).isEqualToIgnoringCase("chunked");
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isNull();
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualToIgnoringCase("test");
    }
}
