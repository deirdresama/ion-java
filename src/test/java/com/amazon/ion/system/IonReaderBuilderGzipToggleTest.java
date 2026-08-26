// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazon.ion.system;

import com.amazon.ion.IonException;
import com.amazon.ion.IonReader;
import com.amazon.ion.IonSystem;
import com.amazon.ion.IonType;
import com.amazon.ion.IonValue;
import com.amazon.ion.util.GzipStreamInterceptor;
import com.amazon.ion.util.InputStreamInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the GZIP decompression toggle API on {@link IonReaderBuilder}.
 */
class IonReaderBuilderGzipToggleTest {

    @Test
    void defaultBuilderHasGzipEnabled() {
        IonReaderBuilder builder = IonReaderBuilder.standard();
        assertTrue(builder.isGzipDecompressionEnabled());
    }

    @Test
    void getInputStreamInterceptorsIncludesGzipWhenEnabled() {
        IonReaderBuilder builder = IonReaderBuilder.standard();
        List<InputStreamInterceptor> interceptors = builder.getInputStreamInterceptors();
        boolean hasGzip = interceptors.stream().anyMatch(i -> i instanceof GzipStreamInterceptor);
        assertTrue(hasGzip, "Expected GzipStreamInterceptor in interceptor list when GZIP is enabled");
    }

    @Test
    void getInputStreamInterceptorsExcludesGzipWhenDisabled() {
        IonReaderBuilder builder = IonReaderBuilder.standard()
                .withGzipDecompressionEnabled(false);
        List<InputStreamInterceptor> interceptors = builder.getInputStreamInterceptors();
        boolean hasGzip = interceptors.stream().anyMatch(i -> i instanceof GzipStreamInterceptor);
        assertFalse(hasGzip, "Expected no GzipStreamInterceptor in interceptor list when GZIP is disabled");
    }

    @Test
    void customInterceptorsPreservedWhenGzipDisabled() {
        InputStreamInterceptor customInterceptor = new InputStreamInterceptor() {
            @Override
            public String formatName() {
                return "custom";
            }

            @Override
            public int numberOfBytesNeededToDetermineMatch() {
                return 2;
            }

            @Override
            public boolean isMatch(byte[] candidate, int offset, int length) {
                return false;
            }

            @Override
            public InputStream newInputStream(InputStream interceptedStream) throws IOException {
                return interceptedStream;
            }
        };

        IonReaderBuilder builder = IonReaderBuilder.standard()
                .addInputStreamInterceptor(customInterceptor)
                .withGzipDecompressionEnabled(false);

        List<InputStreamInterceptor> interceptors = builder.getInputStreamInterceptors();

        // Custom interceptor should still be present
        assertTrue(interceptors.contains(customInterceptor),
                "Custom interceptor should be preserved when GZIP is disabled");

        // GZIP interceptor should be excluded
        boolean hasGzip = interceptors.stream().anyMatch(i -> i instanceof GzipStreamInterceptor);
        assertFalse(hasGzip, "GzipStreamInterceptor should be excluded when GZIP is disabled");
    }

    @Test
    void customInterceptorsPreservedWhenGzipEnabled() {
        InputStreamInterceptor customInterceptor = new InputStreamInterceptor() {
            @Override
            public String formatName() {
                return "custom";
            }

            @Override
            public int numberOfBytesNeededToDetermineMatch() {
                return 2;
            }

            @Override
            public boolean isMatch(byte[] candidate, int offset, int length) {
                return false;
            }

            @Override
            public InputStream newInputStream(InputStream interceptedStream) throws IOException {
                return interceptedStream;
            }
        };

        IonReaderBuilder builder = IonReaderBuilder.standard()
                .addInputStreamInterceptor(customInterceptor)
                .withGzipDecompressionEnabled(true);

        List<InputStreamInterceptor> interceptors = builder.getInputStreamInterceptors();

        // Custom interceptor should be present
        assertTrue(interceptors.contains(customInterceptor),
                "Custom interceptor should be preserved when GZIP is enabled");

        // GZIP interceptor should also be present
        boolean hasGzip = interceptors.stream().anyMatch(i -> i instanceof GzipStreamInterceptor);
        assertTrue(hasGzip, "GzipStreamInterceptor should be present when GZIP is enabled");
    }

    @Test
    void withGzipDecompressionEnabledOnImmutableBuilderReturnsMutableCopy() {
        IonReaderBuilder immutableBuilder = IonReaderBuilder.standard().immutable();
        IonReaderBuilder result = immutableBuilder.withGzipDecompressionEnabled(false);

        // The result should be a different instance (mutable copy)
        assertNotSame(immutableBuilder, result);
        // The original should still have GZIP enabled
        assertTrue(immutableBuilder.isGzipDecompressionEnabled());
        // The new builder should have GZIP disabled
        assertFalse(result.isGzipDecompressionEnabled());
    }

