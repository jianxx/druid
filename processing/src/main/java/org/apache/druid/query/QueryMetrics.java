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

package org.apache.druid.query;

import org.apache.druid.collections.bitmap.BitmapFactory;
import org.apache.druid.guice.annotations.ExtensionPoint;
import org.apache.druid.guice.annotations.PublicApi;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.query.filter.Filter;
import org.apache.druid.query.filter.FilterBundle;
import org.apache.druid.query.search.SearchQueryMetricsFactory;

import java.util.List;

/**
 * Abstraction wrapping {@link org.apache.druid.java.util.emitter.service.ServiceMetricEvent.Builder} and allowing to
 * control what metrics are actually emitted, what dimensions do they have, etc.
 *
 *
 * Goals of QueryMetrics
 * ---------------------
 *  1. Skipping or partial filtering of particular dimensions or metrics entirely. Implementation could leave the body
 *  of the corresponding method empty, or implement random filtering like:
 *  public void reportCpuTime(long timeNs)
 *  {
 *    if (ThreadLocalRandom.current().nextDouble() < 0.1) {
 *      super.reportCpuTime(timeNs);
 *    }
 *  }
 *
 *  2. Ability to add new dimensions and metrics, possibly expensive to compute, or expensive to process (long string
 *  values, high cardinality, etc.) and not to affect existing Druid installations, by skipping (see 1.) those
 *  dimensions and metrics entirely in the default QueryMetrics implementations. Users who need those expensive
 *  dimensions and metrics, could explicitly emit them in their own QueryMetrics.
 *
 *  3. Control over the time unit, in which time metrics are emitted. By default (see {@link DefaultQueryMetrics} and
 *  it's subclasses) it's milliseconds, but if queries are fast, it could be not precise enough.
 *
 *  4. Control over the dimension and metric names.
 *
 *  Here, "control" is provided to the operator of a Druid cluster, who would exercise that control through a
 *  site-specific extension adding XxxQueryMetricsFactory impl(s).
 *
 *
 * Types of methods in this interface
 * ----------------------------------
 *  1. Methods, pulling some dimensions from the query object. These methods are used to populate the metric before the
 *  query is run. These methods accept a single `QueryType query` parameter. {@link #query(Query)} calls all methods
 *  of this type, hence pulling all available information from the query object as dimensions.
 *
 *  2. Methods for setting dimensions, which become known in the process of the query execution or after the query is
 *  completed.
 *
 *  3. Methods to register metrics to be emitted later in bulk via {@link #emit(ServiceEmitter)}. These methods
 *  return this QueryMetrics object back for chaining. Names of these methods start with "report" prefix.
 *
 *
 * Implementors expectations
 * -------------------------
 * QueryMetrics is expected to be changed often, in every Druid release (including "patch" releases). Users who create
 * their custom implementations of QueryMetrics should be ready to fix the code of their QueryMetrics (implement new
 * methods) when they update Druid. Broken builds of custom extensions, containing custom QueryMetrics is the way to
 * notify users that Druid core "wants" to emit new dimension or metric, and the user handles them manually: if the new
 * dimension or metric is useful and not very expensive to process and store then emit, skip (see above Goals, 1.)
 * otherwise.
 *
 * <p>Despite this interface is annotated as {@link ExtensionPoint} and some of it's methods as {@link PublicApi}, it
 * may be changed in breaking ways even in minor releases.
 *
 * <p>If implementors of custom QueryMetrics don't want to fix builds on every Druid release (e. g. if they want to add
 * a single dimension to emitted events and don't want to alter other dimensions and emitted metrics), they could
 * inherit their custom QueryMetrics from {@link DefaultQueryMetrics} or query-specific default implementation class,
 * such as {@link org.apache.druid.query.topn.DefaultTopNQueryMetrics}. Those classes are guaranteed to stay around and
 * implement new methods, added to the QueryMetrics interface (or a query-specific subinterface). However, there is no
 * 100% guarantee of compatibility, because methods could not only be added to QueryMetrics, existing methods could also
 * be changed or removed.
 *
 * <p>QueryMetrics is designed for use from a single thread, implementations shouldn't care about thread-safety.
 *
 *
 * Adding new methods to QueryMetrics
 * ----------------------------------
 * 1. When adding a new method for setting a dimension, which could be pulled from the query object, always make them
 * accept a single `QueryType query` parameter, letting the implementations to do all the work of carving the dimension
 * value out of the query object.
 *
 * 2. When adding a new method for setting a dimension, which becomes known in the process of the query execution or
 * after the query is completed, design it so that as little work as possible is done for preparing arguments for this
 * method, and as much work as possible is done in the implementations of this method, if they decide to actually emit
 * this dimension.
 *
 * 3. When adding a new method for registering metrics, make it to accept the metric value in the smallest reasonable
 * unit (i. e. nanoseconds for time metrics, bytes for metrics of data size, etc.), allowing the implementations of
 * this method to round the value up to more coarse-grained units, if they don't need the maximum precision.
 *
 *
 * Making subinterfaces of QueryMetrics for emitting custom dimensions and/or metrics for specific query types
 * -----------------------------------------------------------------------------------------------------------
 * If a query type (e. g. {@link org.apache.druid.query.metadata.metadata.SegmentMetadataQuery} (it's runners) needs to
 * emit custom dimensions and/or metrics which doesn't make sense for all other query types, the following steps should
 * be executed:
 *
 *  1. Create `interface SegmentMetadataQueryMetrics extends QueryMetrics` (here and below "SegmentMetadata" is the
 *  query type) with additional methods (see "Adding new methods" section above).
 *
 *  2. Create `class DefaultSegmentMetadataQueryMetrics implements SegmentMetadataQueryMetrics`. This class should
 *  implement extra methods from SegmentMetadataQueryMetrics interfaces with empty bodies, AND DELEGATE ALL OTHER
 *  METHODS TO A QueryMetrics OBJECT, provided as a sole parameter in DefaultSegmentMetadataQueryMetrics constructor.
 *
 *  NOTE: query(), dataSource(), queryType(), interval(), hasFilters(), duration(), queryId(), sqlQueryId(), and
 *  context() methods or any "pre-query-execution-time" methods should either have a empty body or throw exception.
 *
 *  3. Create `interface SegmentMetadataQueryMetricsFactory` with a single method
 *  `SegmentMetadataQueryMetrics makeMetrics(SegmentMetadataQuery query);`.
 *
 *  4. Create `class DefaultSegmentMetadataQueryMetricsFactory implements SegmentMetadataQueryMetricsFactory`,
 *  which accepts {@link GenericQueryMetricsFactory} as injected constructor parameter, and implements makeMetrics() as
 *  `return new DefaultSegmentMetadataQueryMetrics(genericQueryMetricsFactory.makeMetrics(query));`
 *
 *  5. Inject and use SegmentMetadataQueryMetricsFactory instead of {@link GenericQueryMetricsFactory} in
 *  {@link org.apache.druid.query.metadata.SegmentMetadataQueryQueryToolChest}.
 *
 *  6. Establish injection of SegmentMetadataQueryMetricsFactory using config and provider method in
 *  QueryToolChestModule (see how it is done in QueryToolChestModule) for existing query types
 *  with custom metrics, e. g. {@link SearchQueryMetricsFactory}), if the query type
 *  belongs to the core druid-processing, e. g. SegmentMetadataQuery. If the query type defined in an extension, you
 *  can specify `binder.bind(ScanQueryMetricsFactory.class).to(DefaultScanQueryMetricsFactory.class)` in the extension's
 *  Guice module, if the query type is defined in an extension, e. g. ScanQuery. Or establish similar configuration,
 *  as for the core query types.
 *
 * This complex procedure is needed to ensure custom {@link GenericQueryMetricsFactory} specified by users still works
 * for the query type when query type decides to create their custom QueryMetrics subclass.
 *
 * {@link org.apache.druid.query.topn.TopNQueryMetrics}, {@link org.apache.druid.query.groupby.GroupByQueryMetrics}, and
 * {@link org.apache.druid.query.timeseries.TimeseriesQueryMetrics} are implemented differently, because they are
 * introduced at the same time as the whole QueryMetrics abstraction and their default implementations have to actually
 * emit more dimensions than the default generic QueryMetrics. So those subinterfaces shouldn't be taken as direct
 * examples for following the plan specified above.
 *
 * Refer {@link SearchQueryMetricsFactory} as an implementation example of this procedure.
 *
 * @param <QueryType>
 */
