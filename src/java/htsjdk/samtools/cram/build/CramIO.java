/*******************************************************************************
 * Copyright 2013 EMBL-EBI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package htsjdk.samtools.cram.build;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMTextHeaderCodec;
import htsjdk.samtools.cram.io.CountingInputStream;
import htsjdk.samtools.cram.io.ExposedByteArrayOutputStream;
import htsjdk.samtools.cram.io.InputStreamUtils;
import htsjdk.samtools.cram.structure.Block;
import htsjdk.samtools.cram.structure.BlockCompressionMethod;
import htsjdk.samtools.cram.structure.BlockContentType;
import htsjdk.samtools.cram.structure.CompressionHeader;
import htsjdk.samtools.cram.structure.Container;
import htsjdk.samtools.cram.structure.ContainerHeaderIO;
import htsjdk.samtools.cram.structure.CramHeader;
import htsjdk.samtools.cram.structure.Slice;
import htsjdk.samtools.cram.structure.SliceIO;
import htsjdk.samtools.seekablestream.SeekableBufferedStream;
import htsjdk.samtools.seekablestream.SeekableFTPStream;
import htsjdk.samtools.seekablestream.SeekableFileStream;
import htsjdk.samtools.seekablestream.SeekableHTTPStream;
import htsjdk.samtools.seekablestream.SeekableStream;
import htsjdk.samtools.seekablestream.UserPasswordInput;
import htsjdk.samtools.util.BufferedLineReader;
import htsjdk.samtools.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CramIO {
    public static int DEFINITION_LENGTH = 4 + 1 + 1 + 20;
    private static final byte[] CHECK = "".getBytes();
    private static Log log = Log.getInstance(CramIO.class);
    public static byte[] ZERO_B_EOF_MARKER = bytesFromHex("0b 00 00 00 ff ff ff ff ff e0 45 4f 46 00 00 00 00 01 00 00 01 00 06 06 01 00 01 00 01 00");
    public static byte[] ZERO_F_EOF_MARKER = bytesFromHex("0f 00 00 00 ff ff ff ff 0f e0 45 4f 46 00 00 00 00 01 00 05 bd d9 4f 00 01 00 06 06 01 00 01 00 01 00 ee 63 01 4b");
    private static byte[] bytesFromHex(String s) {
        String clean = s.replaceAll("[^0-9a-f]", "");
        if (clean.length() % 2 != 0)
            throw new RuntimeException("Not a hex string: " + s);
        byte data[] = new byte[clean.length() / 2];
        for (int i = 0; i < clean.length(); i += 2) {
            data[i / 2] = (Integer.decode("0x" + clean.charAt(i) + clean.charAt(i + 1))).byteValue();
        }
        return data;
    }
// TOD: move to tests
//    public static void main(String[] args) throws IOException {
//        CRC32 c = new CRC32();
//        c.update(ByteBufferUtils
//                .bytesFromHex("0f 00 00 00 ff ff ff ff 0f e0 45 4f 46 00 00 00 00 01 00"));
//        int value = (int) (0xFFFFFFFF & c.getValue());
//        System.out.println(Integer.toHexString(value));
//        System.out.println(value);
//
//        c = new CRC32();
//        c.update(ByteBufferUtils
//                .bytesFromHex("00 01 00 06 06 01 00 01 00 01 00"));
//        value = (int) (0xFFFFFFFF & c.getValue());
//        System.out.println(Integer.toHexString(value));
//        System.out.println(value);
//
//        Container container = readContainer(3, new ByteArrayInputStream(
//                ZERO_F_EOF_MARKER));
//        assertThat(container.isEOF(), is(true));
//    }

    public static String getFileName(String urlString) {
        URL url = null;
        try {
            url = new URL(urlString);
            return new File(url.getFile()).getName();
        } catch (MalformedURLException e) {
            return new File(urlString).getName();
        }
    }

    public static InputStream openInputStreamFromURL(String source)
            throws SocketException, IOException, URISyntaxException {
        URL url = null;
        try {
            url = new URL(source);
        } catch (MalformedURLException e) {
            File file = new File(source);
            return new SeekableBufferedStream(new SeekableFileStream(file));
        }

        String protocol = url.getProtocol();
        if ("ftp".equalsIgnoreCase(protocol))
            return new SeekableBufferedStream(new NamedSeekableFTPStream(url));

        if ("http".equalsIgnoreCase(protocol))
            return new SeekableBufferedStream(new SeekableHTTPStream(url));

        if ("file".equalsIgnoreCase(protocol)) {
            File file = new File(url.toURI());
            return new SeekableBufferedStream(new SeekableFileStream(file));
        }

        throw new RuntimeException("Uknown protocol: " + protocol);
    }

    private static class NamedSeekableFTPStream extends SeekableFTPStream {
        /**
         * This class purpose is to preserve and pass the URL string as the
         * source.
         */
        private URL source;

        public NamedSeekableFTPStream(URL url) throws IOException {
            super(url);
            source = url;
        }

        public NamedSeekableFTPStream(URL url, UserPasswordInput userPasswordInput) throws IOException {
            super(url, userPasswordInput);
            source = url;
        }

        @Override
        public String getSource() {
            return source.toString();
        }

    }

    /**
     * A convenience method.
     * <p/>
     * If a file is supplied then it will be wrapped into a SeekableStream. If
     * file is null, then the fromIS argument will be used or System.in if null.
     * Optionally the input can be decrypted using provided password or the
     * password read from the console.
     * <p/>
     * The method also checks for EOF marker and raise error if the marker is
     * not found for files with version 2.1 or greater. For version below 2.1 a
     * warning will be issued.
     *
     * @param cramURL CRAM url to be read
     * @param decrypt  decrypt the input stream
     * @param password a password to use for decryption
     * @return an InputStream ready to be used for reading CRAM file definition
     * @throws IOException
     * @throws URISyntaxException
     */
    public static InputStream openCramInputStream(String cramURL, boolean decrypt, String password) throws IOException,
            URISyntaxException {

        InputStream is = null;
        if (cramURL == null)
            is = new BufferedInputStream(System.in);
        else
            is = openInputStreamFromURL(cramURL);

        if (decrypt) {
            // TODO: cipher lib not available, import or move this code outside of the htsjdk
//            char[] pass = null;
//            if (password == null) {
//                if (System.console() == null)
//                    throw new RuntimeException("Cannot access console.");
//                pass = System.console().readPassword();
//            } else
//                pass = password.toCharArray();
//
//            if (is instanceof SeekableStream)
//                is = new SeekableCipherStream_256((SeekableStream) is, pass, 1,
//                        128);
//            else
//                is = new CipherInputStream_256(is, pass, 128)
//                        .getCipherInputStream();

        }

        if (is instanceof SeekableStream) {
            CramHeader cramHeader = CramIO.readFormatDefinition(is, new CramHeader());
            SeekableStream s = (SeekableStream) is;
            if (!CramIO.hasZeroB_EOF_marker(s) && CramIO.hasZeroF_EOF_marker(s))
                eofNotFound(cramHeader.getMajorVersion(), cramHeader.getMinorVersion());
            s.seek(0);
        } else
            log.warn("CRAM file/stream completion cannot be verified.");

        return is;
    }

    private static void eofNotFound(byte major, byte minor) {
        if (major >= 2 && minor >= 1) {
            log.error("Incomplete data: EOF marker not found.");
            System.exit(1);
        } else {
            log.warn("EOF marker not found, possibly incomplete file/stream.");
        }
    }

    /**
     * Reads a CRAM container from the input stream. Returns an EOF container
     * when there is no more data or the EOF marker found.
     *
     * @param cramHeader
     * @param is
     * @return
     * @throws IOException
     */
    public static Container readContainer(CramHeader cramHeader, InputStream is) throws IOException {
        Container c = CramIO.readContainer(cramHeader.getMajorVersion(), is);
        if (c == null) {
            // this will cause System.exit(1):
            eofNotFound(cramHeader.getMajorVersion(), cramHeader.getMinorVersion());
            return CramIO.readContainer(new ByteArrayInputStream(
                    CramIO.ZERO_B_EOF_MARKER));
        }
        if (c.isEOF())
            log.debug("EOF marker found, file/stream is complete.");

        return c;
    }

    private static final boolean check(InputStream is) throws IOException {
        DataInputStream dis = new DataInputStream(is);
        byte[] bytes = new byte[CHECK.length];
        dis.readFully(bytes);

        boolean result = Arrays.equals(CHECK, bytes);

        if (!result)
            log.error("Expected %s but got %s.\n", new String(CHECK),
                    new String(bytes));

        return result;
    }

    private static final void check(OutputStream os) throws IOException {
        os.write(CHECK);
    }

    public static long issueZeroB_EOF_marker(OutputStream os)
            throws IOException {
        os.write(ZERO_B_EOF_MARKER);
        return ZERO_B_EOF_MARKER.length;
    }

    public static long issueZeroF_EOF_marker(OutputStream os)
            throws IOException {
        os.write(ZERO_F_EOF_MARKER);
        return ZERO_F_EOF_MARKER.length;
    }

    public static boolean hasZeroB_EOF_marker(SeekableStream s)
            throws IOException {
        byte[] tail = new byte[ZERO_B_EOF_MARKER.length];

        s.seek(s.length() - ZERO_B_EOF_MARKER.length);
        InputStreamUtils.readFully(s, tail, 0, tail.length);

        // relaxing the ITF8 hanging bits:
        tail[8] |= 0xf0;
        return Arrays.equals(tail, ZERO_B_EOF_MARKER);
    }

    public static boolean hasZeroF_EOF_marker(SeekableStream s)
            throws IOException {
        byte[] tail = new byte[ZERO_F_EOF_MARKER.length];

        s.seek(s.length() - ZERO_F_EOF_MARKER.length);
        InputStreamUtils.readFully(s, tail, 0, tail.length);

        // relaxing the ITF8 hanging bits:
        tail[8] |= 0xf0;
        return Arrays.equals(tail, ZERO_F_EOF_MARKER);
    }

    public static boolean hasZeroB_EOF_marker(File file) throws IOException {
        byte[] tail = new byte[ZERO_B_EOF_MARKER.length];

        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            raf.seek(file.length() - ZERO_B_EOF_MARKER.length);
            raf.readFully(tail);
        } catch (IOException e) {
            throw e;
        } finally {
            raf.close();
        }

        // relaxing the ITF8 hanging bits:
        tail[8] |= 0xf0;
        return Arrays.equals(tail, ZERO_B_EOF_MARKER);
    }

    public static long writeCramHeader(CramHeader h, OutputStream os) throws IOException {
        if (h.getMajorVersion() < 3)
            throw new RuntimeException("Deprecated CRAM version: "
                    + h.getMajorVersion());
        os.write("CRAM".getBytes("US-ASCII"));
        os.write(h.getMajorVersion());
        os.write(h.getMinorVersion());
        os.write(h.id);
        for (int i = h.id.length; i < 20; i++)
            os.write(0);

        long len = writeContainerForSamFileHeader(h.getMajorVersion(), h.getSamFileHeader(), os);

        return DEFINITION_LENGTH + len;
    }

    private static CramHeader readFormatDefinition(InputStream is, CramHeader header) throws IOException {
        for (byte b : CramHeader.magick) {
            if (b != is.read())
                throw new RuntimeException("Unknown file format.");
        }

        header.setMajorVersion((byte) is.read());
        header.setMinorVersion((byte) is.read());

        DataInputStream dis = new DataInputStream(is);
        dis.readFully(header.id);

        return header;
    }

    public static CramHeader readCramHeader(InputStream is) throws IOException {
        CramHeader header = new CramHeader();

        readFormatDefinition(is, header);

        header.setSamFileHeader(readSAMFileHeader(header, is));
        return header;
    }

    public static int writeContainer(int major, Container c, OutputStream os) throws IOException {

        long time1 = System.nanoTime();
        ExposedByteArrayOutputStream baos = new ExposedByteArrayOutputStream();

        Block block = new Block();
        block.setContentType(BlockContentType.COMPRESSION_HEADER);
        block.setContentId(0);
        block.setMethod(BlockCompressionMethod.RAW);
        byte[] bytes;
        try {
            bytes = c.h.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("This should have never happend.");
        }
        block.setRawContent(bytes);
        block.write(major, baos);
        c.blockCount = 1;

        List<Integer> landmarks = new ArrayList<Integer>();
        SliceIO sio = new SliceIO();
        for (int i = 0; i < c.slices.length; i++) {
            Slice s = c.slices[i];
            landmarks.add(baos.size());
            sio.write(major, s, baos);
            c.blockCount++;
            c.blockCount++;
            if (s.embeddedRefBlock != null)
                c.blockCount++;
            c.blockCount += s.external.size();
        }
        c.landmarks = new int[landmarks.size()];
        for (int i = 0; i < c.landmarks.length; i++)
            c.landmarks[i] = landmarks.get(i);

        c.containerByteSize = baos.size();
        calculateSliceOffsetsAndSizes(c);

        ContainerHeaderIO chio = new ContainerHeaderIO();
        int len = chio.writeContainerHeader(c, os);
        os.write(baos.getBuffer(), 0, baos.size());
        len += baos.size();

        long time2 = System.nanoTime();

        log.debug("CONTAINER WRITTEN: " + c.toString());
        c.writeTime = time2 - time1;

        return len;
    }

    /**
     * Reads next container from the stream.
     *
     * @param is the stream to read from
     * @return CRAM container or null if no more data
     * @throws IOException
     */
    public static Container readContainer(int major, InputStream is)
            throws IOException {
        return readContainer(major, is, 0, Integer.MAX_VALUE);
    }

    public static Container readContainer(InputStream is) throws IOException {
        return readContainer(2, is, 0, Integer.MAX_VALUE);
    }

    public static Container readContainerHeader(InputStream is)
            throws IOException {
        return readContainerHeader(2, is);
    }

    public static Container readContainerHeader(int major, InputStream is)
            throws IOException {
        Container c = new Container();
        ContainerHeaderIO chio = new ContainerHeaderIO();
        if (!chio.readContainerHeader(major, c, is)) {
            chio.readContainerHeader(c, new ByteArrayInputStream(
                    (major >= 3 ? ZERO_F_EOF_MARKER : ZERO_B_EOF_MARKER)));
            return c;
        }
        return c;
    }

    private static Container readContainer(int major, InputStream is,
                                           int fromSlice, int howManySlices) throws IOException {

        long time1 = System.nanoTime();
        Container c = readContainerHeader(major, is);
        if (c.isEOF())
            return c;

        Block chb = Block.readFromInputStream(major, is) ;
        if (chb.getContentType() != BlockContentType.COMPRESSION_HEADER)
            throw new RuntimeException("Content type does not match: " + chb.getContentType().name());
        c.h = new CompressionHeader() ;
        c.h.read(chb.getRawContent());

        howManySlices = Math.min(c.landmarks.length, howManySlices);

        if (fromSlice > 0)
            is.skip(c.landmarks[fromSlice]);

        SliceIO sio = new SliceIO();
        List<Slice> slices = new ArrayList<Slice>();
        for (int s = fromSlice; s < howManySlices - fromSlice; s++) {
            Slice slice = new Slice();
            slice.index = s;
            sio.readSliceHeadBlock(major, slice, is);
            sio.readSliceBlocks(major, slice, is);
            slices.add(slice);
        }

        c.slices = slices.toArray(new Slice[slices.size()]);

        calculateSliceOffsetsAndSizes(c);

        long time2 = System.nanoTime();

        log.debug("READ CONTAINER: " + c.toString());
        c.readTime = time2 - time1;

        return c;
    }

    private static void calculateSliceOffsetsAndSizes(Container c) {
        if (c.slices.length == 0)
            return;
        for (int i = 0; i < c.slices.length - 1; i++) {
            Slice s = c.slices[i];
            s.offset = c.landmarks[i];
            s.size = c.landmarks[i + 1] - s.offset;
        }
        Slice lastSlice = c.slices[c.slices.length - 1];
        lastSlice.offset = c.landmarks[c.landmarks.length - 1];
        lastSlice.size = c.containerByteSize - lastSlice.offset;
    }

    public static byte[] toByteArray(SAMFileHeader samFileHeader) {
        ExposedByteArrayOutputStream headerBodyOS = new ExposedByteArrayOutputStream();
        OutputStreamWriter w = new OutputStreamWriter(headerBodyOS);
        new SAMTextHeaderCodec().encode(w, samFileHeader);
        try {
            w.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(headerBodyOS.size());
        buf.flip();
        byte[] bytes = new byte[buf.limit()];
        buf.get(bytes);

        ByteArrayOutputStream headerOS = new ByteArrayOutputStream();
        try {
            headerOS.write(bytes);
            headerOS.write(headerBodyOS.getBuffer(), 0, headerBodyOS.size());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return headerOS.toByteArray();
    }

    private static long writeContainerForSamFileHeader(int major, SAMFileHeader samFileHeader, OutputStream os) throws IOException {
        byte[] data = toByteArray(samFileHeader);
        return writeContainerForSamFileHeaderData(major, data, 0,
                Math.max(1024, data.length + data.length / 2), os);
    }

    private static long writeContainerForSamFileHeaderData(int major, byte[] data, int offset, int len, OutputStream os)
            throws IOException {
        byte[] blockContent = new byte[len];
        System.arraycopy(data, 0, blockContent, offset, Math.min(data.length - offset, len));
        Block block = Block.buildNewFileHeaderBlock(blockContent) ;

        Container c = new Container();
        c.blockCount = 1;
        c.blocks = new Block[]{block};
        c.landmarks = new int[0];
        c.slices = new Slice[0];
        c.alignmentSpan = 0;
        c.alignmentStart = 0;
        c.bases = 0;
        c.globalRecordCounter = 0;
        c.nofRecords = 0;
        c.sequenceId = 0;

        ExposedByteArrayOutputStream baos = new ExposedByteArrayOutputStream();
        block.write(major, baos);
        c.containerByteSize = baos.size();

        ContainerHeaderIO chio = new ContainerHeaderIO();
        int containerHeaderByteSize = chio.writeContainerHeader(c, os);
        os.write(baos.getBuffer(), 0, baos.size());

        return containerHeaderByteSize + baos.size();
    }

    public static SAMFileHeader readSAMFileHeader(CramHeader header,
                                                  InputStream is) throws IOException {
        Container container = readContainerHeader(header.getMajorVersion(), is);
        Block b = null;
        {
            if (header.getMajorVersion() >= 3) {
                byte[] bytes = new byte[container.containerByteSize];
                InputStreamUtils.readFully(is, bytes, 0, bytes.length);
                b = Block.readFromInputStream(header.getMajorVersion(), new ByteArrayInputStream(bytes));
                // ignore the rest of the container
            } else {
                /*
                 * pending issue: container.containerByteSize is 2 bytes shorter
				 * then needed in the v21 test cram files.
				 */
                b = Block.readFromInputStream(header.getMajorVersion(), is);
            }
        }

        is = new ByteArrayInputStream(b.getRawContent());

        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++)
            buf.put((byte) is.read());
        buf.flip();
        int size = buf.asIntBuffer().get();

        DataInputStream dis = new DataInputStream(is);
        byte[] bytes = new byte[size];
        dis.readFully(bytes);

        BufferedLineReader r = new BufferedLineReader(new ByteArrayInputStream(
                bytes));
        SAMTextHeaderCodec codec = new SAMTextHeaderCodec();
        return codec.decode(r, new String(header.id));
    }

    public static boolean replaceCramHeader(File file, CramHeader newHeader) throws IOException {

        FileInputStream inputStream = new FileInputStream(file);
        CountingInputStream cis = new CountingInputStream(inputStream);

        CramHeader header = new CramHeader();
        readFormatDefinition(cis, header);

        if (header.getMajorVersion() != newHeader.getMajorVersion() && header.getMinorVersion() != newHeader.getMinorVersion()) {
            log.error(String.format("Cannot replace CRAM header because format versions differ: ", header.getMajorVersion(),
                    header.getMinorVersion(), newHeader.getMajorVersion(), header.getMinorVersion(), file.getAbsolutePath()));
            cis.close();
            return false;
        }

        long containerStart = cis.getCount();
        readContainerHeader(cis);
        Block b = Block.readFromInputStream(newHeader.getMajorVersion(), cis);
        cis.close();

        byte[] data = toByteArray(newHeader.getSamFileHeader());

        if (data.length > b.getRawContentSize()) {
            log.error("Failed to replace CRAM header because the new header is bigger.");
            return false;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream() ;
        writeContainerForSamFileHeaderData(newHeader.getMajorVersion(), data, 0, b.getRawContentSize(), baos) ;
        baos.close();

        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        raf.seek(containerStart);
        raf.write(baos.toByteArray());
        raf.close();

        return true;
    }

}
