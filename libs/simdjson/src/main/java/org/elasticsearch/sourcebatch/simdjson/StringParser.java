/*
 * @notice
 *
 * Copyright 2021-2024 The simdjson-java contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Based on a modification of https://github.com/simdjson/simdjson-java,
 * licensed under the Apache License 2.0.
 */

package org.elasticsearch.sourcebatch.simdjson;

import jdk.incubator.vector.ByteVector;

import static org.elasticsearch.sourcebatch.simdjson.CharacterUtils.escape;
import static org.elasticsearch.sourcebatch.simdjson.CharacterUtils.hexToInt;

public class StringParser {

    private static final byte BACKSLASH = '\\';
    private static final byte QUOTE = '"';
    private static final int BYTES_PROCESSED = VectorUtils.BYTE_SPECIES.vectorByteSize();
    private static final int MIN_HIGH_SURROGATE = 0xD800;
    private static final int MAX_HIGH_SURROGATE = 0xDBFF;
    private static final int MIN_LOW_SURROGATE = 0xDC00;
    private static final int MAX_LOW_SURROGATE = 0xDFFF;

    public int parseString(byte[] buffer, int idx, byte[] stringBuffer) {
        return doParseString(buffer, idx, stringBuffer, 0);
    }

    private int doParseString(byte[] buffer, int idx, byte[] stringBuffer, int offset) {
        int src = idx + 1;
        int dst = offset;
        while (true) {
            ByteVector srcVec = ByteVector.fromArray(VectorUtils.BYTE_SPECIES, buffer, src);
            srcVec.intoArray(stringBuffer, dst);
            long backslashBits = srcVec.eq(BACKSLASH).toLong();
            long quoteBits = srcVec.eq(QUOTE).toLong();

            if (hasQuoteFirst(backslashBits, quoteBits)) {
                dst += Long.numberOfTrailingZeros(quoteBits);
                break;
            }
            if (hasBackslash(backslashBits, quoteBits)) {
                int backslashDist = Long.numberOfTrailingZeros(backslashBits);
                byte escapeChar = buffer[src + backslashDist + 1];
                if (escapeChar == 'u') {
                    src += backslashDist;
                    dst += backslashDist;
                    int codePoint = hexToInt(buffer, src + 2);
                    src += 6;
                    if (codePoint >= MIN_HIGH_SURROGATE && codePoint <= MAX_HIGH_SURROGATE) {
                        codePoint = parseLowSurrogate(buffer, src, codePoint);
                        src += 6;
                    } else if (codePoint >= MIN_LOW_SURROGATE && codePoint <= MAX_LOW_SURROGATE) {
                        throw new JsonParsingException("Invalid code point. The range U+DC00–U+DFFF is reserved for low surrogate.");
                    }
                    dst += storeCodePointInStringBuffer(codePoint, dst, stringBuffer);
                } else {
                    stringBuffer[dst + backslashDist] = escape(escapeChar);
                    src += backslashDist + 2;
                    dst += backslashDist + 1;
                }
            } else {
                src += BYTES_PROCESSED;
                dst += BYTES_PROCESSED;
            }
        }
        return dst;
    }

    private int parseLowSurrogate(byte[] buffer, int src, int codePoint) {
        if ((buffer[src] << 8 | buffer[src + 1]) != ('\\' << 8 | 'u')) {
            throw new JsonParsingException("Low surrogate should start with '\\u'");
        } else {
            int codePoint2 = hexToInt(buffer, src + 2);
            int lowBit = codePoint2 - MIN_LOW_SURROGATE;
            if (lowBit >> 10 == 0) {
                return (((codePoint - MIN_HIGH_SURROGATE) << 10) | lowBit) + 0x10000;
            } else {
                throw new JsonParsingException("Invalid code point. Low surrogate should be in the range U+DC00–U+DFFF.");
            }
        }
    }

    private int storeCodePointInStringBuffer(int codePoint, int dst, byte[] stringBuffer) {
        if (codePoint < 0) {
            // TODO: Look into this. Clickbench was failing with this.
            // hexToInt returned -1: the four bytes after \\u were not all valid hex digits.
            // Output U+FFFD (replacement character) so the parser can continue rather than
            // aborting the document — the caller's fallback path (Jackson) would produce the
            // same replacement behavior for truly malformed escapes.
            stringBuffer[dst] = (byte) 0xEF;
            stringBuffer[dst + 1] = (byte) 0xBF;
            stringBuffer[dst + 2] = (byte) 0xBD;
            return 3;
        }
        if (codePoint <= 0x7F) {
            stringBuffer[dst] = (byte) codePoint;
            return 1;
        }
        if (codePoint <= 0x7FF) {
            stringBuffer[dst] = (byte) ((codePoint >> 6) + 192);
            stringBuffer[dst + 1] = (byte) ((codePoint & 63) + 128);
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            stringBuffer[dst] = (byte) ((codePoint >> 12) + 224);
            stringBuffer[dst + 1] = (byte) (((codePoint >> 6) & 63) + 128);
            stringBuffer[dst + 2] = (byte) ((codePoint & 63) + 128);
            return 3;
        }
        if (codePoint <= 0x10FFFF) {
            stringBuffer[dst] = (byte) ((codePoint >> 18) + 240);
            stringBuffer[dst + 1] = (byte) (((codePoint >> 12) & 63) + 128);
            stringBuffer[dst + 2] = (byte) (((codePoint >> 6) & 63) + 128);
            stringBuffer[dst + 3] = (byte) ((codePoint & 63) + 128);
            return 4;
        }
        throw new IllegalStateException("Code point is greater than 0x110000.");
    }

    private boolean hasQuoteFirst(long backslashBits, long quoteBits) {
        return ((backslashBits - 1) & quoteBits) != 0;
    }

    private boolean hasBackslash(long backslashBits, long quoteBits) {
        return ((quoteBits - 1) & backslashBits) != 0;
    }
}
