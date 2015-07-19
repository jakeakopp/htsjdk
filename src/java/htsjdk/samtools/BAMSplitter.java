package htsjdk.samtools;

import htsjdk.samtools.BAMBlockWriter;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.util.IOUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class BAMSplitter {
  public static void main(String[] args) {
    try {
    final int bufferSize = 0;
    String inputFilename = "../NA12878.chrom11.ILLUMINA.bwa.CEU.exome.20121211.bam";
    File inputFile = new File(inputFilename);
    final SamReader reader = SamReaderFactory.makeDefault().open(inputFile);
    File outputFile1 = new File("/tmp/NA12878.chrome11.1.bam");
    OutputStream os1 = IOUtil.maybeBufferOutputStream(new FileOutputStream(outputFile1, false), bufferSize);
    final BAMBlockWriter bw1 = new BAMBlockWriter(os1, outputFile1, false /*writeEndBlock*/);
    bw1.setSortOrder(SAMFileHeader.SortOrder.coordinate, true /*presorted*/);
    bw1.setHeader(reader.getFileHeader(), true /*writeHeader*/);
    
    File outputFile2 = new File("/tmp/NA12878.chrome11.2.bam");
    OutputStream os2 = IOUtil.maybeBufferOutputStream(new FileOutputStream(outputFile2, false), bufferSize);
    final BAMBlockWriter bw2 = new BAMBlockWriter(os2, outputFile2, false /*writeEndBlock*/);
    bw2.setSortOrder(SAMFileHeader.SortOrder.coordinate, true /*presorted*/);
    bw2.setHeader(reader.getFileHeader(), false /*writeHeader*/);

    File outputFile3 = new File("/tmp/NA12878.chrome11.3.bam");
    OutputStream os3 = IOUtil.maybeBufferOutputStream(new FileOutputStream(outputFile3, false), bufferSize);
    final BAMBlockWriter bw3 = new BAMBlockWriter(os3, outputFile3, true /*writeEndBlock*/);
    bw3.setSortOrder(SAMFileHeader.SortOrder.coordinate, true /*presorted*/);
    bw3.setHeader(reader.getFileHeader(), false /*writeHeader*/);

    int i = 0;
    for (final SAMRecord samRecord : reader) {
      if (i < 3000000) {
        bw1.addAlignment(samRecord);
      } else if (i < 6000000) {
        bw2.addAlignment(samRecord);
      } else {
        bw3.addAlignment(samRecord);
      }
      i++;
    }
    bw1.close();
    bw2.close();
    bw3.close();
    
    System.out.println("done, " + i + " records");
    } catch (FileNotFoundException e) {
      System.out.println("FileNotFoundException: " + e);
    }
  }
}
