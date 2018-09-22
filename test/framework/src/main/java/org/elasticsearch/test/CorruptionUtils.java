/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.test;

import com.carrotsearch.randomizedtesting.generators.RandomPicks;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.elasticsearch.common.logging.ESLoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;

import static org.apache.lucene.util.LuceneTestCase.assumeTrue;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;


public final class CorruptionUtils {
    private static Logger logger = ESLoggerFactory.getLogger("test");
    private CorruptionUtils() {}

    /**
     * Corrupts a random file at a random position
     */
    public static void corruptFile(Random random, Path... files) throws IOException {
        assertTrue("files must be non-empty", files.length > 0);
        final Path fileToCorrupt = RandomPicks.randomFrom(random, files);
        assertTrue(fileToCorrupt + " is not a file", Files.isRegularFile(fileToCorrupt));
        try (Directory dir = FSDirectory.open(fileToCorrupt.toAbsolutePath().getParent())) {
            long checksumBeforeCorruption;
            try (IndexInput input = dir.openInput(fileToCorrupt.getFileName().toString(), IOContext.DEFAULT)) {
                checksumBeforeCorruption = CodecUtil.retrieveChecksum(input);
            }
            try (FileChannel raf = FileChannel.open(fileToCorrupt, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                long maxPosition = raf.size();

                if (fileToCorrupt.getFileName().toString().endsWith(".cfs") && maxPosition > 4) {
                    // TODO: it is known that Lucene does not check the checksum of CFS file (CompoundFileS, like an archive)
                    // see note at https://github.com/elastic/elasticsearch/pull/33911
                    // so far, don't corrupt crc32 part of checksum (last 4 bytes) of cfs file
                    // checksum is 8 bytes: first 4 bytes have to be zeros, while crc32 value is not verified
                    maxPosition -= 4;
                }
                final int position = random.nextInt((int) Math.min(Integer.MAX_VALUE, maxPosition));
                corruptAt(fileToCorrupt, raf, position);
            }

            long checksumAfterCorruption;
            long actualChecksumAfterCorruption;
            try (ChecksumIndexInput input = dir.openChecksumInput(fileToCorrupt.getFileName().toString(), IOContext.DEFAULT)) {
                assertThat(input.getFilePointer(), is(0L));
                input.seek(input.length() - 8); // one long is the checksum... 8 bytes
                checksumAfterCorruption = input.getChecksum();
                actualChecksumAfterCorruption = input.readLong();
            }
            // we need to add assumptions here that the checksums actually really don't match there is a small chance to get collisions
            // in the checksum which is ok though....
            StringBuilder msg = new StringBuilder();
            msg.append("before: [").append(checksumBeforeCorruption).append("] ");
            msg.append("after: [").append(checksumAfterCorruption).append("] ");
            msg.append("checksum value after corruption: ").append(actualChecksumAfterCorruption).append("] ");
            msg.append("file: ").append(fileToCorrupt.getFileName()).append(" length: ");
            msg.append(dir.fileLength(fileToCorrupt.getFileName().toString()));
            logger.info("Checksum {}", msg);
            assumeTrue("Checksum collision - " + msg.toString(),
                    checksumAfterCorruption != checksumBeforeCorruption // collision
                            || actualChecksumAfterCorruption != checksumBeforeCorruption); // checksum corrupted
            assertThat("no file corrupted", fileToCorrupt, notNullValue());
        }
    }

    static void corruptAt(Path path, FileChannel channel, int position) throws IOException {
        // read
        channel.position(position);
        long filePointer = channel.position();
        ByteBuffer bb = ByteBuffer.wrap(new byte[1]);
        channel.read(bb);
        bb.flip();

        // corrupt
        byte oldValue = bb.get(0);
        byte newValue = (byte) (oldValue + 1);
        bb.put(0, newValue);

        // rewrite
        channel.position(filePointer);
        channel.write(bb);
        logger.info("Corrupting file --  flipping at position {} from {} to {} file: {}", filePointer,
                Integer.toHexString(oldValue), Integer.toHexString(newValue), path.getFileName());
    }


}