@ExtensionPoint
public interface QueryMetrics<QueryType extends Query<?>>
{

  /**
   * Pulls all information from the query object into dimensions of future metrics.
   */
  void query(QueryType query);

  /**
   * Sets {@link Query#getDataSource()} of the given query as dimension.
   */
  @PublicApi
  void dataSource(QueryType query);

  /**
   * Sets {@link Query#getType()} of the given query as dimension.
   */
  @PublicApi
  void queryType(QueryType query);

  /**
   * Sets {@link Query#getIntervals()} of the given query as dimension.
   */
  @PublicApi
  void interval(QueryType query);

  /**
   * Sets {@link Query#hasFilters()} of the given query as dimension.
   */
  @PublicApi
  void hasFilters(QueryType query);

  /**
   * Sets {@link Query#getDuration()} of the given query as dimension.
   */
  @PublicApi
  void duration(QueryType query);

  /**
   * Sets {@link Query#getId()} of the given query as dimension.
   */
  @PublicApi
  void queryId(QueryType query);

  /**
   * Sets id of the given query as dimension.
   */
  @PublicApi
  void queryId(@SuppressWarnings("UnusedParameters") String queryId);

  /**
   * Sets {@link Query#getSubQueryId()} of the given query as dimension.
   */
  @PublicApi
  void subQueryId(QueryType query);

