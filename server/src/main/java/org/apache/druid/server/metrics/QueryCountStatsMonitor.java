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

package org.apache.druid.server.metrics;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import org.apache.druid.collections.BlockingPool;
import org.apache.druid.discovery.NodeRole;
import org.apache.druid.guice.annotations.LoadScope;
import org.apache.druid.guice.annotations.Merging;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.java.util.emitter.service.ServiceMetricEvent;
import org.apache.druid.java.util.metrics.AbstractMonitor;
import org.apache.druid.java.util.metrics.KeyedDiff;

import java.nio.ByteBuffer;
import java.util.Map;

@LoadScope(roles = {
    NodeRole.BROKER_JSON_NAME,
    NodeRole.HISTORICAL_JSON_NAME,
    NodeRole.ROUTER_JSON_NAME,
    NodeRole.INDEXER_JSON_NAME,
    NodeRole.PEON_JSON_NAME
})
public class QueryCountStatsMonitor extends AbstractMonitor
{
  private final KeyedDiff keyedDiff = new KeyedDiff();
  private final QueryCountStatsProvider statsProvider;
  private final BlockingPool<ByteBuffer> mergeBufferPool;
  private final boolean emitMergeBufferPendingRequests;

  @Inject
  public QueryCountStatsMonitor(
      QueryCountStatsProvider statsProvider,
      MonitorsConfig monitorsConfig,
      @Merging BlockingPool<ByteBuffer> mergeBufferPool
  )
  {
    this.statsProvider = statsProvider;
    this.mergeBufferPool = mergeBufferPool;
    this.emitMergeBufferPendingRequests = !monitorsConfig.getMonitors().contains(GroupByStatsMonitor.class);
  }

  @Override
  public boolean doMonitor(ServiceEmitter emitter)
  {
    final ServiceMetricEvent.Builder builder = new ServiceMetricEvent.Builder();
    final long successfulQueryCount = statsProvider.getSuccessfulQueryCount();
    final long failedQueryCount = statsProvider.getFailedQueryCount();
    final long interruptedQueryCount = statsProvider.getInterruptedQueryCount();
    final long timedOutQueryCount = statsProvider.getTimedOutQueryCount();

    Map<String, Long> diff = keyedDiff.to(
        "queryCountStats",
        ImmutableMap.of(
            "query/count", successfulQueryCount + failedQueryCount + interruptedQueryCount + timedOutQueryCount,
            "query/success/count", successfulQueryCount,
            "query/failed/count", failedQueryCount,
            "query/interrupted/count", interruptedQueryCount,
            "query/timeout/count", timedOutQueryCount
        )
    );
    if (diff != null) {
      for (Map.Entry<String, Long> diffEntry : diff.entrySet()) {
        emitter.emit(builder.setMetric(diffEntry.getKey(), diffEntry.getValue()));
      }
    }

    if (emitMergeBufferPendingRequests) {
      long pendingQueries = this.mergeBufferPool.getPendingRequests();
      emitter.emit(builder.setMetric("mergeBuffer/pendingRequests", pendingQueries));
    }

    return true;
  }
}
