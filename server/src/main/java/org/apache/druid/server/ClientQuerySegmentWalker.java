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

package org.apache.druid.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.druid.client.CachingClusteredClient;
import org.apache.druid.client.DirectDruidClient;
import org.apache.druid.client.cache.Cache;
import org.apache.druid.client.cache.CacheConfig;
import org.apache.druid.error.DruidException;
import org.apache.druid.frame.allocation.ArenaMemoryAllocatorFactory;
import org.apache.druid.frame.write.UnsupportedColumnTypeException;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.java.util.emitter.service.ServiceMetricEvent;
import org.apache.druid.query.DataSource;
import org.apache.druid.query.DruidMetrics;
import org.apache.druid.query.FluentQueryRunner;
import org.apache.druid.query.FrameBasedInlineDataSource;
import org.apache.druid.query.FrameSignaturePair;
import org.apache.druid.query.GenericQueryMetricsFactory;
import org.apache.druid.query.GlobalTableDataSource;
import org.apache.druid.query.InlineDataSource;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryContext;
import org.apache.druid.query.QueryContexts;
import org.apache.druid.query.QueryDataSource;
import org.apache.druid.query.QueryLogic;
import org.apache.druid.query.QueryLogicCompatToolChest;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryRunnerFactoryConglomerate;
import org.apache.druid.query.QuerySegmentWalker;
import org.apache.druid.query.QueryToolChest;
import org.apache.druid.query.ResourceLimitExceededException;
import org.apache.druid.query.ResultLevelCachingQueryRunner;
import org.apache.druid.query.ResultSerializationMode;
import org.apache.druid.query.RetryQueryRunner;
import org.apache.druid.query.RetryQueryRunnerConfig;
import org.apache.druid.query.SegmentDescriptor;
import org.apache.druid.query.TableDataSource;
import org.apache.druid.query.planning.ExecutionVertex;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.segment.join.JoinableFactory;
import org.apache.druid.server.initialization.ServerConfig;
import org.apache.druid.server.metrics.SubqueryCountStatsProvider;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Query handler for the Broker processes (see CliBroker).
 * <p>
 * This class is responsible for:
 * <p>
 * 1) Running queries on the cluster using its 'clusterClient'
 * 2) Running queries locally (when all datasources are global) using its 'localClient'
 * 3) Inlining subqueries if necessary, in service of the above two goals
 */
public class ClientQuerySegmentWalker implements QuerySegmentWalker
{

  private static final Logger log = new Logger(ClientQuerySegmentWalker.class);
  private static final int FRAME_SIZE = 8_000_000;
  public static final String ROWS_COUNT_METRIC = "subquery/rows";
  public static final String BYTES_COUNT_METRIC = "subquery/bytes";

  private final ServiceEmitter emitter;
  private final QuerySegmentWalker clusterClient;
  private final QuerySegmentWalker localClient;
  private final QueryRunnerFactoryConglomerate conglomerate;
  private final JoinableFactory joinableFactory;
  private final RetryQueryRunnerConfig retryConfig;
  private final ObjectMapper objectMapper;
  private final ServerConfig serverConfig;
  private final Cache cache;
  private final CacheConfig cacheConfig;
  private final SubqueryGuardrailHelper subqueryGuardrailHelper;
  private final SubqueryCountStatsProvider subqueryStatsProvider;
  private final GenericQueryMetricsFactory genericQueryMetricsFactory;

  public ClientQuerySegmentWalker(
      ServiceEmitter emitter,
      QuerySegmentWalker clusterClient,
      QuerySegmentWalker localClient,
      QueryRunnerFactoryConglomerate conglomerate,
      JoinableFactory joinableFactory,
      RetryQueryRunnerConfig retryConfig,
      ObjectMapper objectMapper,
      ServerConfig serverConfig,
      Cache cache,
      CacheConfig cacheConfig,
      SubqueryGuardrailHelper subqueryGuardrailHelper,
      SubqueryCountStatsProvider subqueryStatsProvider,
      GenericQueryMetricsFactory genericQueryMetricsFactory
  )
  {
    this.emitter = emitter;
    this.clusterClient = clusterClient;
    this.localClient = localClient;
    this.conglomerate = conglomerate;
    this.joinableFactory = joinableFactory;
    this.retryConfig = retryConfig;
    this.objectMapper = objectMapper;
    this.serverConfig = serverConfig;
    this.cache = cache;
    this.cacheConfig = cacheConfig;
    this.subqueryGuardrailHelper = subqueryGuardrailHelper;
    this.subqueryStatsProvider = subqueryStatsProvider;
    this.genericQueryMetricsFactory = genericQueryMetricsFactory;
  }