  /**
   * Sets {@link Query#getSqlQueryId()} of the given query as dimension
   */
  @PublicApi
  void sqlQueryId(QueryType query);

  /**
   * Sets sqlQueryId as a dimension
   */
  @PublicApi
  void sqlQueryId(@SuppressWarnings("UnusedParameters") String sqlQueryId);

  /**
   * Sets {@link Query#getContext()} of the given query as dimension.
   */
  @PublicApi
  void context(QueryType query);

  void server(String host);

  void remoteAddress(String remoteAddress);

  void status(String status);

  void success(boolean success);

  void segment(String segmentIdentifier);

  /**
   * If a projection was used during segment processing, set its name as the projection dimension
   */
  void projection(String projection);

  /**
   * @deprecated use {@link #filterBundle(FilterBundle.BundleInfo)} instead to collect details about filters which were
   * used to construct {@link org.apache.druid.segment.BitmapOffset} or
   * {@link org.apache.druid.segment.vector.BitmapVectorOffset}.
   * This method will be removed in a future Druid release
   */
  @Deprecated
  @SuppressWarnings({"unreachable", "unused"})
  default void preFilters(List<Filter> preFilters)
  {
    // do nothing, nothing calls this
  }

  /**
   * @deprecated use {@link #filterBundle(FilterBundle.BundleInfo)} instead to collect details about filters which were
   * used as value matchers for {@link org.apache.druid.segment.FilteredOffset} or
   * {@link org.apache.druid.segment.vector.FilteredVectorOffset}
   * This method will be removed in a future Druid release
   */
  @Deprecated
  @SuppressWarnings({"unreachable", "unused"})
  default void postFilters(List<Filter> postFilters)
  {
    // do nothing, nothing calls this
  }

  default void filterBundle(FilterBundle.BundleInfo bundleInfo)
  {
    // Emit nothing by default.
  }

  /**
   * Sets identity of the requester for a query. See {@code AuthenticationResult}.
   */
  void identity(String identity);

  /**
   * Sets whether or not a segment scan has been vectorized. Generally expected to only be attached to segment-level
   * metrics, since at whole-query level we might have a mix of vectorized and non-vectorized segment scans.
   */
  void vectorized(boolean vectorized);

  /**
   * Sets broker merge parallelism, if parallel merges are enabled. This will only appear in broker level metrics. This
   * value is identical to the {@link #reportParallelMergeParallelism} metric value, but optionally also available as a
   * dimension.
   */
  void parallelMergeParallelism(int parallelism);

  /**
   * Creates a {@link BitmapResultFactory} which may record some information along bitmap construction from
   * {@link #filterBundle(FilterBundle.BundleInfo)}. The returned BitmapResultFactory may add some dimensions to this
   * QueryMetrics from it's {@link BitmapResultFactory#toImmutableBitmap(Object)} method. See
   * {@link BitmapResultFactory} Javadoc for more information.
   */
  BitmapResultFactory<?> makeBitmapResultFactory(BitmapFactory factory);

  /**
   * Registers "query time" metric.
   *
   * Measures the time between a Jetty thread starting to handle a query, and the response being fully written to
   * the response output stream. Does not include time spent waiting in a queue before the query runs.
   */
  QueryMetrics<QueryType> reportQueryTime(long timeNs);

  /**
   * Registers "query bytes" metric.
   *
   * Measures the total number of bytes written by the query server thread to the response output stream.
   *
   * Emitted once per query.
   */
  QueryMetrics<QueryType> reportQueryBytes(long byteCount);

  /**
   * Registers "segments queried count" metric.
   */
  QueryMetrics<QueryType> reportQueriedSegmentCount(long segmentCount);

  /**
   * Registers "wait time" metric.
   *
   * Measures the total time segment-processing runnables spent waiting for execution in the processing thread pool.
   *
   * Emitted once per segment.
   */
  QueryMetrics<QueryType> reportWaitTime(long timeNs);

