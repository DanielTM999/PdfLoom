package dtm.pdfloom.font;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class WoffDecoder {
    private WoffDecoder() {
    }

    public static byte[] decode(byte[] source, long limit) throws IOException {
        if (source.length < 4 || ByteBuffer.wrap(source).getInt() != 0x774f4646) {
            return source;
        }
        if (source.length < 44) {
            throw new IOException("Truncated WOFF header");
        }
        ByteBuffer input = ByteBuffer.wrap(source).order(ByteOrder.BIG_ENDIAN);
        int flavor = input.getInt(4);
        long length = Integer.toUnsignedLong(input.getInt(8));
        int count = Short.toUnsignedInt(input.getShort(12));
        long size = Integer.toUnsignedLong(input.getInt(16));
        if (length != source.length
                || count < 1
                || count > 4095
                || 44L + count * 20L > source.length
                || size > limit
                || size < 12L + count * 16L
                || size > Integer.MAX_VALUE
                || input.getShort(14) != 0) {
            throw new IOException("Invalid WOFF size or directory");
        }
        List<Range> ranges = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int entry = 44 + index * 20;
            long offset = Integer.toUnsignedLong(input.getInt(entry + 4));
            long compressed = Integer.toUnsignedLong(input.getInt(entry + 8));
            ranges.add(new Range(offset, offset + compressed));
        }
        long metadataOffset = Integer.toUnsignedLong(input.getInt(24));
        long metadataLength = Integer.toUnsignedLong(input.getInt(28));
        long metadataOriginal = Integer.toUnsignedLong(input.getInt(32));
        long privateOffset = Integer.toUnsignedLong(input.getInt(36));
        long privateLength = Integer.toUnsignedLong(input.getInt(40));
        if ((metadataOffset == 0) != (metadataLength == 0)
                || (metadataOffset == 0) != (metadataOriginal == 0)
                || (privateOffset == 0) != (privateLength == 0)
                || metadataOriginal > limit) {
            throw new IOException("Invalid WOFF optional block");
        }
        if (metadataOffset != 0) {
            ranges.add(new Range(metadataOffset, metadataOffset + metadataLength));
        }
        if (privateOffset != 0) {
            ranges.add(new Range(privateOffset, privateOffset + privateLength));
        }
        ranges.sort(Comparator.comparingLong(Range::start));
        long previousEnd = 44L + count * 20L;
        for (Range range : ranges) {
            if ((range.start() & 3) != 0
                    || range.start() < previousEnd
                    || range.end() <= range.start()
                    || range.end() > length) {
                throw new IOException("Overlapping or invalid WOFF block");
            }
            previousEnd = range.end();
        }
        ByteBuffer output = ByteBuffer.allocate((int) size).order(ByteOrder.BIG_ENDIAN);
        int power = Integer.highestOneBit(count);
        output.putInt(flavor)
                .putShort((short) count)
                .putShort((short) (power * 16))
                .putShort((short) Integer.numberOfTrailingZeros(power))
                .putShort((short) (count * 16 - power * 16));
        int cursor = 12 + count * 16;
        int previousTag = 0;
        boolean first = true;
        for (int index = 0; index < count; index++) {
            int entry = 44 + index * 20;
            int tag = input.getInt(entry);
            long offset = Integer.toUnsignedLong(input.getInt(entry + 4));
            long compressed = Integer.toUnsignedLong(input.getInt(entry + 8));
            long original = Integer.toUnsignedLong(input.getInt(entry + 12));
            int checksum = input.getInt(entry + 16);
            if ((!first && Integer.compareUnsigned(previousTag, tag) >= 0)
                    || offset < 44L + count * 20L
                    || (offset & 3) != 0
                    || compressed < 1
                    || compressed > original
                    || offset + compressed > source.length
                    || original > size
                    || cursor + original > size) {
                throw new IOException("Invalid WOFF table");
            }
            first = false;
            previousTag = tag;
            output.position(12 + index * 16);
            output.putInt(tag).putInt(checksum).putInt(cursor).putInt((int) original);
            byte[] table;
            if (compressed == original) {
                table =
                        java.util.Arrays.copyOfRange(
                                source, (int) offset, (int) (offset + original));
            } else {
                table = inflate(source, (int) offset, (int) compressed, (int) original);
            }
            if (checksum(table, tag == 0x68656164) != checksum) {
                throw new IOException("WOFF table checksum mismatch");
            }
            output.position(cursor);
            output.put(table);
            cursor = (cursor + table.length + 3) & ~3;
        }
        if (cursor != size) {
            throw new IOException("WOFF SFNT size mismatch");
        }
        return output.array();
    }

    private record Range(long start, long end) {
    }

    private static int checksum(byte[] table, boolean head) {
        int sum = 0;
        for (int offset = 0; offset < table.length; offset += 4) {
            int word = 0;
            for (int index = 0; index < 4; index++) {
                int position = offset + index;
                int value = position < table.length ? Byte.toUnsignedInt(table[position]) : 0;
                if (head && position >= 8 && position < 12) {
                    value = 0;
                }
                word = (word << 8) | value;
            }
            sum += word;
        }
        return sum;
    }

    private static byte[] inflate(byte[] source, int offset, int compressed, int original)
            throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(source, offset, compressed);
            byte[] decoded = new byte[original];
            int written = 0;
            while (!inflater.finished() && written < original) {
                int count = inflater.inflate(decoded, written, original - written);
                if (count == 0) {
                    throw new IOException("Invalid WOFF compressed table");
                }
                written += count;
            }
            if (!inflater.finished() || written != original || inflater.getRemaining() != 0) {
                throw new IOException("WOFF decoded length mismatch");
            }
            return decoded;
        } catch (DataFormatException exception) {
            throw new IOException("Invalid WOFF compression", exception);
        } finally {
            inflater.end();
        }
    }
}
