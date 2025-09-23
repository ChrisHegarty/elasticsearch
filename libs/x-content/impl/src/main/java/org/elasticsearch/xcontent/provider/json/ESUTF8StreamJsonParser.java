/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.xcontent.provider.json;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.json.UTF8StreamJsonParser;
import com.fasterxml.jackson.core.sym.ByteQuadsCanonicalizer;

import org.elasticsearch.simdvec.ESVectorUtil;
import org.elasticsearch.xcontent.Text;
import org.elasticsearch.xcontent.XContentString;
import org.elasticsearch.xcontent.provider.OptimizedTextCapable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class ESUTF8StreamJsonParser extends UTF8StreamJsonParser implements OptimizedTextCapable {
    private static final VarHandle LONG_HANDLE_LE =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    protected int stringEnd = -1;
    protected int stringLength;
    protected byte[] lastOptimisedValue;

    private final List<Integer> backslashes = new ArrayList<>();

    public ESUTF8StreamJsonParser(
        IOContext ctxt,
        int features,
        InputStream in,
        ObjectCodec codec,
        ByteQuadsCanonicalizer sym,
        byte[] inputBuffer,
        int start,
        int end,
        int bytesPreProcessed,
        boolean bufferRecyclable
    ) {
        super(ctxt, features, in, codec, sym, inputBuffer, start, end, bytesPreProcessed, bufferRecyclable);
    }

    /**
     * Method that will try to get underlying UTF-8 encoded bytes of the current string token.
     * This is only a best-effort attempt; if there is some reason the bytes cannot be retrieved, this method will return null.
     */
    @Override
    public Text getValueAsText() throws IOException {
        if (_currToken == JsonToken.VALUE_STRING && _tokenIncomplete) {
            if (lastOptimisedValue != null) {
                return new Text(new XContentString.UTF8Bytes(lastOptimisedValue), stringLength);
            }
            if (stringEnd > 0) {
                final int len = stringEnd - 1 - _inputPtr;
                return new Text(new XContentString.UTF8Bytes(_inputBuffer, _inputPtr, len), stringLength);
            }
            return _finishAndReturnText();
        }
        return null;
    }

    static boolean hasZeroByte(long v) {
        return ((v - 0x0101010101010101L) & ~v & 0x8080808080808080L) != 0;
    }

    protected Text _finishAndReturnText() throws IOException {
        int ptr = _inputPtr;
        if (ptr >= _inputEnd) {
            _loadMoreGuaranteed();
            ptr = _inputPtr;
        }

        int startPtr = ptr;
        final int[] codes = INPUT_CODES_UTF8;
        final int max = _inputEnd;
        final byte[] inputBuffer = _inputBuffer;
        stringLength = 0;
        backslashes.clear();


        var x = ESVectorUtil.scanAsciiRun(inputBuffer, ptr, max - ptr);
        ptr += x;

//        while (ptr + Long.BYTES <= max) {  // SWAR
//            long word = (long) LONG_HANDLE_LE.get(inputBuffer, ptr);
//
//            // Fast reject: no high bits, no quote, no backslash
//            if ((word & 0x8080808080808080L) == 0 &&
//                hasZeroByte(word ^ 0x2222222222222222L) == false &&
//                hasZeroByte(word ^ 0x5C5C5C5C5C5C5C5CL) == false ) {
//                ptr += Long.BYTES;
//                stringLength += Long.BYTES;
//                // System.out.println("+8");
//                continue;
//            }
//
//            // Otherwise, fall back to slow path (per-byte with INPUT_CODES_UTF8)
//            break;
//        }

        loop: while (true) {
            if (ptr >= max) {
                return null;
            }
            int c = inputBuffer[ptr] & 0xFF;
            switch (codes[c]) {
                case 0 -> {
                    ++ptr;
                    ++stringLength;
                }
                case 1 -> {
                    if (c == INT_QUOTE) {
                        // End of the string
                        break loop;
                    }
                    assert c == INT_BACKSLASH;
                    backslashes.add(ptr);
                    ++ptr;
                    if (ptr >= max) {
                        // Backslash at end of file
                        return null;
                    }
                    c = inputBuffer[ptr] & 0xFF;
                    if (c == '"' || c == '/' || c == '\\') {
                        ptr += 1;
                        stringLength += 1;
                    } else {
                        // Any other escaped sequence requires replacing the sequence with
                        // a new character, which we don't support in the optimized path
                        return null;
                    }
                }
                case 2, 3, 4 -> {
                    int bytesToSkip = codes[c];
                    if (ptr + bytesToSkip > max) {
                        return null;
                    }
                    ptr += bytesToSkip;
                    // Code points that require 4 bytes in UTF-8 will use 2 chars in UTF-16.
                    stringLength += (bytesToSkip == 4 ? 2 : 1);
                }
                default -> {
                    return null;
                }
            }
        }

        stringEnd = ptr + 1;
        if (backslashes.isEmpty()) {
            return new Text(new XContentString.UTF8Bytes(inputBuffer, startPtr, ptr - startPtr), stringLength);
        } else {
            byte[] buff = new byte[ptr - startPtr - backslashes.size()];
            int copyPtr = startPtr;
            int destPtr = 0;
            for (Integer backslash : backslashes) {
                int length = backslash - copyPtr;
                System.arraycopy(inputBuffer, copyPtr, buff, destPtr, length);
                destPtr += length;
                copyPtr = backslash + 1;
            }
            System.arraycopy(inputBuffer, copyPtr, buff, destPtr, ptr - copyPtr);
            lastOptimisedValue = buff;
            return new Text(new XContentString.UTF8Bytes(buff), stringLength);
        }
    }

    protected Text _finishAndReturnTextNEW() throws IOException {

        ESVectorUtil.indexOf(new byte[] { 0, 0, 0 }, 5555, 1, (byte) 0x0A);

        int ptr = _inputPtr;
        if (ptr >= _inputEnd) {
            _loadMoreGuaranteed();
            ptr = _inputPtr;
        }

        int startPtr = ptr;
        final int max = _inputEnd;
        final byte[] inputBuffer = _inputBuffer;
        stringLength = 0;
        backslashes.clear();

        while (ptr < max) {
            int c = inputBuffer[ptr] & 0xFF;

            int isQuote     = ((c ^ '"') - 1) >>> 31;
            int isBackslash = ((c ^ '\\') - 1) >>> 31;
            int isAscii     = (~c >>> 7) & 1;

            // ASCII non-specials count toward length
            stringLength += isAscii & ~(isQuote | isBackslash);

            // Backslashes just increment length (assuming \" or \\ only)
            stringLength += isBackslash;

            // Mark completion if quote found
            if (isQuote == 1) {
                stringEnd = ptr + 1;
                break;
            }
            ptr++;
        }

        return stringLength > 0 ? new Text(new XContentString.UTF8Bytes(inputBuffer, startPtr, ptr - startPtr), stringLength) : null;


//        while (ptr < max) {
//            int c = inputBuffer[ptr] & 0xFF;
//
//            //   // quote check: 0xFF if c == '"', else 0x00
//            int isQuote = -(c ^ '"') >>> 31; // mask: 1 if equal, else 0
//            if (isQuote != 0) {
//                // found end
//                stringEnd = ptr + 1;
//                // if (backslashes.isEmpty()) {
//                return new Text(new XContentString.UTF8Bytes(inputBuffer, startPtr, ptr - startPtr), stringLength);
//            }
//
//            // Fast ASCII path (no branch for most characters)
//            // if ((c & 0x80) == 0) {
//            // mask-based equality check
////            if (c == '"') {
////                // Found terminating quote
////                stringEnd = ptr + 1;
////                // if (backslashes.isEmpty()) {
////                return new Text(new XContentString.UTF8Bytes(inputBuffer, startPtr, ptr - startPtr), stringLength);
////                // } else {
////                // // rebuild string without backslashes
////                // byte[] buff = new byte[ptr - startPtr - backslashes.size()];
////                // int copyPtr = startPtr;
////                // int destPtr = 0;
////                // for (int backslash : backslashes) {
////                // int length = backslash - copyPtr;
////                // System.arraycopy(inputBuffer, copyPtr, buff, destPtr, length);
////                // destPtr += length;
////                // copyPtr = backslash + 1;
////                // }
////                // System.arraycopy(inputBuffer, copyPtr, buff, destPtr, ptr - copyPtr);
////                // lastOptimisedValue = buff;
////                // return new Text(new XContentString.UTF8Bytes(buff), stringLength);
////                // }
////            }
//
//            // if (c == '\\') {
//            // // record backslash
//            // backslashes.add(ptr);
//            // ptr++;
//            // if (ptr >= max) {
//            // return null; // incomplete escape
//            // }
//            // int next = inputBuffer[ptr] & 0xFF;
//            // // Only allow simple escapes in fast path
//            // if (next == '"' || next == '/' || next == '\\') {
//            // stringLength += 1;
//            // ptr++;
//            // continue;
//            // }
//            // return null; // fallback to slow path
//            // }
//
//            // Normal ASCII char
//            stringLength++;
//            ptr++;
//            continue;
//            // }
//
//            // Non-ASCII: bail out to slow path
//            // throw new AssertionError();
//            // return null;
//        }
//
//        return null; // unterminated string
    }

    @Override
    public JsonToken nextToken() throws IOException {
        maybeResetCurrentTokenState();
        stringEnd = -1;
        return super.nextToken();
    }

    @Override
    public boolean nextFieldName(SerializableString str) throws IOException {
        maybeResetCurrentTokenState();
        stringEnd = -1;
        return super.nextFieldName(str);
    }

    @Override
    public String nextFieldName() throws IOException {
        maybeResetCurrentTokenState();
        stringEnd = -1;
        return super.nextFieldName();
    }

    /**
     * Resets the current token state before moving to the next.
     */
    private void maybeResetCurrentTokenState() {
        if (_currToken == JsonToken.VALUE_STRING && _tokenIncomplete && stringEnd > 0) {
            _inputPtr = stringEnd;
            _tokenIncomplete = false;
            lastOptimisedValue = null;
        }
    }
}