  @Inject
  ClientQuerySegmentWalker(
      ServiceEmitter emitter,
      CachingClusteredClient clusterClient,
      LocalQuerySegmentWalker localClient,
      QueryRunnerFactoryConglomerate conglomerate,
      JoinableFactory joinableFactory,
      RetryQueryRunnerConfig retryConfig,
      ObjectMapper objectMapper,
      ServerConfig serverConfig,
      Cache cache,
      CacheConfig cacheConfig,
      SubqueryGuardrailHelper subqueryGuardrailHelper,
      SubqueryCountStatsProvider subqueryStatsProvider,
      GenericQueryMetricsFactory genericQueryMetricsFactory
  )
  {
    this(
        emitter,
        clusterClient,
        (QuerySegmentWalker) localClient,
        conglomerate,
        joinableFactory,
        retryConfig,
        objectMapper,
        serverConfig,
        cache,
        cacheConfig,
        subqueryGuardrailHelper,
        subqueryStatsProvider,
        genericQueryMetricsFactory
    );
  }

  @Override
  public <T> QueryRunner<T> getQueryRunnerForIntervals(Query<T> query, final Iterable<Interval> intervals)
  {
    QueryContext context = query.context();
    final int maxSubqueryRows = context.getMaxSubqueryRows(serverConfig.getMaxSubqueryRows());
    final String maxSubqueryMemoryString = context.getMaxSubqueryMemoryBytes(serverConfig.getMaxSubqueryBytes());
    final long maxSubqueryMemory = subqueryGuardrailHelper.convertSubqueryLimitStringToLong(maxSubqueryMemoryString);
    final boolean useNestedForUnknownTypeInSubquery = context
        .isUseNestedForUnknownTypeInSubquery(serverConfig.isuseNestedForUnknownTypeInSubquery());

    final QueryLogic queryExecutor = conglomerate.getQueryLogic(query);
    if (queryExecutor != null) {
      query = query.withOverriddenContext(
          ImmutableMap.of(
              QueryContexts.USE_NESTED_FOR_UNKNOWN_TYPE_IN_SUBQUERY,
              useNestedForUnknownTypeInSubquery
          )
      );
      return (QueryRunner<T>) queryExecutor.entryPoint(query, this);
    }

    final QueryToolChest<T, Query<T>> toolChest = conglomerate.getToolChest(query);

    // transform TableDataSource to GlobalTableDataSource when eligible
    // before further transformation to potentially inline

    Query<T> newQuery = ResourceIdPopulatingQueryRunner.populateResourceId(query);

    // Populate the subquery ids of the subquery id present in the main query
    newQuery = newQuery.withDataSource(generateSubqueryIds(
        query.getDataSource(),
        query.getId(),
        query.getSqlQueryId(),
        query.context().getString(QueryContexts.QUERY_RESOURCE_ID)
    ));

    final DataSource freeTradeDataSource = globalizeIfPossible(newQuery.getDataSource());

    // do an inlining dry run to see if any inlining is necessary, without actually running the queries.
    final DataSource inlineDryRun = inlineIfNecessary(
        freeTradeDataSource,
        toolChest,
        new AtomicInteger(),
        new AtomicLong(),
        new AtomicBoolean(false),
        maxSubqueryRows,
        maxSubqueryMemory,
        useNestedForUnknownTypeInSubquery,
        true
    );

    if (!canRunQueryUsingClusterWalker(query.withDataSource(inlineDryRun))
        && !canRunQueryUsingLocalWalker(query.withDataSource(inlineDryRun))) {
      // Dry run didn't go well.
      throw new ISE("Cannot handle subquery structure for dataSource: %s", query.getDataSource());
    }
    // Now that we know the structure is workable, actually do the inlining (if necessary).
    AtomicLong memoryLimitAcc = new AtomicLong(0);
    DataSource maybeInlinedDataSource = inlineIfNecessary(
        freeTradeDataSource,
        toolChest,
        new AtomicInteger(),
        memoryLimitAcc,
        new AtomicBoolean(false),
        maxSubqueryRows,
        maxSubqueryMemory,
        useNestedForUnknownTypeInSubquery,
        false
    );
    newQuery = newQuery.withDataSource(maybeInlinedDataSource);

    log.debug("Memory used by subqueries of query [%s] is [%d]", query, memoryLimitAcc.get());

    if (canRunQueryUsingLocalWalker(newQuery)) {
      // No need to decorate since LocalQuerySegmentWalker does its own.
      return new QuerySwappingQueryRunner<>(
          localClient.getQueryRunnerForIntervals(newQuery, intervals),
          query,
          newQuery
      );
    } else if (canRunQueryUsingClusterWalker(newQuery)) {
      // Note: clusterClient.getQueryRunnerForIntervals() can return an empty sequence if there is no segment
      // to query, but this is not correct when there's a right or full outer join going on.
      // See https://github.com/apache/druid/issues/9229 for details.
      return new QuerySwappingQueryRunner<>(
          decorateClusterRunner(newQuery, clusterClient.getQueryRunnerForIntervals(newQuery, intervals)),
          query,
          newQuery
      );
    } else {
      // We don't expect to ever get here, because the logic earlier in this method should have rejected any query
      // that can't be run with either the local or cluster walkers. If this message ever shows up it is a bug.
      throw new ISE("Inlined query could not be run");
    }
  }

