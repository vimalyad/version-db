package com.versiondb.storage;

import com.versiondb.shared.ColumnType;
import com.versiondb.shared.SerializationException;
import com.versiondb.shared.Value;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes and decodes a row of {@link Value}s to and from the raw bytes stored
 * in a heap tuple. This is the single serialization contract shared by every
 * layer that reads or writes tuple bytes.
 *
 * <p>Layout:
 * <ul>
 *   <li>a null bitmap of {@code ceil(n/8)} bytes, one bit per column
 *       (bit set = that column is SQL NULL), bit {@code i} at byte {@code i/8},
 *       position {@code i%8} (least significant first);</li>
 *   <li>then, for each non-null column in schema order: INT as 8 bytes
 *       big-endian, FLOAT as 8 bytes IEEE-754, BOOL as 1 byte (0/1), VARCHAR as
 *       a 4-byte length prefix followed by its UTF-8 bytes.</li>
 * </ul>
 * NULL columns contribute only their bitmap bit; no value bytes are written.
 */
public final class TupleCodec {

    private TupleCodec() {
    }

    /** Encode a row. {@code values} and {@code types} must have equal length. */
    public static byte[] encode(List<Value> values, List<ColumnType> types) {
        if (values.size() != types.size()) {
            throw new SerializationException("value count " + values.size()
                    + " does not match column count " + types.size());
        }
        int n = types.size();
        byte[] nullBitmap = new byte[(n + 7) / 8];
        for (int i = 0; i < n; i++) {
            Value v = values.get(i);
            if (v.type() != types.get(i)) {
                throw new SerializationException("column " + i + " is " + types.get(i)
                        + " but value is " + v.type());
            }
            if (v.isNull()) {
                nullBitmap[i / 8] |= (byte) (1 << (i % 8));
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write(nullBitmap);
            for (int i = 0; i < n; i++) {
                Value v = values.get(i);
                if (v.isNull()) {
                    continue;
                }
                switch (types.get(i)) {
                    case INT -> out.writeLong(v.asInt());
                    case FLOAT -> out.writeDouble(v.asFloat());
                    case BOOL -> out.writeByte(v.asBool() ? 1 : 0);
                    case VARCHAR -> {
                        byte[] s = v.asString().getBytes(StandardCharsets.UTF_8);
                        out.writeInt(s.length);
                        out.write(s);
                    }
                }
            }
        } catch (IOException e) {
            throw new SerializationException("failed to encode tuple", e);
        }
        return bytes.toByteArray();
    }

    /** Decode a row previously written by {@link #encode} against the same schema. */
    public static List<Value> decode(byte[] data, List<ColumnType> types) {
        int n = types.size();
        int bitmapSize = (n + 7) / 8;
        List<Value> values = new ArrayList<>(n);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte[] nullBitmap = new byte[bitmapSize];
            in.readFully(nullBitmap);
            for (int i = 0; i < n; i++) {
                ColumnType type = types.get(i);
                boolean isNull = (nullBitmap[i / 8] & (1 << (i % 8))) != 0;
                if (isNull) {
                    values.add(Value.nullValue(type));
                    continue;
                }
                values.add(switch (type) {
                    case INT -> Value.ofInt(in.readLong());
                    case FLOAT -> Value.ofFloat(in.readDouble());
                    case BOOL -> Value.ofBool(in.readByte() != 0);
                    case VARCHAR -> {
                        int len = in.readInt();
                        byte[] s = new byte[len];
                        in.readFully(s);
                        yield Value.ofString(new String(s, StandardCharsets.UTF_8));
                    }
                });
            }
        } catch (IOException e) {
            throw new SerializationException("failed to decode tuple", e);
        }
        return values;
    }
}
