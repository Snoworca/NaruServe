package com.snoworca.naruserve.common;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Real HTTP transport support shared by independent acceptance suites. */
public final class RealHttpTestSupport {
    private RealHttpTestSupport() {}
    public static final class Reply {
        public final int status;
        public final byte[] bytes;
        private final Map<String,List<String>> headers;
        Reply(int status, byte[] bytes, Map<String,List<String>> headers) {this.status=status;this.bytes=bytes;this.headers=headers;}
        public String text() {return new String(bytes,StandardCharsets.UTF_8);}
        public String header(String name) {
            for(Map.Entry<String,List<String>> e:headers.entrySet()) if(name.equalsIgnoreCase(e.getKey())) return String.join(", ",e.getValue());
            return null;
        }
    }
    public static Reply send(int port,String method,String path,String body,String... headerPairs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder request = new StringBuilder(method).append(" ").append(path)
                    .append(" HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n");
            for (int i = 0; i < headerPairs.length; i += 2) request.append(headerPairs[i]).append(": ").append(headerPairs[i + 1]).append("\r\n");
            if (body != null) request.append("Content-Length: ").append(payload.length).append("\r\n");
            request.append("\r\n");
            OutputStream output = socket.getOutputStream();
            output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            output.write(payload);
            output.flush();
            InputStream input = socket.getInputStream();
            String statusLine = line(input);
            int status = Integer.parseInt(statusLine.split(" ")[1]);
            Map<String,List<String>> headers = new LinkedHashMap<>();
            String header;
            while (!(header = line(input)).isEmpty()) {
                int colon = header.indexOf(':');
                headers.computeIfAbsent(header.substring(0, colon), ignored -> new ArrayList<>()).add(header.substring(colon + 1).trim());
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Reply metadata = new Reply(status, new byte[0], headers);
            if (!method.equals("HEAD") && status != 204 && status != 304) {
                if ("chunked".equalsIgnoreCase(metadata.header("Transfer-Encoding"))) {
                    while (true) {
                        int count = Integer.parseInt(line(input).split(";", 2)[0].trim(), 16);
                        if (count == 0) break;
                        copy(input, bytes, count);
                        if (!line(input).isEmpty()) throw new IOException("invalid chunk ending");
                    }
                } else if (metadata.header("Content-Length") != null) {
                    copy(input, bytes, Integer.parseInt(metadata.header("Content-Length")));
                } else {
                    byte[] buffer = new byte[1024]; int count;
                    while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                }
            }
            return new Reply(status, bytes.toByteArray(), headers);
        }
    }
    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1) {
            if (next == '\n') return new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1).replace("\r", "");
            bytes.write(next);
        }
        throw new EOFException("missing HTTP line");
    }
    private static void copy(InputStream input, OutputStream output, int count) throws IOException {
        byte[] buffer = new byte[1024];
        while (count > 0) {
            int n = input.read(buffer, 0, Math.min(buffer.length, count));
            if (n < 0) throw new EOFException("incomplete HTTP body");
            output.write(buffer, 0, n); count -= n;
        }
    }
}