  @Override
  public <T> QueryRunner<T> getQueryRunnerForSegments(final Query<T> query, final Iterable<SegmentDescriptor> specs)
  {

    // Inlining isn't done for segments-based queries, but we still globalify the table datasources if possible
    Query<T> freeTradeQuery = query.withDataSource(globalizeIfPossible(query.getDataSource()));

    freeTradeQuery = ResourceIdPopulatingQueryRunner.populateResourceId(freeTradeQuery);

    if (canRunQueryUsingClusterWalker(query)) {
      return new QuerySwappingQueryRunner<>(
          decorateClusterRunner(freeTradeQuery, clusterClient.getQueryRunnerForSegments(freeTradeQuery, specs)),
          query,
          freeTradeQuery
      );
    } else {
      // We don't expect end-users to see this message, since it only happens when specific segments are requested;
      // this is not typical end-user behavior.
      throw new ISE(
          "Cannot run query on specific segments (must be table-based; outer query, if present, must be "
          + "handleable by the query toolchest natively)");
    }
  }

  /**
   * Checks if a query can be handled wholly by {@link #localClient}. Assumes that it is a
   * {@link LocalQuerySegmentWalker} or something that behaves similarly.
   */
  private <T> boolean canRunQueryUsingLocalWalker(Query<T> query)
  {
    ExecutionVertex ev = ExecutionVertex.of(query);
    return ev.canRunQueryUsingLocalWalker();
  }

  /**
   * Checks if a query can be handled wholly by {@link #clusterClient}. Assumes that it is a
   * {@link CachingClusteredClient} or something that behaves similarly.
   */
  private <T> boolean canRunQueryUsingClusterWalker(Query<T> query)
  {
    ExecutionVertex ev = ExecutionVertex.of(query);
    return ev.canRunQueryUsingClusterWalker();
  }

  private DataSource globalizeIfPossible(
      final DataSource dataSource
  )
  {
    if (dataSource instanceof TableDataSource) {
      GlobalTableDataSource maybeGlobal = new GlobalTableDataSource(((TableDataSource) dataSource).getName());
      if (joinableFactory.isDirectlyJoinable(maybeGlobal)) {
        return maybeGlobal;
      }
      return dataSource;
    } else {
      List<DataSource> currentChildren = dataSource.getChildren();
      List<DataSource> newChildren = new ArrayList<>(currentChildren.size());
      for (DataSource child : currentChildren) {
        newChildren.add(globalizeIfPossible(child));
      }
      return dataSource.withChildren(newChildren);
    }
  }

