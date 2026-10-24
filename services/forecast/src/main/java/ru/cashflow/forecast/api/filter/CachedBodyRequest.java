package ru.cashflow.forecast.api.filter;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

final class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] bytes;

    CachedBodyRequest(HttpServletRequest request, byte[] bytes) {
        super(request);
        this.bytes = bytes;
    }

    @Override
    public ServletInputStream getInputStream() {
        var input = new ByteArrayInputStream(bytes);
        return new ServletInputStream() {
            @Override
            public int read() { return input.read(); }

            @Override
            public int read(byte[] buffer, int offset, int length) { return input.read(buffer, offset, length); }

            @Override
            public boolean isFinished() { return input.available() == 0; }

            @Override
            public boolean isReady() { return true; }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Synchronous request body only");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
