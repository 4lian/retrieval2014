/**
 * Copyright 2010 The Apache Software Foundation
 *
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
package org.hbasene.index.create.mapred;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.mapred.IdentityTableMap;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RunningJob;
import org.hbasene.index.create.IndexConfiguration;


/**
 * Example table column indexing class. Runs a mapreduce job to index specified
 * table columns.
 * <ul>
 * <li>Each row is modeled as a Lucene document: row key is indexed in its
 * untokenized form, column name-value pairs are Lucene field name-value pairs.</li>
 * <li>A file passed on command line is used to populate an
 * {@link IndexConfiguration} which is used to set various Lucene parameters,
 * specify whether to optimize an index and which columns to index and/or store,
 * in tokenized or untokenized form, etc. For an example, see the
 * <code>createIndexConfContent</code> method in TestTableIndex</li>
 * <li>The number of reduce tasks decides the number of indexes (partitions).
 * The index(es) is stored in the output path of job configuration.</li>
 * <li>The index build process is done in the reduce phase. Users can use the
 * map phase to join rows from different tables or to pre-parse/analyze column
 * content, etc.</li>
 * </ul>
 */
@Deprecated
public class BuildTableIndex {
  private static final String USAGE = "Usage: BuildTableIndex "
      + "-m <numMapTasks> -r <numReduceTasks>\n  -indexConf <iconfFile> "
      + "-indexDir <indexDir>\n  -table <tableName> -columns <columnName1> "
      + "[<columnName2> ...]";

  private static void printUsage(String message) {
    System.err.println(message);
    System.err.println(USAGE);
    System.exit(-1);
  }

  /** default constructor */
  public BuildTableIndex() {
    super();
  }

  /**
   * Block and do not return until the job is complete.
   * 
   * @param args
   * @throws IOException
   */
  public void run(String[] args) throws IOException {
    if (args.length < 6) {
      printUsage("Too few arguments");
    }

    int numMapTasks = 1;
    int numReduceTasks = 1;
    String iconfFile = null;
    String indexDir = null;
    String tableName = null;
    StringBuilder columnNames = null;

    // parse args
    for (int i = 0; i < args.length - 1; i++) {
      if ("-m".equals(args[i])) {
        numMapTasks = Integer.parseInt(args[++i]);
      } else if ("-r".equals(args[i])) {
        numReduceTasks = Integer.parseInt(args[++i]);
      } else if ("-indexConf".equals(args[i])) {
        iconfFile = args[++i];
      } else if ("-indexDir".equals(args[i])) {
        indexDir = args[++i];
      } else if ("-table".equals(args[i])) {
        tableName = args[++i];
      } else if ("-columns".equals(args[i])) {
        columnNames = new StringBuilder(args[++i]);
        while (i + 1 < args.length && !args[i + 1].startsWith("-")) {
          columnNames.append(" ");
          columnNames.append(args[++i]);
        }
      } else {
        printUsage("Unsupported option " + args[i]);
      }
    }

    if (indexDir == null || tableName == null || columnNames == null) {
      printUsage("Index directory, table name and at least one column must "
          + "be specified");
    }

    Configuration conf = new HBaseConfiguration();
    if (iconfFile != null) {
      // set index configuration content from a file
      String content = readContent(iconfFile);
      IndexConfiguration iconf = new IndexConfiguration();
      // purely to validate, exception will be thrown if not valid
      iconf.addFromXML(content);
      conf.set("hbase.index.conf", content);
    }

    if (columnNames != null) {
      JobConf jobConf = createJob(conf, numMapTasks, numReduceTasks, indexDir,
          tableName, columnNames.toString());
      RunningJob runningJob = JobClient.runJob(jobConf);
      runningJob.waitForCompletion();
    }
  }

  /**
   * @param conf
   * @param numMapTasks
   * @param numReduceTasks
   * @param indexDir
   * @param tableName
   * @param columnNames
   * @return JobConf
   */
  public JobConf createJob(Configuration conf, int numMapTasks,
      int numReduceTasks, String indexDir, String tableName, String columnNames) {
    JobConf jobConf = new JobConf(conf, BuildTableIndex.class);
    jobConf.setJobName("build index for table " + tableName);
    jobConf.setNumMapTasks(numMapTasks);
    // number of indexes to partition into
    jobConf.setNumReduceTasks(numReduceTasks);

    // use identity map (a waste, but just as an example)
    IdentityTableMap.initJob(tableName, columnNames, IdentityTableMap.class,
        jobConf);

    // use IndexTableReduce to build a Lucene index
    jobConf.setReducerClass(IndexTableReduce.class);
    FileOutputFormat.setOutputPath(jobConf, new Path(indexDir));
    jobConf.setOutputFormat(IndexOutputFormat.class);
    jobConf.setJarByClass(BuildTableIndex.class);
    return jobConf;
  }

  /*
   * Read xml file of indexing configurations. The xml format is similar to
   * hbase-default.xml and hadoop-default.xml. For an example configuration, see
   * the <code>createIndexConfContent</code> method in TestTableIndex
   * 
   * @param fileName File to read.
   * 
   * @return XML configuration read from file
   * 
   * @throws IOException
   */
  private String readContent(String fileName) throws IOException {
    File file = new File(fileName);
    int length = (int) file.length();
    if (length == 0) {
      printUsage("Index configuration file " + fileName + " does not exist");
    }

    int bytesRead = 0;
    byte[] bytes = new byte[length];
    FileInputStream fis = new FileInputStream(file);

    try {
      // read entire file into content
      while (bytesRead < length) {
        int read = fis.read(bytes, bytesRead, length - bytesRead);
        if (read > 0) {
          bytesRead += read;
        } else {
          break;
        }
      }
    } finally {
      fis.close();
    }

    return new String(bytes, 0, bytesRead, HConstants.UTF8_ENCODING);
  }

  /**
   * @param args
   * @throws IOException
   */
  public static void main(String[] args) throws IOException {
    BuildTableIndex build = new BuildTableIndex();
    build.run(args);
  }
}