  /**
   * Replace QueryDataSources with InlineDataSources when necessary and possible. "Necessary" is defined as:
   * <p>
   * 1) For outermost subqueries: inlining is necessary if the toolchest cannot handle it.
   * 2) For all other subqueries (e.g. those nested under a join): inlining is always necessary.
   *
   * @param dataSource                  datasource to process.
   * @param toolChestIfOutermost        if provided, and if the provided datasource is a {@link QueryDataSource}, this method
   *                                    will consider whether the toolchest can handle a subquery on the datasource using
   *                                    {@link QueryToolChest#canPerformSubquery}. If the toolchest can handle it, then it will
   *                                    not be inlined. See {@link org.apache.druid.query.groupby.GroupByQueryQueryToolChest}
   *                                    for an example of a toolchest that can handle subqueries.
   * @param subqueryRowLimitAccumulator an accumulator for tracking the number of accumulated rows in all subqueries
   *                                    for a particular master query
   * @param maxSubqueryRows             Max rows that all the subqueries generated by a master query can have, combined
   * @param useNestedForUnknownTypeInSubquery if true, then the null types are replaced by nested types while converting the results to frames
   * @param dryRun                      if true, does not actually execute any subqueries, but will inline empty result sets.
   */
  @SuppressWarnings({"rawtypes", "unchecked"}) // Subquery, toolchest, runner handling all use raw types
  private <T> DataSource inlineIfNecessary(
      final DataSource dataSource,
      @Nullable final QueryToolChest toolChestIfOutermost,
      final AtomicInteger subqueryRowLimitAccumulator,
      final AtomicLong subqueryMemoryLimitAccumulator,
      final AtomicBoolean cannotMaterializeToFrames,
      final int maxSubqueryRows,
      final long maxSubqueryMemory,
      final boolean useNestedForUnknownTypeInSubquery,
      final boolean dryRun
  )
  {
    if (dataSource instanceof QueryDataSource) {
      // This datasource is a subquery.
      final Query subQuery = ((QueryDataSource) dataSource).getQuery();
      final QueryToolChest toolChest = conglomerate.getToolChest(subQuery);
      final QueryLogic subQueryLogic = conglomerate.getQueryLogic(subQuery);

      if (subQueryLogic != null) {
        final Sequence<?> queryResults;

        if (dryRun) {
          queryResults = Sequences.empty();
        } else {
          Query subQueryWithSerialization = subQuery.withOverriddenContext(
              Collections.singletonMap(
                  ResultSerializationMode.CTX_SERIALIZATION_PARAMETER,
                  ClientQuerySegmentWalkerUtils.getLimitType(maxSubqueryMemory, cannotMaterializeToFrames.get())
                      .serializationMode()
                      .toString()
              )
          );
          queryResults = subQueryLogic
              .entryPoint(subQueryWithSerialization, this)
              .run(QueryPlus.wrap(subQueryWithSerialization), DirectDruidClient.makeResponseContextForQuery());
        }

        return toInlineDataSource(
            subQuery,
            queryResults,
            (QueryToolChest) new QueryLogicCompatToolChest(
                subQuery.getResultRowSignature(),
                genericQueryMetricsFactory
            ),
            subqueryRowLimitAccumulator,
            subqueryMemoryLimitAccumulator,
            cannotMaterializeToFrames,
            maxSubqueryRows,
            maxSubqueryMemory,
            useNestedForUnknownTypeInSubquery,
            subqueryStatsProvider,
            !dryRun,
            emitter
        );
      }

      if (toolChestIfOutermost != null && toolChestIfOutermost.canPerformSubquery(subQuery)) {
        // Strip outer queries that are handleable by the toolchest, and inline subqueries that may be underneath
        // them (e.g. subqueries nested under a join).
        final Stack<DataSource> stack = new Stack<>();

        DataSource current = dataSource;
        while (current instanceof QueryDataSource) {
          stack.push(current);
          current = Iterables.getOnlyElement(current.getChildren());
        }

        if (current instanceof QueryDataSource) {
          throw new ISE("Got a QueryDataSource[%s], should've walked it away in the loop above.", current);
        }
        current = inlineIfNecessary(
            current,
            null,
            subqueryRowLimitAccumulator,
            subqueryMemoryLimitAccumulator,
            cannotMaterializeToFrames,
            maxSubqueryRows,
            maxSubqueryMemory,
            useNestedForUnknownTypeInSubquery,
            dryRun
        );

        while (!stack.isEmpty()) {
          current = stack.pop().withChildren(Collections.singletonList(current));
        }

        if (!(current instanceof QueryDataSource)) {
          throw new ISE("Should have a QueryDataSource, but got[%s] instead", current);
        }
        if (toolChest.canPerformSubquery(((QueryDataSource) current).getQuery())) {
          return current;
        } else {
          // Something happened during inlining that means the toolchest is no longer able to handle this subquery.
          // We need to consider inlining it.
          return inlineIfNecessary(
              current,
              toolChestIfOutermost,
              subqueryRowLimitAccumulator,
              subqueryMemoryLimitAccumulator,
              cannotMaterializeToFrames,
              maxSubqueryRows,
              maxSubqueryMemory,
              useNestedForUnknownTypeInSubquery,
              dryRun
          );
        }
      } else if (canRunQueryUsingLocalWalker(subQuery) || canRunQueryUsingClusterWalker(subQuery)) {
        // Subquery needs to be inlined. Assign it a subquery id and run it.

        final Sequence<?> queryResults;

        if (dryRun) {
          queryResults = Sequences.empty();
        } else {
          Query subQueryWithSerialization = subQuery.withOverriddenContext(
              Collections.singletonMap(
                  ResultSerializationMode.CTX_SERIALIZATION_PARAMETER,
                  ClientQuerySegmentWalkerUtils.getLimitType(maxSubqueryMemory, cannotMaterializeToFrames.get())
                                               .serializationMode()
                                               .toString()
              )
          );

          queryResults = subQueryWithSerialization
              .getRunner(this)
              .run(QueryPlus.wrap(subQueryWithSerialization), DirectDruidClient.makeResponseContextForQuery());
        }

        return toInlineDataSource(
            subQuery,
            queryResults,
            toolChest,
            subqueryRowLimitAccumulator,
            subqueryMemoryLimitAccumulator,
            cannotMaterializeToFrames,
            maxSubqueryRows,
            maxSubqueryMemory,
            useNestedForUnknownTypeInSubquery,
            subqueryStatsProvider,
            !dryRun,
            emitter
        );
      } else {
        // Cannot inline subquery. Attempt to inline one level deeper, and then try again.

        List<DataSource> newDataSources = new ArrayList<>();
        for (DataSource ds : dataSource.getChildren()) {
          newDataSources.add(
              inlineIfNecessary(
                  ds,
                  null,
                  subqueryRowLimitAccumulator,
                  subqueryMemoryLimitAccumulator,
                  cannotMaterializeToFrames,
                  maxSubqueryRows,
                  maxSubqueryMemory,
                  useNestedForUnknownTypeInSubquery,
                  dryRun
              )
          );
        }
        return inlineIfNecessary(
            dataSource.withChildren(
                newDataSources
            ),
            toolChestIfOutermost,
            subqueryRowLimitAccumulator,
            subqueryMemoryLimitAccumulator,
            cannotMaterializeToFrames,
            maxSubqueryRows,
            maxSubqueryMemory,
            useNestedForUnknownTypeInSubquery,
            dryRun
        );
      }
    } else {
      // Not a query datasource. Walk children and see if there's anything to inline.
      return dataSource.withChildren(
          dataSource.getChildren()
                    .stream()
                    .map(child -> inlineIfNecessary(
                        child,
                        null,
                        subqueryRowLimitAccumulator,
                        subqueryMemoryLimitAccumulator,
                        cannotMaterializeToFrames,
                        maxSubqueryRows,
                        maxSubqueryMemory,
                        useNestedForUnknownTypeInSubquery,
                        dryRun
                    ))
                    .collect(Collectors.toList())
      );
    }
  }

