package com.clickhouse.client.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.clickhouse.client.ClickHouseByteBuffer;
import com.clickhouse.client.ClickHouseChecker;
import com.clickhouse.client.ClickHouseDeserializer;
import com.clickhouse.client.ClickHouseFormat;
import com.clickhouse.client.ClickHouseInputStream;
import com.clickhouse.client.ClickHouseOutputStream;
import com.clickhouse.client.ClickHouseSerializer;
import com.clickhouse.client.ClickHouseValue;

public interface TextDataProcessor {
    static class TextDeSer implements ClickHouseDeserializer, ClickHouseSerializer {
        private static final Map<String, TextDeSer> cache = new HashMap<>();

        public static final TextDeSer of(byte escapeChar, byte recordSeparator, byte valueSeparator, String nullValue) {
            String key = new StringBuffer().append((char) escapeChar).append((char) recordSeparator)
                    .append((char) valueSeparator)
                    .append(nullValue).toString();
            return cache.computeIfAbsent(key, TextDeSer::new);
        }

        // TODO what about quotes?
        private final byte escapeChar;
        private final byte recordSeparator;
        private final byte valueSeparator;
        private final byte[] nullValue;

        protected int read(byte[] bytes, int position, int limit, byte stopChar) {
            int offset = 0;
            for (int i = position; i < limit; i++) {
                byte b = bytes[i];
                offset++;
                if (b == stopChar) {
                    return offset;
                }
            }

            return -1;
        }

        public TextDeSer(String str) {
            byte[] bytes = ClickHouseChecker.nonNull(str, "String").getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 3) {
                throw new IllegalArgumentException(
                        "Expect a string with at least 3 characters: 1) escape-character; 2) record seperator; 3) value separator");
            }

            this.escapeChar = bytes[0];
            this.recordSeparator = bytes[1];
            this.valueSeparator = bytes[2];
            if (this.recordSeparator == (byte) 0) {
                throw new IllegalArgumentException("Record separator must be specified and it should never be 0x00");
            }
            this.nullValue = Arrays.copyOfRange(bytes, 3, bytes.length);
        }

        public TextDeSer(byte escapeChar, byte recordSeparator, byte valueSeparator, String nullValue) {
            this.escapeChar = escapeChar;
            this.recordSeparator = recordSeparator;
            this.valueSeparator = valueSeparator;
            this.nullValue = nullValue == null ? new byte[0] : nullValue.getBytes(StandardCharsets.UTF_8);
        }

        public byte getRecordSeparator() {
            return recordSeparator;
        }

        public byte getValueSeparator() {
            return valueSeparator;
        }

        public boolean hasEscapeCharacter() {
            return escapeChar != (byte) 0;
        }

        public boolean hasValueSeparator() {
            return valueSeparator != (byte) 0;
        }

        protected int readRecord(byte[] bytes, int position, int limit) {
            if (escapeChar == (byte) 0) {
                return read(bytes, position, limit, recordSeparator);
            }

            int offset = 0;
            byte prev = 0;
            for (int i = position; i < limit; i++) {
                byte b = bytes[i];
                offset++;
                if (prev == escapeChar) {
                    prev = b == escapeChar ? (byte) 0 : b;
                } else {
                    prev = b;
                    if (b == recordSeparator) {
                        return offset;
                    }
                }
            }

            return -1;
        }

        protected int readValue(byte[] bytes, int position, int limit) {
            if (escapeChar == (byte) 0) {
                return read(bytes, position, limit, valueSeparator);
            }

            int offset = 0;
            byte prev = 0;
            for (int i = position; i < limit; i++) {
                byte b = bytes[i];
                offset++;
                if (prev == escapeChar) {
                    prev = b == escapeChar ? (byte) 0 : b;
                } else {
                    prev = b;
                    if (b == valueSeparator || b == recordSeparator) {
                        return offset;
                    }
                }
            }

            return -1;
        }

        protected ClickHouseByteBuffer readBuffer(ClickHouseInputStream input) throws IOException {
            ClickHouseByteBuffer buf;
            if (valueSeparator != (byte) 0) {
                buf = input.readCustom(this::readValue);
                int len = buf.length();
                if (len > 0 && (buf.lastByte() == valueSeparator || buf.lastByte() == recordSeparator)) {
                    if (escapeChar == (byte) 0 || (len > 1 && buf.getByte(buf.length() - 2) != escapeChar)) {
                        buf.setLength(len - 1);
                    }
                }
            } else {
                buf = input.readCustom(this::readRecord);
                int len = buf.length();
                if (len > 0 && buf.lastByte() == recordSeparator) {
                    if (escapeChar == (byte) 0 || (len > 1 && buf.getByte(buf.length() - 2) != escapeChar)) {
                        buf.setLength(len - 1);
                    }
                }
            }
            return buf;
        }

        public ClickHouseValue deserializeBinary(ClickHouseValue ref, ClickHouseInputStream input) throws IOException {
            ClickHouseByteBuffer buf = readBuffer(input);
            // TODO unescape
            return ref.update(buf.match(nullValue) ? null : buf.compact().array());
        }

        @Override
        public ClickHouseValue deserialize(ClickHouseValue ref, ClickHouseInputStream input) throws IOException {
            ClickHouseByteBuffer buf = readBuffer(input);
            // TODO unescape
            return ref.update(buf.match(nullValue) ? null : buf.asUnicodeString());
        }

        public void serializeBinary(ClickHouseValue value, ClickHouseOutputStream output) throws IOException {
            byte[] bytes = value.asBinary();
            // TODO escape
            if (bytes == null) {
                output.writeBytes(nullValue);
            } else {
                output.writeBytes(bytes);
            }
        }

        @Override
        public void serialize(ClickHouseValue value, ClickHouseOutputStream output) throws IOException {
            String str = value.asString();
            // TODO escape
            if (str == null) {
                output.writeBytes(nullValue);
            } else {
                output.writeBytes(str.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    static TextDeSer getTextDeSer(ClickHouseFormat format) {
        final TextDeSer deser;
        switch (format) {
            case CSV:
            case CSVWithNames:
                deser = new TextDeSer("\\\n,\\N");
                break;
            case TSV:
            case TSVRaw:
            case TSVWithNames:
            case TSVWithNamesAndTypes:
            case TabSeparated:
            case TabSeparatedRaw:
            case TabSeparatedWithNames:
            case TabSeparatedWithNamesAndTypes:
                deser = new TextDeSer("\\\n\t\\N");
                break;
            default:
                deser = new TextDeSer("\\\n\0\\N");
                break;
        }
        return deser;
    }
}
