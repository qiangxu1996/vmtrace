/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.zipflinger;

import com.android.annotations.NonNull;
import java.nio.ByteBuffer;

class CentralDirectoryRecord {

    public static final int SIGNATURE = 0x02014b50;
    public static final int SIZE = 46;
    public static final int DATA_DESCRIPTOR_FLAG = 0x0008;
    public static final int DATA_DESCRIPTOR_SIGNATURE = 0x08074b50;

    // JDK 9 consider time&data field with value 0 as invalid. Use 1 instead.
    // These are in MS-DOS 16-bit format. For actual specs, see:
    // https://msdn.microsoft.com/en-us/library/windows/desktop/ms724247(v=vs.85).aspx
    public static final short DEFAULT_TIME = 1 | 1 << 5 | 1 << 11;
    public static final short DEFAULT_DATE = 1 | 1 << 5 | 1 << 9;

    private final byte[] nameBytes;
    private final int crc;
    private final long compressedSize;
    private final long uncompressedSize;
    // Location of the Local file header to end of payload in file space.
    private final Location location;
    private final short compressionFlag;
    private final Location payloadLocation;

    CentralDirectoryRecord(
            @NonNull byte[] nameBytes,
            int crc,
            long compressedSize,
            long uncompressedSize,
            Location location,
            short compressionFlag,
            Location payloadLocation) {
        this.nameBytes = nameBytes;
        this.crc = crc;
        this.compressedSize = compressedSize;
        this.uncompressedSize = uncompressedSize;
        this.location = location;
        this.compressionFlag = compressionFlag;
        this.payloadLocation = payloadLocation;
    }

    void write(@NonNull ByteBuffer buf) {
        buf.putInt(SIGNATURE);
        buf.putShort((short) 0); // version made by
        buf.putShort(LocalFileHeader.DEFAULT_VERSION_NEEDED);
        buf.putShort((short) 0); // flag
        buf.putShort(compressionFlag);
        buf.putShort(DEFAULT_TIME);
        buf.putShort(DEFAULT_DATE);
        buf.putInt(crc);
        buf.putInt(Ints.longToUint(compressedSize));
        buf.putInt(Ints.longToUint(uncompressedSize));
        buf.putShort(Ints.intToUshort(nameBytes.length));
        //TODO Zip64 -> Write extra zip64 field
        buf.putShort((short) 0); // Extra size
        buf.putShort((short) 0); // comment size
        buf.putShort((short) 0); // disk # start
        buf.putShort((short) 0); // internal att
        buf.putInt(0); // external att
        buf.putInt(Ints.longToUint(location.first));
        buf.put(nameBytes);
    }

    int getCrc() {
        return crc;
    }

    long getCompressedSize() {
        return compressedSize;
    }

    long getUncompressedSize() {
        return uncompressedSize;
    }

    short getCompressionFlag() {
        return compressionFlag;
    }

    long getSize() {
        //TODO Zip64 -> Factor in if a zip64 extra field is needed.
        return SIZE + nameBytes.length;
    }

    @NonNull
    Location getPayloadLocation() {
        return payloadLocation;
    }

    @NonNull
    Location getLocation() {
        return location;
    }
}
