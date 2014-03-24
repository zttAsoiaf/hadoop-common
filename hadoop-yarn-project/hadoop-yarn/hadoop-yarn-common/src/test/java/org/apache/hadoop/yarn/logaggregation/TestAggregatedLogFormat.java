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

package org.apache.hadoop.yarn.logaggregation;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

import junit.framework.Assert;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.logaggregation.AggregatedLogFormat.LogKey;
import org.apache.hadoop.yarn.logaggregation.AggregatedLogFormat.LogReader;
import org.apache.hadoop.yarn.logaggregation.AggregatedLogFormat.LogValue;
import org.apache.hadoop.yarn.logaggregation.AggregatedLogFormat.LogWriter;
import org.apache.hadoop.yarn.util.BuilderUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestAggregatedLogFormat {

  private static final File testWorkDir = new File("target",
      "TestAggregatedLogFormat");
  private static final Configuration conf = new Configuration();
  private static final FileSystem fs;
  private static final char filler = 'x';
  private static final Log LOG = LogFactory
      .getLog(TestAggregatedLogFormat.class);

  static {
    try {
      fs = FileSystem.get(conf);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Before
  @After
  public void cleanupTestDir() throws Exception {
    Path workDirPath = new Path(testWorkDir.getAbsolutePath());
    LOG.info("Cleaning test directory [" + workDirPath + "]");
    fs.delete(workDirPath, true);
  }

  //Test for Corrupted AggregatedLogs. The Logs should not write more data
  //if Logvalue.write() is called and the application is still
  //appending to logs

  @Test
  public void testForCorruptedAggregatedLogs() throws Exception {
    Configuration conf = new Configuration();
    File workDir = new File(testWorkDir, "testReadAcontainerLogs1");
    Path remoteAppLogFile =
        new Path(workDir.getAbsolutePath(), "aggregatedLogFile");
    Path srcFileRoot = new Path(workDir.getAbsolutePath(), "srcFiles");
    ContainerId testContainerId = BuilderUtils.newContainerId(1, 1, 1, 1);
    Path t =
        new Path(srcFileRoot, testContainerId.getApplicationAttemptId()
            .getApplicationId().toString());
    Path srcFilePath = new Path(t, testContainerId.toString());

    long numChars = 950000;

    writeSrcFileAndALog(srcFilePath, "stdout", numChars, remoteAppLogFile,
       srcFileRoot, testContainerId);

    LogReader logReader = new LogReader(conf, remoteAppLogFile);
    LogKey rLogKey = new LogKey();
    DataInputStream dis = logReader.next(rLogKey);
    Writer writer = new StringWriter();
    try {
      LogReader.readAcontainerLogs(dis, writer);
    } catch (Exception e) {
      if(e.toString().contains("NumberFormatException")) {
        Assert.fail("Aggregated logs are corrupted.");
      }
    }
  }

  private void writeSrcFileAndALog(Path srcFilePath, String fileName, final long length,
      Path remoteAppLogFile, Path srcFileRoot, ContainerId testContainerId)
      throws Exception {
    File dir = new File(srcFilePath.toString());
    if (!dir.exists()) {
      if (!dir.mkdirs()) {
        throw new IOException("Unable to create directory : " + dir);
      }
    }

    File outputFile = new File(new File(srcFilePath.toString()), fileName);
    FileOutputStream os = new FileOutputStream(outputFile);
    final OutputStreamWriter osw = new OutputStreamWriter(os, "UTF8");
    final int ch = filler;

    UserGroupInformation ugi = UserGroupInformation.getCurrentUser();
    LogWriter logWriter = new LogWriter(conf, remoteAppLogFile, ugi);

    LogKey logKey = new LogKey(testContainerId);
    LogValue logValue =
        new LogValue(Collections.singletonList(srcFileRoot.toString()),
            testContainerId);

    final CountDownLatch latch = new CountDownLatch(1);

    Thread t = new Thread() {
      public void run() {
        try {
          for(int i=0; i < length/3; i++) {
              osw.write(ch);
          }

          latch.countDown();

          for(int i=0; i < (2*length)/3; i++) {
            osw.write(ch);
          }
          osw.close();
        } catch (IOException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }
    };
    t.start();

    //Wait till the osw is partially written
    //aggregation starts once the ows has completed 1/3rd of its work
    latch.await();

    //Aggregate The Logs
    logWriter.append(logKey, logValue);
    logWriter.close();
  }

  //Verify the output generated by readAContainerLogs(DataInputStream, Writer)
  @Test
  public void testReadAcontainerLogs1() throws Exception {
    Configuration conf = new Configuration();
    File workDir = new File(testWorkDir, "testReadAcontainerLogs1");
    Path remoteAppLogFile =
        new Path(workDir.getAbsolutePath(), "aggregatedLogFile");
    Path srcFileRoot = new Path(workDir.getAbsolutePath(), "srcFiles");
    ContainerId testContainerId = BuilderUtils.newContainerId(1, 1, 1, 1);
    Path t =
        new Path(srcFileRoot, testContainerId.getApplicationAttemptId()
            .getApplicationId().toString());
    Path srcFilePath = new Path(t, testContainerId.toString());

    int numChars = 80000;

    writeSrcFile(srcFilePath, "stdout", numChars);

    UserGroupInformation ugi = UserGroupInformation.getCurrentUser();
    LogWriter logWriter = new LogWriter(conf, remoteAppLogFile, ugi);

    LogKey logKey = new LogKey(testContainerId);
    LogValue logValue =
        new LogValue(Collections.singletonList(srcFileRoot.toString()),
            testContainerId);

    logWriter.append(logKey, logValue);
    logWriter.close();

    // make sure permission are correct on the file
    FileStatus fsStatus =  fs.getFileStatus(remoteAppLogFile);
    Assert.assertEquals("permissions on log aggregation file are wrong",  
      FsPermission.createImmutable((short) 0640), fsStatus.getPermission()); 

    LogReader logReader = new LogReader(conf, remoteAppLogFile);
    LogKey rLogKey = new LogKey();
    DataInputStream dis = logReader.next(rLogKey);
    Writer writer = new StringWriter();
    LogReader.readAcontainerLogs(dis, writer);
    
    String s = writer.toString();
    int expectedLength =
        "\n\nLogType:stdout".length() + ("\nLogLength:" + numChars).length()
            + "\nLog Contents:\n".length() + numChars;
    Assert.assertTrue("LogType not matched", s.contains("LogType:stdout"));
    Assert.assertTrue("LogLength not matched", s.contains("LogLength:" + numChars));
    Assert.assertTrue("Log Contents not matched", s.contains("Log Contents"));
    
    StringBuilder sb = new StringBuilder();
    for (int i = 0 ; i < numChars ; i++) {
      sb.append(filler);
    }
    String expectedContent = sb.toString();
    Assert.assertTrue("Log content incorrect", s.contains(expectedContent));
    
    Assert.assertEquals(expectedLength, s.length());
  }

  
  private void writeSrcFile(Path srcFilePath, String fileName, long length)
      throws IOException {
    File dir = new File(srcFilePath.toString());
    if (!dir.exists()) {
      if (!dir.mkdirs()) {
        throw new IOException("Unable to create directory : " + dir);
      }
    }
    File outputFile = new File(new File(srcFilePath.toString()), fileName);
    FileOutputStream os = new FileOutputStream(outputFile);
    OutputStreamWriter osw = new OutputStreamWriter(os, "UTF8");
    int ch = filler;
    for (int i = 0; i < length; i++) {
      osw.write(ch);
    }
    osw.close();
  }
}