  /**
   * Decorate query runners created by {@link #clusterClient}, adding result caching, result merging, metric
   * emission, etc. Not to be used on runners from {@link #localClient}, since we expect it to do this kind
   * of decoration to itself.
   *
   * @param query             the query
   * @param baseClusterRunner runner from {@link #clusterClient}
   */
  private <T> QueryRunner<T> decorateClusterRunner(Query<T> query, QueryRunner<T> baseClusterRunner)
  {
    final QueryToolChest<T, Query<T>> toolChest = conglomerate.getToolChest(query);

    final SetAndVerifyContextQueryRunner<T> baseRunner = new SetAndVerifyContextQueryRunner<>(
        serverConfig,
        new RetryQueryRunner<>(
            baseClusterRunner,
            clusterClient::getQueryRunnerForSegments,
            retryConfig,
            objectMapper
        )
    );

    return FluentQueryRunner
        .create(baseRunner, toolChest)
        .applyPreMergeDecoration()
        .mergeResults(false)
        .applyPostMergeDecoration()
        .emitCPUTimeMetric(emitter)
        .postProcess(
            objectMapper.convertValue(
                query.context().getString("postProcessing"),
                new TypeReference<>() {}
            )
        )
        .map(
            runner ->
                new ResultLevelCachingQueryRunner<>(
                    runner,
                    toolChest,
                    query,
                    objectMapper,
                    cache,
                    cacheConfig,
                    emitter
                )
        );
  }

  /**
   * This method returns the datasource by populating all the {@link QueryDataSource} with correct nesting level and
   * sibling order of all the subqueries that are present.
   * It also plumbs parent query's id and sql id in case the subqueries don't have it set by default
   *
   * @param rootDataSource        Datasource whose subqueries need to be populated
   * @param parentQueryId         Parent Query's ID, can be null if it does not need to update this in the subqueries
   * @param parentSqlQueryId      Parent Query's SQL Query ID, can be null if it does not need to update this in the subqueries
   * @param parentQueryResourceId Parent Query's Query Resource ID
   * @return DataSource populated with the subqueries
   */
  @VisibleForTesting
  public static DataSource generateSubqueryIds(
      DataSource rootDataSource,
      @Nullable final String parentQueryId,
      @Nullable final String parentSqlQueryId,
      final String parentQueryResourceId
  )
  {
    Queue<DataSource> queue = new ArrayDeque<>();
    queue.add(rootDataSource);

    // Performs BFS on the datasource tree to find the nesting level, and the sibling order of the query datasource
    Map<QueryDataSource, Pair<Integer, Integer>> queryDataSourceToSubqueryIds = new HashMap<>();
    int level = 1;
    while (!queue.isEmpty()) {
      int size = queue.size();
      int siblingOrder = 1;
      for (int i = 0; i < size; ++i) {
        DataSource currentDataSource = queue.poll();
        if (currentDataSource == null) { // Shouldn't be encountered
          continue;
        }
        if (currentDataSource instanceof QueryDataSource) {
          queryDataSourceToSubqueryIds.put((QueryDataSource) currentDataSource, new Pair<>(level, siblingOrder));
          ++siblingOrder;
        }
        queue.addAll(currentDataSource.getChildren());
      }
      ++level;
    }
    /*
    Returns the datasource by populating all the subqueries with the id generated in the map above.
    Implemented in a separate function since the methods on datasource and queries return a new datasource/query
     */
    return insertSubqueryIds(rootDataSource, queryDataSourceToSubqueryIds, parentQueryId, parentSqlQueryId, parentQueryResourceId);
  }

