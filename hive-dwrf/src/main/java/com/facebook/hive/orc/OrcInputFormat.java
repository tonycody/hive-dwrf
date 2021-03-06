//  Copyright (c) 2013, Facebook, Inc.  All rights reserved.

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.hive.orc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.io.InputFormatChecker;
import org.apache.hadoop.hive.serde2.ColumnProjectionUtils;
import org.apache.hadoop.hive.serde2.ReaderWriterProfiler;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;

import com.facebook.hive.orc.lazy.OrcLazyRow;

/**
 * A MapReduce/Hive input format for ORC files.
 */
public class OrcInputFormat  extends FileInputFormat<NullWritable, OrcLazyRow>
  implements InputFormatChecker {

  private static final Log LOG = LogFactory.getLog(OrcInputFormat.class);

  public static class OrcRecordReader
      implements RecordReader<NullWritable, OrcLazyRow> {
    private final com.facebook.hive.orc.RecordReader reader;
    private final long offset;
    private final long length;
    private float progress = 0.0f;
    private ObjectInspector objectInspector = null;

    OrcRecordReader(Reader file, Configuration conf,
                    long offset, long length) throws IOException {
      this.reader = file.rows(offset, length,
          findIncludedColumns(file.getTypes(), conf));
      this.offset = offset;
      this.length = length;
      this.objectInspector = file.getObjectInspector();
    }

    @Override
    public boolean next(NullWritable key, OrcLazyRow value) throws IOException {
      if (reader.hasNext()) {
        reader.next(value);
        progress = reader.getProgress();
        return true;
      } else {
        return false;
      }
    }

    @Override
    public NullWritable createKey() {
      return NullWritable.get();
    }

    @Override
    public OrcLazyRow createValue() {
      return reader.getReader();
    }

    @Override
    public long getPos() throws IOException {
      return offset + (long) (progress * length);
    }

    @Override
    public void close() throws IOException {
      reader.close();
    }

    @Override
    public float getProgress() throws IOException {
      return progress;
    }

    public ObjectInspector getObjectInspector() throws IOException {
      return objectInspector;
    }
  }

  public OrcInputFormat() {
    // just set a really small lower bound
    setMinSplitSize(16 * 1024);
  }

  /**
   * Recurse down into a type subtree turning on all of the sub-columns.
   * @param types the types of the file
   * @param result the global view of columns that should be included
   * @param typeId the root of tree to enable
   */
  private static void includeColumnRecursive(List<OrcProto.Type> types,
                                             boolean[] result,
                                             int typeId) {
    result[typeId] = true;
    OrcProto.Type type = types.get(typeId);
    int children = type.getSubtypesCount();
    for(int i=0; i < children; ++i) {
      includeColumnRecursive(types, result, type.getSubtypes(i));
    }
  }

  /**
   * Take the configuration and figure out which columns we need to include.
   * @param types the types of the file
   * @param conf the configuration
   * @return true for each column that should be included
   */
  private static boolean[] findIncludedColumns(List<OrcProto.Type> types,
                                               Configuration conf) {
    String includedStr =
        conf.get(ColumnProjectionUtils.READ_COLUMN_IDS_CONF_STR);
    if (includedStr == null || includedStr.trim().length() == 0) {
      return null;
    } else {
      int numColumns = types.size();
      boolean[] result = new boolean[numColumns];
      result[0] = true;
      OrcProto.Type root = types.get(0);
      List<Integer> included = ColumnProjectionUtils.getReadColumnIDs(conf);
      for(int i=0; i < root.getSubtypesCount(); ++i) {
        if (included.contains(i)) {
          includeColumnRecursive(types, result, root.getSubtypes(i));
        }
      }
      // if we are filtering at least one column, return the boolean array
      for(boolean include: result) {
        if (!include) {
          return result;
        }
      }
      return null;
    }
  }

  @Override
  public RecordReader<NullWritable, OrcLazyRow>
      getRecordReader(InputSplit inputSplit, JobConf conf,
                      Reporter reporter) throws IOException {
    ReaderWriterProfiler.setProfilerOptions(conf);
    FileSplit fileSplit = (FileSplit) inputSplit;
    Path path = fileSplit.getPath();
    FileSystem fs = path.getFileSystem(conf);
    reporter.setStatus(fileSplit.toString());

    try {
      return new OrcRecordReader(
          OrcFile.createReader(fs, path, conf),
          conf,
          fileSplit.getStart(),
          fileSplit.getLength());
    } catch (IndexOutOfBoundsException e) {
      /**
       * When a non ORC file is read by ORC reader, we get IndexOutOfBoundsException exception while
       * creating a reader. Caught that exception and checked the file header to see if the input
       * file was ORC or not. If its not ORC, throw a NotAnORCFileException with the file
       * attempted to be reading (thus helping to figure out which table-partition was being read).
       */
      checkIfORC(fs, path);
      throw new IOException("Failed to create record reader for file " + path , e);
    } catch (IOException e) {
      throw new IOException("Failed to create record reader for file " + path , e);
    }
  }

  /**
   * Reads the file header (first 40 bytes) and checks if the first three characters are 'ORC'.
   */
  private static void checkIfORC(FileSystem fs, Path path) throws IOException {
    // hardcoded to 40 because "SEQ-org.apache.hadoop.hive.ql.io.RCFile", the header, is of 40 chars
    final int buffLen = 40;
    final byte header[] = new byte[buffLen];
    final FSDataInputStream file = fs.open(path);
    final long fileLength = fs.getFileStatus(path).getLen();
    int sizeToBeRead = buffLen;
    if (buffLen > fileLength) {
      sizeToBeRead = (int)fileLength;
    }

    IOUtils.readFully(file, header, 0, sizeToBeRead);
    file.close();

    final String headerString = new String(header);
    if (headerString.startsWith("ORC")) {
      LOG.error("Error while parsing the footer of the file : " + path);
    } else {
      throw new NotAnORCFileException("Input file = " + path + " , header = " + headerString);
    }
  }

  @Override
  public boolean validateInput(FileSystem fs, HiveConf conf,
                               ArrayList<FileStatus> files
                              ) throws IOException {
    if (files.size() <= 0) {
      return false;
    }
    for (FileStatus file : files) {
      try {
        OrcFile.createReader(fs, file.getPath(), conf);
      } catch (IOException e) {
        return false;
      }
    }
    return true;
  }
}
