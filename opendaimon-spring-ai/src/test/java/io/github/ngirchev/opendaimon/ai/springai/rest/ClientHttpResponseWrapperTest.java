package io.github.ngirchev.opendaimon.ai.springai.rest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ClientHttpResponseWrapper.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClientHttpResponseWrapperTest {

    private static final byte[] BODY_BYTES = "{\"result\":\"ok\"}".getBytes(StandardCharsets.UTF_8);

    @Mock
    private ClientHttpResponse delegate;

    private HttpHeaders delegateHeaders;
    private ClientHttpResponseWrapper wrapper;

    @BeforeEach
    void setUp() {
        delegateHeaders = new HttpHeaders();
        when(delegate.getHeaders()).thenReturn(delegateHeaders);
        wrapper = new ClientHttpResponseWrapper(delegate, BODY_BYTES);
    }

    @Test
    void getBody_returnsInputStreamWithWrappedBody() throws IOException {
        InputStream body = wrapper.getBody();

        assertNotNull(body);
        byte[] read = body.readAllBytes();
        assertArrayEquals(BODY_BYTES, read);
    }

    @Test
    void getBody_canBeReadMultipleTimes() throws IOException {
        byte[] first = wrapper.getBody().readAllBytes();
        byte[] second = wrapper.getBody().readAllBytes();

        assertArrayEquals(BODY_BYTES, first);
        assertArrayEquals(BODY_BYTES, second);
    }

    @Test
    void getHeaders_delegatesToDelegate() {
        assertSame(delegateHeaders, wrapper.getHeaders());
        verify(delegate).getHeaders();
    }

    @Test
    void getStatusCode_delegatesToDelegate() throws IOException {
        HttpStatusCode status = HttpStatusCode.valueOf(200);
        when(delegate.getStatusCode()).thenReturn(status);

        assertSame(status, wrapper.getStatusCode());
        verify(delegate).getStatusCode();
    }

    @Test
    void getStatusText_delegatesToDelegate() throws IOException {
        when(delegate.getStatusText()).thenReturn("OK");

        assertEquals("OK", wrapper.getStatusText());
        verify(delegate).getStatusText();
    }

    @Test
    void close_delegatesToDelegate() {
        wrapper.close();

        verify(delegate).close();
    }
}
