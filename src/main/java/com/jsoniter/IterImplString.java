package com.jsoniter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static java.lang.Character.MIN_HIGH_SURROGATE;
import static java.lang.Character.MIN_LOW_SURROGATE;
import static java.lang.Character.MIN_SUPPLEMENTARY_CODE_POINT;

class IterImplString {

    static int[] base64Tbl = {
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63, 52, 53, 54,
            55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2,
            3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1, -1, 26, 27, 28, 29, 30,
            31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47,
            48, 49, 50, 51, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1};

    public static final String readString(JsonIterator iter) throws IOException {
        byte c = IterImpl.nextToken(iter);
        if (c == '"') {
            // try fast path first
            for (int i = iter.head, j = 0; i < iter.tail && j < iter.reusableChars.length; i++, j++) {
                c = iter.buf[i];
                if (c == '"') {
                    iter.head = i + 1;
                    return new String(iter.reusableChars, 0, j);
                }
                // If we encounter a backslash, which is a beginning of an escape sequence
                // or a high bit was set - indicating an UTF-8 encoded multibyte character,
                // there is no chance that we can decode the string without instantiating
                // a temporary buffer, so quit this loop
                if ((c ^ '\\') < 1) break;
                iter.reusableChars[j] = (char) c;
            }
            return readStringSlowPath(iter);
        }
        if (c == 'n') {
            IterImpl.skipUntilBreak(iter);
            return null;
        }
        throw iter.reportError("readString", "expect n or \"");
    }

    final static String readStringSlowPath(JsonIterator iter) throws IOException {
        // http://grepcode.com/file_/repository.grepcode.com/java/root/jdk/openjdk/8u40-b25/sun/nio/cs/UTF_8.java/?v=source
        // byte => char with support of escape in one pass
        int j = 0;
        int minimumCapacity = iter.reusableChars.length - 2;
        for (; ; ) {
            if (j == minimumCapacity) {
                char[] newBuf = new char[iter.reusableChars.length * 2];
                System.arraycopy(iter.reusableChars, 0, newBuf, 0, iter.reusableChars.length);
                iter.reusableChars = newBuf;
                minimumCapacity = iter.reusableChars.length - 2;
            }
            int b1 = IterImpl.readByte(iter);
            if (b1 >= 0) {
                if (b1 == '"') {
                    return new String(iter.reusableChars, 0, j);
                } else if (b1 == '\\') {
                    int b2 = IterImpl.readByte(iter);
                    switch (b2) {
                        case '"':
                            iter.reusableChars[j++] = '"';
                            break;
                        case '\\':
                            iter.reusableChars[j++] = '\\';
                            break;
                        case '/':
                            iter.reusableChars[j++] = '/';
                            break;
                        case 'b':
                            iter.reusableChars[j++] = '\b';
                            break;
                        case 'f':
                            iter.reusableChars[j++] = '\f';
                            break;
                        case 'n':
                            iter.reusableChars[j++] = '\n';
                            break;
                        case 'r':
                            iter.reusableChars[j++] = '\r';
                            break;
                        case 't':
                            iter.reusableChars[j++] = '\t';
                            break;
                        case 'u':
                            iter.reusableChars[j++] = IterImplNumber.readU4(iter);
                            break;
                        default:
                            throw iter.reportError("readStringSlowPath", "unexpected escape char: " + b2);
                    }
                } else if (b1 == 0) {
                    throw iter.reportError("readStringSlowPath", "incomplete string");
                } else {
                    // 1 byte, 7 bits: 0xxxxxxx
                    iter.reusableChars[j++] = (char) b1;
                }
            } else if ((b1 >> 5) == -2 && (b1 & 0x1e) != 0) {
                // 2 bytes, 11 bits: 110xxxxx 10xxxxxx
                int b2 = IterImpl.readByte(iter);
                iter.reusableChars[j++] = (char) (((b1 << 6) ^ b2)
                        ^
                        (((byte) 0xC0 << 6) ^
                                ((byte) 0x80 << 0)));
            } else if ((b1 >> 4) == -2) {
                // 3 bytes, 16 bits: 1110xxxx 10xxxxxx 10xxxxxx
                int b2 = IterImpl.readByte(iter);
                int b3 = IterImpl.readByte(iter);
                char c = (char)
                        ((b1 << 12) ^
                                (b2 << 6) ^
                                (b3 ^
                                        (((byte) 0xE0 << 12) ^
                                                ((byte) 0x80 << 6) ^
                                                ((byte) 0x80 << 0))));
                iter.reusableChars[j++] = c;
            } else if ((b1 >> 3) == -2) {
                // 4 bytes, 21 bits: 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
                int b2 = IterImpl.readByte(iter);
                int b3 = IterImpl.readByte(iter);
                int b4 = IterImpl.readByte(iter);
                int uc = ((b1 << 18) ^
                        (b2 << 12) ^
                        (b3 << 6) ^
                        (b4 ^
                                (((byte) 0xF0 << 18) ^
                                        ((byte) 0x80 << 12) ^
                                        ((byte) 0x80 << 6) ^
                                        ((byte) 0x80 << 0))));
                iter.reusableChars[j++] = highSurrogate(uc);
                iter.reusableChars[j++] = lowSurrogate(uc);
            } else {
                throw iter.reportError("readStringSlowPath", "unexpected input");
            }
        }
    }

    private static char highSurrogate(int codePoint) {
        return (char) ((codePoint >>> 10)
                + (MIN_HIGH_SURROGATE - (MIN_SUPPLEMENTARY_CODE_POINT >>> 10)));
    }

    private static char lowSurrogate(int codePoint) {
        return (char) ((codePoint & 0x3ff) + MIN_LOW_SURROGATE);
    }

    public static final byte[] readBase64(JsonIterator iter) throws IOException {
        // from https://gist.github.com/EmilHernvall/953733
        Slice slice = IterImpl.readSlice(iter);
        if (slice == null) {
            return null;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int end = slice.tail();
        for (int i = slice.head(); i < end; i++) {
            int b = 0;
            if (base64Tbl[slice.data()[i]] != -1) {
                b = (base64Tbl[slice.data()[i]] & 0xFF) << 18;
            }
            // skip unknown characters
            else {
                i++;
                continue;
            }

            int num = 0;
            if (i + 1 < end && base64Tbl[slice.data()[i + 1]] != -1) {
                b = b | ((base64Tbl[slice.data()[i + 1]] & 0xFF) << 12);
                num++;
            }
            if (i + 2 < end && base64Tbl[slice.data()[i + 2]] != -1) {
                b = b | ((base64Tbl[slice.data()[i + 2]] & 0xFF) << 6);
                num++;
            }
            if (i + 3 < end && base64Tbl[slice.data()[i + 3]] != -1) {
                b = b | (base64Tbl[slice.data()[i + 3]] & 0xFF);
                num++;
            }

            while (num > 0) {
                int c = (b & 0xFF0000) >> 16;
                buffer.write((char) c);
                b <<= 8;
                num--;
            }
            i += 4;
        }
        return buffer.toByteArray();
    }

    // slice does not allow escape
    final static int findSliceEnd(JsonIterator iter) {
        for (int i = iter.head; i < iter.tail; i++) {
            byte c = iter.buf[i];
            if (c == '"') {
                return i + 1;
            } else if (c == '\\') {
                throw iter.reportError("findSliceEnd", "slice does not support escape char");
            }
        }
        return -1;
    }
}