  /**
   * To be used in conjunction with {@code generateSubqueryIds()} method. This does the actual task of populating the
   * query's id, subQueryId and sqlQueryId
   *
   * @param currentDataSource            The datasource to be populated with the subqueries
   * @param queryDataSourceToSubqueryIds Map of the datasources to their level and sibling order
   * @param parentQueryId                Parent query's id
   * @param parentSqlQueryId             Parent query's sqlQueryId
   * @param parentQueryResourceId        Parent query's resource Id
   * @return Populates the subqueries from the map
   */
  private static DataSource insertSubqueryIds(
      DataSource currentDataSource,
      Map<QueryDataSource, Pair<Integer, Integer>> queryDataSourceToSubqueryIds,
      @Nullable final String parentQueryId,
      @Nullable final String parentSqlQueryId,
      final String parentQueryResourceId
  )
  {
    if (currentDataSource instanceof QueryDataSource
        && queryDataSourceToSubqueryIds.containsKey(currentDataSource)) {
      QueryDataSource queryDataSource = (QueryDataSource) currentDataSource;
      Pair<Integer, Integer> nestingInfo = queryDataSourceToSubqueryIds.get(queryDataSource);
      String subQueryId = nestingInfo.lhs + "." + nestingInfo.rhs;
      Query<?> query = queryDataSource.getQuery();

      if (StringUtils.isEmpty(query.getSubQueryId())) {
        query = query.withSubQueryId(subQueryId);
      }

      if (StringUtils.isEmpty(query.getId()) && StringUtils.isNotEmpty(parentQueryId)) {
        query = query.withId(parentQueryId);
      }

      if (StringUtils.isEmpty(query.getSqlQueryId()) && StringUtils.isNotEmpty(parentSqlQueryId)) {
        query = query.withSqlQueryId(parentSqlQueryId);
      }

      query.withOverriddenContext(Collections.singletonMap(
          QueryContexts.QUERY_RESOURCE_ID,
          parentQueryResourceId
      ));

      currentDataSource = new QueryDataSource(query);
    }
    return currentDataSource.withChildren(currentDataSource.getChildren()
                                                           .stream()
                                                           .map(childDataSource -> insertSubqueryIds(
                                                               childDataSource,
                                                               queryDataSourceToSubqueryIds,
                                                               parentQueryId,
                                                               parentSqlQueryId,
                                                               parentQueryResourceId
                                                           ))
                                                           .collect(Collectors.toList()));
  }