    @Test
    void copyConstructorPreservesGzipFlag() {
        IonReaderBuilder original = IonReaderBuilder.standard()
                .withGzipDecompressionEnabled(false);
        IonReaderBuilder copy = original.copy();

        assertFalse(copy.isGzipDecompressionEnabled(),
                "Copy should preserve the gzipDecompressionEnabled flag");
    }

    @Test
    void setGzipDecompressionEnabledOnMutableBuilder() {
        IonReaderBuilder builder = IonReaderBuilder.standard();
        builder.setGzipDecompressionEnabled(false);
        assertFalse(builder.isGzipDecompressionEnabled());
    }

    @Test
    void setGzipDecompressionEnabledOnImmutableBuilderThrows() {
        IonReaderBuilder immutableBuilder = IonReaderBuilder.standard().immutable();
        assertThrows(UnsupportedOperationException.class, () -> {
            immutableBuilder.setGzipDecompressionEnabled(false);
        });
    }

    //=========================================================================
    // End-to-end tests: the toggle must govern actual reader behavior, not
    // merely the contents of getInputStreamInterceptors().
    //=========================================================================

    private static byte[] gzip(byte[] uncompressed) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(uncompressed);
        }
        return out.toByteArray();
    }

    private static byte[] binaryIon(String ionText) {
        return IonSystemBuilder.standard().build().getLoader().load(ionText).getBytes();
    }

    private static int readAllValues(IonReader reader) {
        int count = 0;
        while (reader.next() != null) {
            count++;
            if (reader.getType() == IonType.STRING) {
                reader.stringValue(); // force materialization
            }
        }
        return count;
    }

    /** One documented way of starting a parse. Must fully consume the payload. */
    @FunctionalInterface
    private interface ParseEntryPoint {
        /**
         * @return the number of top-level values parsed.
         * @throws com.amazon.ion.IonException if the payload cannot be read.
         */
        int parse(IonReaderBuilder builder, IonSystem system, byte[] payload, Path tempDir)
            throws Exception;
    }

    /**
     * Wraps the payload in a byte array with 3 bytes of leading padding, to exercise the
     * offset/length overloads over a non-zero offset.
     */
    private static byte[] pad(byte[] payload) {
        byte[] padded = new byte[payload.length + 3];
        System.arraycopy(payload, 0, padded, 3, payload.length);
        return padded;
    }

    private static int consume(Iterator<IonValue> iterator) {
        int count = 0;
        while (iterator.hasNext()) {
            iterator.next();
            count++;
        }
        return count;
    }

    private static Stream<Arguments> entryPoints() {
        return Stream.of(
            Arguments.of("IonReaderBuilder.build(byte[])",
                (ParseEntryPoint) (b, s, p, dir) -> {
                    try (IonReader r = b.build(p)) {
                        return readAllValues(r);
                    }
                }),
            Arguments.of("IonReaderBuilder.build(byte[], offset, length)",
                (ParseEntryPoint) (b, s, p, dir) -> {
                    try (IonReader r = b.build(pad(p), 3, p.length)) {
                        return readAllValues(r);
                    }
                }),
            Arguments.of("IonReaderBuilder.build(InputStream)",
                (ParseEntryPoint) (b, s, p, dir) -> {
                    try (IonReader r = b.build(new ByteArrayInputStream(p))) {
                        return readAllValues(r);
                    }
                }),
            Arguments.of("IonSystem.newReader(byte[])",
                (ParseEntryPoint) (b, s, p, dir) -> {
                    try (IonReader r = s.newReader(p)) {
                        return readAllValues(r);
                    }
                }),
            Arguments.of("IonSystem.newReader(byte[], offset, length)",
                (ParseEntryPoint) (b, s, p, dir) -> {
                    try (IonReader r = s.newReader(pad(p), 3, p.length)) {
                        return readAllValues(r);
                    }
                }),
            Arguments.of("IonSystem.newReader(InputStream)",
                (ParseEntryPoint) (b, s, p, dir) -> {
                    try (IonReader r = s.newReader(new ByteArrayInputStream(p))) {
                        return readAllValues(r);
                    }
                }),
            Arguments.of("IonSystem.singleValue(byte[])",
                (ParseEntryPoint) (b, s, p, dir) -> {
                    assertNotNull(s.singleValue(p));
                    return 1;
                }),
            Arguments.of("IonSystem.singleValue(byte[], offset, length)",
                (ParseEntryPoint) (b, s, p, dir) -> {
                    assertNotNull(s.singleValue(pad(p), 3, p.length));
                    return 1;
                }),
            Arguments.of("IonSystem.iterate(byte[])",
                (ParseEntryPoint) (b, s, p, dir) -> consume(s.iterate(p))),
            Arguments.of("IonSystem.iterate(InputStream)",
                (ParseEntryPoint) (b, s, p, dir) -> consume(s.iterate(new ByteArrayInputStream(p)))),
            Arguments.of("IonLoader.load(byte[])",
                (ParseEntryPoint) (b, s, p, dir) -> s.getLoader().load(p).size()),
            Arguments.of("IonLoader.load(InputStream)",
                (ParseEntryPoint) (b, s, p, dir) ->
                    s.getLoader().load(new ByteArrayInputStream(p)).size()),
            Arguments.of("IonLoader.load(File)",
                (ParseEntryPoint) (b, s, p, dir) -> {
                    Path file = dir.resolve("payload.ion.gz");
                    Files.write(file, p);
                    return s.getLoader().load(file.toFile()).size();
                })
        );
    }

    /** The single value every payload encodes, so each entry point has one thing to assert. */
    private static final String SINGLE_VALUE = "{a: 1, b: \"hello\"}";

    private static byte[] gzippedTextPayload() throws IOException {
        return gzip(SINGLE_VALUE.getBytes("UTF-8"));
    }

    private static byte[] gzippedBinaryPayload() throws IOException {
        return gzip(binaryIon(SINGLE_VALUE));
    }

    private static IonSystem systemFor(IonReaderBuilder builder) {
        return IonSystemBuilder.standard().withReaderBuilder(builder).build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entryPoints")
    void gzippedTextIsParsedWhenGzipEnabled(String name, ParseEntryPoint entryPoint,
                                            @TempDir Path tempDir) throws Exception {
        IonReaderBuilder builder = IonReaderBuilder.standard();
        assertEquals(1, entryPoint.parse(builder, systemFor(builder), gzippedTextPayload(), tempDir),
            name + " should parse GZIP-compressed text Ion when decompression is enabled");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entryPoints")
    void gzippedBinaryIsParsedWhenGzipEnabled(String name, ParseEntryPoint entryPoint,
                                              @TempDir Path tempDir) throws Exception {
        IonReaderBuilder builder = IonReaderBuilder.standard();
        assertEquals(1, entryPoint.parse(builder, systemFor(builder), gzippedBinaryPayload(), tempDir),
            name + " should parse GZIP-compressed binary Ion when decompression is enabled");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entryPoints")
    void gzippedTextIsRejectedWhenGzipDisabled(String name, ParseEntryPoint entryPoint,
                                               @TempDir Path tempDir) throws Exception {
        IonReaderBuilder builder = IonReaderBuilder.standard().withGzipDecompressionEnabled(false);
        IonSystem system = systemFor(builder);
        byte[] payload = gzippedTextPayload();
        assertThrows(IonException.class,
            () -> entryPoint.parse(builder, system, payload, tempDir),
            name + " should raise IonException rather than decompress when the toggle is off");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entryPoints")
    void gzippedBinaryIsRejectedWhenGzipDisabled(String name, ParseEntryPoint entryPoint,
                                                 @TempDir Path tempDir) throws Exception {
        IonReaderBuilder builder = IonReaderBuilder.standard().withGzipDecompressionEnabled(false);
        IonSystem system = systemFor(builder);
        byte[] payload = gzippedBinaryPayload();
        assertThrows(IonException.class,
            () -> entryPoint.parse(builder, system, payload, tempDir),
            name + " should raise IonException rather than decompress when the toggle is off");
    }

    /** Uncompressed payloads must reach every entry point unchanged, whatever the toggle says. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("entryPoints")
    void uncompressedIonIsUnaffectedByTheToggle(String name, ParseEntryPoint entryPoint,
                                                @TempDir Path tempDir) throws Exception {
        for (boolean gzipEnabled : new boolean[] {true, false}) {
            IonReaderBuilder builder = IonReaderBuilder.standard()
                .withGzipDecompressionEnabled(gzipEnabled);
            for (byte[] payload : new byte[][] {
                SINGLE_VALUE.getBytes("UTF-8"),
                binaryIon(SINGLE_VALUE)
            }) {
                assertEquals(1, entryPoint.parse(builder, systemFor(builder), payload, tempDir),
                    name + " should read uncompressed Ion with gzipEnabled=" + gzipEnabled);
            }
        }
    }

    //=========================================================================
    // Layering and configuration interactions
    //=========================================================================

    /**
     * Only one layer of unwrapping is applied, which is what buildReader documents: it detects
     * GZIP-compressed Ion or zstd-compressed Ion, not GZIP-compressed zstd-compressed Ion, and
     * callers needing more are expected to supply their own interceptor.
     */
    @Test
    void onlyOneLayerOfGzipIsUnwrapped() throws IOException {
        byte[] singleLayer = gzip(SINGLE_VALUE.getBytes("UTF-8"));
        byte[] doubleLayer = gzip(singleLayer);
        IonReaderBuilder builder = IonReaderBuilder.standard();

        try (IonReader reader = builder.build(singleLayer)) {
            assertEquals(1, readAllValues(reader), "one layer must still be unwrapped");
        }

        assertThrows(IonException.class, () -> {
            try (IonReader reader = builder.build(doubleLayer)) {
                readAllValues(reader);
            }
        }, "the second GZIP layer must not be unwrapped");
        assertThrows(IonException.class, () -> {
            try (IonReader reader = builder.build(new ByteArrayInputStream(doubleLayer))) {
                readAllValues(reader);
            }
        }, "the second GZIP layer must not be unwrapped");
    }

    /** Strips a 4-byte "PROB" header, standing in for any non-GZIP wrapper format. */
    private static final InputStreamInterceptor PREFIX_INTERCEPTOR = new InputStreamInterceptor() {
        @Override
        public String formatName() {
            return "prob";
        }

        @Override
        public int numberOfBytesNeededToDetermineMatch() {
            return 4;
        }

        @Override
        public boolean isMatch(byte[] candidate, int offset, int length) {
            return length >= 4
                && candidate[offset] == 'P' && candidate[offset + 1] == 'R'
                && candidate[offset + 2] == 'O' && candidate[offset + 3] == 'B';
        }

        @Override
        public InputStream newInputStream(InputStream interceptedStream) throws IOException {
            long skipped = 0;
            while (skipped < 4) {
                long n = interceptedStream.skip(4 - skipped);
                if (n <= 0) {
                    break;
                }
                skipped += n;
            }
            return interceptedStream;
        }
    };

    /** Disabling GZIP must not disturb interceptors for other formats. */
    @Test
    void customInterceptorStillAppliesWhenGzipDisabled() throws IOException {
        byte[] payload = new byte[4 + SINGLE_VALUE.length()];
        System.arraycopy("PROB".getBytes("UTF-8"), 0, payload, 0, 4);
        System.arraycopy(SINGLE_VALUE.getBytes("UTF-8"), 0, payload, 4, SINGLE_VALUE.length());

        IonReaderBuilder builder = IonReaderBuilder.standard()
            .addInputStreamInterceptor(PREFIX_INTERCEPTOR)
            .withGzipDecompressionEnabled(false);

        try (IonReader reader = builder.build(payload)) {
            assertEquals(1, readAllValues(reader));
        }
        try (IonReader reader = builder.build(new ByteArrayInputStream(payload))) {
            assertEquals(1, readAllValues(reader));
        }
    }

    /** The toggle must hold regardless of whether incremental reading is enabled. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void toggleHoldsWithIncrementalReading(boolean incremental) throws IOException {
        byte[] gzipped = gzip(SINGLE_VALUE.getBytes("UTF-8"));

        IonReaderBuilder enabled = IonReaderBuilder.standard()
            .withIncrementalReadingEnabled(incremental);
        try (IonReader reader = enabled.build(new ByteArrayInputStream(gzipped))) {
            assertEquals(1, readAllValues(reader));
        }

        IonReaderBuilder disabled = enabled.copy().withGzipDecompressionEnabled(false);
        assertThrows(IonException.class, () -> {
            try (IonReader reader = disabled.build(new ByteArrayInputStream(gzipped))) {
                readAllValues(reader);
            }
        });
    }

    /** A truncated GZIP payload must surface as IonException, not as a raw stream failure. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void truncatedGzipIsReportedAsIonException(boolean gzipEnabled) throws IOException {
        byte[] complete = gzip(SINGLE_VALUE.getBytes("UTF-8"));
        byte[] truncated = new byte[complete.length / 2];
        System.arraycopy(complete, 0, truncated, 0, truncated.length);

        IonReaderBuilder builder = IonReaderBuilder.standard()
            .withGzipDecompressionEnabled(gzipEnabled);
        assertThrows(IonException.class, () -> {
            try (IonReader reader = builder.build(new ByteArrayInputStream(truncated))) {
                readAllValues(reader);
            }
        });
    }

}