  /**
   * Registers "segment time" metric.
   *
   * Measures the total wall-clock time spent operating on segments in processing threads.
   *
   * Emitted once per segment.
   */
  QueryMetrics<QueryType> reportSegmentTime(long timeNs);

  /**
   * Registers "segmentAndCache time" metric.
   *
   * Measures the total wall-clock time spent in processing threads, either operating on segments or retrieving items
   * from cache.
   *
   * Emitted once per segment.
   */
  QueryMetrics<QueryType> reportSegmentAndCacheTime(long timeNs);

  /**
   * Emits iff a given query polled the result-level cache and the success of that operation.
   */
  QueryMetrics<QueryType> reportResultCachePoll(boolean hit);

  /**
   * Registers "cpu time" metric.
   */
  QueryMetrics<QueryType> reportCpuTime(long timeNs);

  /**
   * Registers "time to first byte" metric.
   */
  QueryMetrics<QueryType> reportNodeTimeToFirstByte(long timeNs);

  /**
   * Registers "time that channel is unreadable (backpressure)" metric.
   */
  QueryMetrics<QueryType> reportBackPressureTime(long timeNs);

  /**
   * Registers "node time" metric.
   */
  QueryMetrics<QueryType> reportNodeTime(long timeNs);

  /**
   * Registers "node bytes" metric.
   */
  QueryMetrics<QueryType> reportNodeBytes(long byteCount);

  /**
   * Reports the time spent constructing bitmap from {@link #filterBundle(FilterBundle.BundleInfo)} of the query. Not
   * reported, if there are no indexes.
   */
  QueryMetrics<QueryType> reportBitmapConstructionTime(long timeNs);

  /**
   * Reports the total number of rows in the processed segment.
   */
  QueryMetrics<QueryType> reportSegmentRows(long numRows);

  /**
   * Reports the number of rows to scan in the segment after applying {@link #filterBundle(FilterBundle.BundleInfo)}.
   * If the are no indexes, this metric is equal to {@link #reportSegmentRows(long)}.
   */
  QueryMetrics<QueryType> reportPreFilteredRows(long numRows);

  /**
   * Reports number of parallel tasks the broker used to process the query during parallel merge. This value is
   * identical to the {@link #parallelMergeParallelism} dimension value, but optionally also available as a metric.
   */
  QueryMetrics<QueryType> reportParallelMergeParallelism(int parallelism);

  /**
   * Reports total number of input sequences processed by the broker during parallel merge.
   */
  QueryMetrics<QueryType> reportParallelMergeInputSequences(long numSequences);

  /**
   * Reports total number of input rows processed by the broker during parallel merge.
   */
  QueryMetrics<QueryType> reportParallelMergeInputRows(long numRows);

  /**
   * Reports broker total number of output rows after merging and combining input sequences (should be less than or
   * equal to the value supplied to {@link #reportParallelMergeInputRows}.
   */
  QueryMetrics<QueryType> reportParallelMergeOutputRows(long numRows);

  /**
   * Reports broker total number of fork join pool tasks required to complete query
   */
  QueryMetrics<QueryType> reportParallelMergeTaskCount(long numTasks);

  /**
   * Reports broker total CPU time in nanoseconds where fork join merge combine tasks were doing work
   */
  QueryMetrics<QueryType> reportParallelMergeTotalCpuTime(long timeNs);

  /**
   * Reports broker total "wall" time in nanoseconds from parallel merge start sequence creation to total
   * consumption.
   */
  QueryMetrics<QueryType> reportParallelMergeTotalTime(long timeNs);

  /**
   * Reports broker "wall" time in nanoseconds for the fastest parallel merge sequence partition to be 'initialized',
   * where 'initialized' is time to the first result batch is populated from data servers and merging can begin.
   *
   * Similar to query 'time to first byte' metrics, except is a composite of the whole group of data servers which are
   * present in the merge partition, which all must supply an initial result batch before merging can actually begin.
   */
  QueryMetrics<QueryType> reportParallelMergeFastestPartitionTime(long timeNs);

  /**
   * Reports broker "wall" time in nanoseconds for the slowest parallel merge sequence partition to be 'initialized',
   * where 'initialized' is time to the first result batch is populated from data servers and merging can begin.
   *
   * Similar to query 'time to first byte' metrics, except is a composite of the whole group of data servers which are
   * present in the merge partition, which all must supply an initial result batch before merging can actually begin.
   */
  QueryMetrics<QueryType> reportParallelMergeSlowestPartitionTime(long timeNs);

  /**
   * Emits all metrics, registered since the last {@code emit()} call on this QueryMetrics object.
   */
  void emit(ServiceEmitter emitter);
}