  /**
   *
   * Convert the results of a particular query into a materialized (List-based) InlineDataSource.
   *
   * @param query            the query
   * @param toolChest        toolchest for the query
   * @param limitAccumulator an accumulator for tracking the number of accumulated rows in all subqueries for a
   *                         particular master query
   * @param limit            user-configured limit. If negative, will be treated as {@link Integer#MAX_VALUE}.
   *                         If zero, this method will throw an error immediately.
   * @param memoryLimit      User configured byte limit.
   * @param useNestedForUnknownTypeInSubquery Uses nested json for unknown types when materializing subquery results
   * @param subqueryStatsProvider Statistics about the subquery materialization
   * @param emitMetrics      Flag to control if the metrics need to be emitted while materializing. The metrics are omitted
   *                         when we are performing a dry run of the query to avoid double reporting the same metric incorrectly
   * @param emitter          Metrics emitter
   * @return                 Inlined datasource represented by the provided results
   * @throws ResourceLimitExceededException if the limit is exceeded
   */
  private static <T, QueryType extends Query<T>> DataSource toInlineDataSource(
      final QueryType query,
      final Sequence<T> queryResults,
      final QueryToolChest<T, QueryType> toolChest,
      final AtomicInteger limitAccumulator,
      final AtomicLong memoryLimitAccumulator,
      final AtomicBoolean cannotMaterializeToFrames,
      final int limit,
      long memoryLimit,
      final boolean useNestedForUnknownTypeInSubquery,
      final SubqueryCountStatsProvider subqueryStatsProvider,
      final boolean emitMetrics,
      final ServiceEmitter emitter
  )
  {
    final int rowLimitToUse = limit < 0 ? Integer.MAX_VALUE : limit;

    DataSource dataSource;

    switch (ClientQuerySegmentWalkerUtils.getLimitType(memoryLimit, cannotMaterializeToFrames.get())) {
      case ROW_LIMIT:
        if (limitAccumulator.get() >= rowLimitToUse) {
          subqueryStatsProvider.incrementQueriesExceedingRowLimit();
          throw ResourceLimitExceededException.withMessage(rowLimitExceededMessage(rowLimitToUse));
        }
        subqueryStatsProvider.incrementSubqueriesWithRowLimit();
        dataSource = materializeResultsAsArray(
            query,
            queryResults,
            toolChest,
            limitAccumulator,
            limit,
            subqueryStatsProvider,
            emitMetrics,
            emitter
        );
        break;
      case MEMORY_LIMIT:
        if (memoryLimitAccumulator.get() >= memoryLimit) {
          subqueryStatsProvider.incrementQueriesExceedingByteLimit();
          throw ResourceLimitExceededException.withMessage(byteLimitExceededMessage(memoryLimit));
        }
        Optional<DataSource> maybeDataSource = materializeResultsAsFrames(
            query,
            queryResults,
            toolChest,
            limitAccumulator,
            memoryLimitAccumulator,
            memoryLimit,
            useNestedForUnknownTypeInSubquery,
            subqueryStatsProvider,
            emitMetrics,
            emitter
        );
        if (!maybeDataSource.isPresent()) {
          cannotMaterializeToFrames.set(true);
          // Check if the previous row limit accumulator has exceeded the memory results
          if (limitAccumulator.get() >= rowLimitToUse) {
            subqueryStatsProvider.incrementQueriesExceedingRowLimit();
            throw ResourceLimitExceededException.withMessage(rowLimitExceededMessage(rowLimitToUse));
          }
          subqueryStatsProvider.incrementSubqueriesWithRowLimit();
          subqueryStatsProvider.incrementSubqueriesFallingBackToRowLimit();
          dataSource = materializeResultsAsArray(
              query,
              queryResults,
              toolChest,
              limitAccumulator,
              limit,
              subqueryStatsProvider,
              emitMetrics,
              emitter
          );
        } else {
          subqueryStatsProvider.incrementSubqueriesWithByteLimit();
          dataSource = maybeDataSource.get();
        }
        break;
      default:
        throw DruidException.defensive("Only row based and memory based limiting is supported");
    }
    return dataSource;
  }

  /**
   * This method materializes the query results as Frames. The method defaults back to materializing as rows in case
   * one cannot materialize the results as frames
   */
  private static <T, QueryType extends Query<T>> Optional<DataSource> materializeResultsAsFrames(
      final QueryType query,
      final Sequence<T> results,
      final QueryToolChest<T, QueryType> toolChest,
      final AtomicInteger limitAccumulator,
      final AtomicLong memoryLimitAccumulator,
      final long memoryLimit,
      final boolean useNestedForUnknownTypeInSubquery,
      final SubqueryCountStatsProvider subqueryStatsProvider,
      final boolean emitMetrics,
      final ServiceEmitter emitter
  )
  {
    boolean startedAccumulating = false;
    try {
      Optional<Sequence<FrameSignaturePair>> framesOptional = toolChest.resultsAsFrames(
          query,
          results,
          new ArenaMemoryAllocatorFactory(FRAME_SIZE),
          useNestedForUnknownTypeInSubquery
      );

      if (!framesOptional.isPresent()) {
        throw DruidException.defensive("Unable to materialize the results as frames. Defaulting to materializing the results as rows");
      }

      Sequence<FrameSignaturePair> frames = framesOptional.get();
      List<FrameSignaturePair> frameSignaturePairs = new ArrayList<>();

      startedAccumulating = true;

      final int initialSubqueryRows = limitAccumulator.get();
      final long initialSubqueryBytes = memoryLimitAccumulator.get();
      frames.forEach(
          frame -> {
            limitAccumulator.addAndGet(frame.getFrame().numRows());
            if (memoryLimitAccumulator.addAndGet(frame.getFrame().numBytes()) >= memoryLimit) {
              subqueryStatsProvider.incrementQueriesExceedingByteLimit();
              throw ResourceLimitExceededException.withMessage(byteLimitExceededMessage(memoryLimit));

            }
            frameSignaturePairs.add(frame);
          }
      );
      if (emitMetrics) {
        emitter.emit(
            ServiceMetricEvent.builder()
                              .setDimension(DruidMetrics.ID, query.getId())
                              .setDimension(DruidMetrics.SUBQUERY_ID, query.getSubQueryId())
                              .setMetric(ROWS_COUNT_METRIC, limitAccumulator.get() - initialSubqueryRows)
        );

        emitter.emit(
            ServiceMetricEvent.builder()
                              .setDimension(DruidMetrics.ID, query.getId())
                              .setDimension(DruidMetrics.SUBQUERY_ID, query.getSubQueryId())
                              .setMetric(BYTES_COUNT_METRIC, memoryLimitAccumulator.get() - initialSubqueryBytes)
        );
      }
      return Optional.of(new FrameBasedInlineDataSource(frameSignaturePairs, toolChest.resultArraySignature(query)));
    }
    catch (UnsupportedColumnTypeException e) {
      subqueryStatsProvider.incrementSubqueriesFallingBackDueToUnsufficientTypeInfo();
      log.debug(e, "Type info in signature insufficient to materialize rows as frames.");
      return Optional.empty();
    }
    catch (ResourceLimitExceededException e) {
      throw e;
    }
    catch (Exception e) {
      if (startedAccumulating) {
        // If we have opened the resultSequence, we can't fall back safely as the resultSequence might hold some resources
        // that we release on exception, and we need to throw the exception to disable the 'maxSubqueryBytes' configuration
        throw DruidException.defensive()
                            .build(
                                e,
                                "Unable to materialize the results as frames for estimating the byte footprint. "
                                + "Please disable the 'maxSubqueryBytes' by setting it to 'disabled' in the query context or removing it altogether "
                                + "from the query context and/or the server config."
                            );
      } else {
        return Optional.empty();
      }
    }
  }

