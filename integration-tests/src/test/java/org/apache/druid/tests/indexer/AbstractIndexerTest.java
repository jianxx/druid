/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.tests.indexer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import org.apache.commons.io.IOUtils;
import org.apache.druid.guice.annotations.Json;
import org.apache.druid.guice.annotations.Smile;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.testing.IntegrationTestingConfig;
import org.apache.druid.testing.clients.CoordinatorResourceTestClient;
import org.apache.druid.testing.clients.OverlordResourceTestClient;
import org.apache.druid.testing.clients.TaskResponseObject;
import org.apache.druid.testing.utils.DataLoaderHelper;
import org.apache.druid.testing.utils.ITRetryUtil;
import org.apache.druid.testing.utils.SqlTestQueryHelper;
import org.apache.druid.testing.utils.TestQueryHelper;
import org.joda.time.Interval;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class AbstractIndexerTest
{
  private static final Logger LOG = new Logger(AbstractIndexerTest.class);

  @Inject
  protected CoordinatorResourceTestClient coordinator;
  @Inject
  protected OverlordResourceTestClient indexer;
  @Inject
  @Json
  protected ObjectMapper jsonMapper;
  @Inject
  @Smile
  protected ObjectMapper smileMapper;
  @Inject
  protected TestQueryHelper queryHelper;
  @Inject
  protected SqlTestQueryHelper sqlQueryHelper;
  @Inject
  protected DataLoaderHelper dataLoaderHelper;

  @Inject
  protected IntegrationTestingConfig config;

  protected Closeable unloader(final String dataSource)
  {
    return () -> unloadAndKillData(dataSource);
  }

  protected void unloadAndKillData(final String dataSource)
  {
    // Get all failed task logs
    List<TaskResponseObject> allTasks = indexer.getCompleteTasksForDataSource(dataSource);
    for (TaskResponseObject task : allTasks) {
      if (task.getStatus().isFailure()) {
        LOG.info("------- START Found failed task logging for taskId=" + task.getId() + " -------");
        LOG.info("Start failed task log:");
        LOG.info(indexer.getTaskLog(task.getId()));
        LOG.info("End failed task log.");
        LOG.info("Start failed task errorMsg:");
        LOG.info(indexer.getTaskErrorMessage(task.getId()));
        LOG.info("End failed task errorMsg.");
        LOG.info("------- END Found failed task logging for taskId=" + task.getId() + " -------");
      }
    }

    List<String> intervals = coordinator.getSegmentIntervals(dataSource);

    // each element in intervals has this form:
    //   2015-12-01T23:15:00.000Z/2015-12-01T23:16:00.000Z
    // we'll sort the list (ISO dates have lexicographic order)
    // then delete segments from the 1st date in the first string
    // to the 2nd date in the last string
    Collections.sort(intervals);
    String first = intervals.get(0).split("/")[0];
    String last = intervals.get(intervals.size() - 1).split("/")[1];
    unloadAndKillData(dataSource, first, last);
  }

  protected String submitIndexTask(String indexTask, final String fullDatasourceName) throws Exception
  {
    // Wait for any existing kill tasks to complete before submitting new index task otherwise
    // kill tasks can fail with interval lock revoked.
    waitForAllTasksToCompleteForDataSource(fullDatasourceName);
    String taskSpec = getResourceAsString(indexTask);
    taskSpec = StringUtils.replace(taskSpec, "%%DATASOURCE%%", fullDatasourceName);
    taskSpec = StringUtils.replace(
        taskSpec,
        "%%SEGMENT_AVAIL_TIMEOUT_MILLIS%%",
        jsonMapper.writeValueAsString("0")
    );
    final String taskID = indexer.submitTask(taskSpec);
    LOG.info("TaskID for loading index task %s", taskID);

    return taskID;
  }

  protected void loadData(String indexTask, final String fullDatasourceName) throws Exception
  {
    final String taskID = submitIndexTask(indexTask, fullDatasourceName);
    indexer.waitUntilTaskCompletes(taskID);

    ITRetryUtil.retryUntilTrue(
        () -> coordinator.areSegmentsLoaded(fullDatasourceName),
        "Segment Load"
    );
  }

  private void unloadAndKillData(final String dataSource, String start, String end)
  {
    // Wait for any existing index tasks to complete before disabling the datasource otherwise
    // realtime tasks can get stuck waiting for handoff. https://github.com/apache/druid/issues/1729
    waitForAllTasksToCompleteForDataSource(dataSource);
    Interval interval = Intervals.of(start + "/" + end);
    coordinator.unloadSegmentsForDataSource(dataSource);
    ITRetryUtil.retryUntilFalse(
        () -> coordinator.areSegmentsLoaded(dataSource),
        "Segments are loaded"
    );
    coordinator.deleteSegmentsDataSource(dataSource, interval);
    waitForAllTasksToCompleteForDataSource(dataSource);
  }

  protected void waitForAllTasksToCompleteForDataSource(final String dataSource)
  {
    ITRetryUtil.retryUntilTrue(
        () -> indexer.getUncompletedTasksForDataSource(dataSource).isEmpty(),
        StringUtils.format("All tasks of [%s] have finished", dataSource)
    );
  }

  /**
   * Retries until segments have been loaded.
   */
  protected void waitForSegmentsToLoad(String dataSource)
  {
    ITRetryUtil.retryUntilTrue(
        () -> coordinator.areSegmentsLoaded(dataSource),
        "Segments are loaded"
    );
  }

  /**
   * Retries until the segment count is as expected.
   */
  protected void waitUntilDatasourceSegmentCountEquals(String dataSource, int numExpectedSegments)
  {
    ITRetryUtil.retryUntilEquals(
        () -> coordinator.getFullSegmentsMetadata(dataSource).size(),
        numExpectedSegments,
        "Segment count"
    );
  }

  /**
   * Retries until the total row count is as expected.
   */
  protected void waitUntilDatasourceRowCountEquals(String dataSource, long totalRows)
  {
    ITRetryUtil.retryUntilEquals(
        () -> queryHelper.countRows(
            dataSource,
            Intervals.ETERNITY,
            name -> new LongSumAggregatorFactory(name, "count")
        ),
        totalRows,
        "Total row count in datasource"
    );
  }

  public static String getResourceAsString(String file) throws IOException
  {
    try (final InputStream inputStream = getResourceAsStream(file)) {
      if (inputStream == null) {
        throw new ISE("Failed to load resource: [%s]", file);
      }
      return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
    }
  }

  public static InputStream getResourceAsStream(String resource)
  {
    return AbstractIndexerTest.class.getResourceAsStream(resource);
  }

  public static List<String> listResources(String dir) throws IOException
  {
    List<String> resources = new ArrayList<>();

    try (
        InputStream in = getResourceAsStream(dir);
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StringUtils.UTF8_STRING))
    ) {
      String resource;

      while ((resource = br.readLine()) != null) {
        resources.add(resource);
      }
    }

    return resources;
  }
}