  /**
   * This method materializes the query results as {@code List<Objects[]>}
   */
  private static <T, QueryType extends Query<T>> DataSource materializeResultsAsArray(
      final QueryType query,
      final Sequence<T> results,
      final QueryToolChest<T, QueryType> toolChest,
      final AtomicInteger limitAccumulator,
      final int limit,
      final SubqueryCountStatsProvider subqueryStatsProvider,
      boolean emitMetrics,
      final ServiceEmitter emitter
  )
  {
    final int rowLimitToUse = limit < 0 ? Integer.MAX_VALUE : limit;
    final RowSignature signature = toolChest.resultArraySignature(query);

    final ArrayList<Object[]> resultList = new ArrayList<>();

    final int initialSubqueryRows = limitAccumulator.get();
    toolChest.resultsAsArrays(query, results).accumulate(
        resultList,
        (acc, in) -> {
          if (limitAccumulator.getAndIncrement() >= rowLimitToUse) {
            subqueryStatsProvider.incrementQueriesExceedingRowLimit();
            throw ResourceLimitExceededException.withMessage(rowLimitExceededMessage(rowLimitToUse));
          }
          acc.add(in);
          return acc;
        }
    );
    if (emitMetrics) {
      emitter.emit(
          ServiceMetricEvent.builder()
                            .setDimension(DruidMetrics.ID, query.getId())
                            .setDimension(DruidMetrics.SUBQUERY_ID, query.getSubQueryId())
                            .setMetric(ROWS_COUNT_METRIC, limitAccumulator.get() - initialSubqueryRows)
      );
    }
    return InlineDataSource.fromIterable(resultList, signature);
  }

  private static String byteLimitExceededMessage(final long memoryLimit)
  {
    return org.apache.druid.java.util.common.StringUtils.format(
    "Cannot issue the query, subqueries generated results beyond maximum[%d] bytes. Increase the "
    + "JVM's memory or set the '%s' in the query context to increase the space allocated for subqueries to "
    + "materialize their results. Manually alter the value carefully as it can cause the broker to go out of memory.",
        memoryLimit,
        QueryContexts.MAX_SUBQUERY_BYTES_KEY
    );
  }

  private static String rowLimitExceededMessage(final int rowLimitUsed)
  {
    return org.apache.druid.java.util.common.StringUtils.format(
        "Cannot issue the query, subqueries generated results beyond maximum[%d] rows. Try setting the "
        + "'%s' in the query context to '%s' for enabling byte based limit, which chooses an optimal limit based on "
        + "memory size and result's heap usage or manually configure the values of either '%s' or '%s' in the query "
        + "context. Manually alter the value carefully as it can cause the broker to go out of memory.",
        rowLimitUsed,
        QueryContexts.MAX_SUBQUERY_BYTES_KEY,
        SubqueryGuardrailHelper.AUTO_LIMIT_VALUE,
        QueryContexts.MAX_SUBQUERY_BYTES_KEY,
        QueryContexts.MAX_SUBQUERY_ROWS_KEY
    );
  }
}
