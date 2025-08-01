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

package org.apache.druid.indexer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.PeekingIterator;
import com.google.common.io.Closeables;
import org.apache.druid.collections.CombiningIterable;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.Rows;
import org.apache.druid.data.input.StringTuple;
import org.apache.druid.data.input.impl.DimensionSchema;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.indexer.partitions.DimensionRangePartitionsSpec;
import org.apache.druid.indexer.partitions.SingleDimensionPartitionsSpec;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.timeline.partition.DimensionRangeShardSpec;
import org.apache.druid.timeline.partition.ShardSpec;
import org.apache.druid.timeline.partition.SingleDimensionShardSpec;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.InvalidJobConfException;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskInputOutputContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.joda.time.chrono.ISOChronology;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Determines appropriate ShardSpecs for a job by determining whether or not partitioning is necessary, and if so,
 * choosing the best dimension that satisfies the criteria:
 * <p/>
 * <ul>
 * <li>Must have exactly one value per row.</li>
 * <li>Must not generate oversized partitions. A dimension with N rows having the same value will necessarily
 * put all those rows in the same partition, and that partition may be much larger than the target size.</li>
 * </ul>
 * <p/>
 * "Best" means a very high cardinality dimension, or, if none exist, the dimension that minimizes variation of
 * segment size relative to the target.
 */
public class DeterminePartitionsJob implements Jobby
{
  private static final Logger log = new Logger(DeterminePartitionsJob.class);

  private static final Joiner TAB_JOINER = HadoopDruidIndexerConfig.TAB_JOINER;
  private static final Joiner COMMA_JOINER = Joiner.on(",").useForNull("");

  private final HadoopDruidIndexerConfig config;

  private Job groupByJob;

  private String failureCause;

  DeterminePartitionsJob(
      HadoopDruidIndexerConfig config
  )
  {
    this.config = config;
  }

  @Override
  public boolean run()
  {
    try {
      /*
       * Group by (timestamp, dimensions) so we can correctly count dimension values as they would appear
       * in the final segment.
       */

      if (!(config.getPartitionsSpec() instanceof DimensionRangePartitionsSpec)) {
        throw new ISE(
            "DeterminePartitionsJob can only be run for DimensionRangePartitionsSpec, partitionSpec found [%s]",
            config.getPartitionsSpec()
        );
      }

      final DimensionRangePartitionsSpec partitionsSpec =
          (DimensionRangePartitionsSpec) config.getPartitionsSpec();

      if (!partitionsSpec.isAssumeGrouped()) {
        groupByJob = Job.getInstance(
            new Configuration(),
            StringUtils.format("%s-determine_partitions_groupby-%s", config.getDataSource(), config.getIntervals())
        );

        JobHelper.injectSystemProperties(groupByJob.getConfiguration(), config);
        config.addJobProperties(groupByJob);

        groupByJob.setMapperClass(DeterminePartitionsGroupByMapper.class);
        groupByJob.setMapOutputKeyClass(BytesWritable.class);
        groupByJob.setMapOutputValueClass(NullWritable.class);
        groupByJob.setCombinerClass(DeterminePartitionsGroupByReducer.class);
        groupByJob.setReducerClass(DeterminePartitionsGroupByReducer.class);
        groupByJob.setOutputKeyClass(BytesWritable.class);
        groupByJob.setOutputValueClass(NullWritable.class);
        groupByJob.setOutputFormatClass(SequenceFileOutputFormat.class);
        JobHelper.setupClasspath(
            JobHelper.distributedClassPath(config.getWorkingPath()),
            JobHelper.distributedClassPath(config.makeIntermediatePath()),
            groupByJob
        );

        config.addInputPaths(groupByJob);
        config.intoConfiguration(groupByJob);
        FileOutputFormat.setOutputPath(groupByJob, config.makeGroupedDataDir());

        groupByJob.submit();
        log.info("Job %s submitted, status available at: %s", groupByJob.getJobName(), groupByJob.getTrackingURL());

        // Store the jobId in the file
        if (groupByJob.getJobID() != null) {
          JobHelper.writeJobIdToFile(config.getHadoopJobIdFileName(), groupByJob.getJobID().toString());
        }


        try {
          if (!groupByJob.waitForCompletion(true)) {
            log.error("Job failed: %s", groupByJob.getJobID());
            failureCause = Utils.getFailureMessage(groupByJob, HadoopDruidIndexerConfig.JSON_MAPPER);
            return false;
          }
        }
        catch (IOException ioe) {
          if (!Utils.checkAppSuccessForJobIOException(ioe, groupByJob, config.isUseYarnRMJobStatusFallback())) {
            throw ioe;
          }
        }
      } else {
        log.info("Skipping group-by job.");
      }

      /*
       * Read grouped data and determine appropriate partitions.
       */
      final Job dimSelectionJob = Job.getInstance(
          new Configuration(),
          StringUtils.format("%s-determine_partitions_dimselection-%s", config.getDataSource(), config.getIntervals())
      );

      dimSelectionJob.getConfiguration().set("io.sort.record.percent", "0.19");

      JobHelper.injectSystemProperties(dimSelectionJob.getConfiguration(), config);
      config.addJobProperties(dimSelectionJob);

      if (!partitionsSpec.isAssumeGrouped()) {
        // Read grouped data from the groupByJob.
        dimSelectionJob.setMapperClass(DeterminePartitionsDimSelectionPostGroupByMapper.class);
        dimSelectionJob.setInputFormatClass(SequenceFileInputFormat.class);
        FileInputFormat.addInputPath(dimSelectionJob, config.makeGroupedDataDir());
      } else {
        // Directly read the source data, since we assume it's already grouped.
        dimSelectionJob.setMapperClass(DeterminePartitionsDimSelectionAssumeGroupedMapper.class);
        config.addInputPaths(dimSelectionJob);
      }

      SortableBytes.useSortableBytesAsMapOutputKey(dimSelectionJob, DeterminePartitionsDimSelectionPartitioner.class);
      dimSelectionJob.setMapOutputValueClass(Text.class);
      dimSelectionJob.setCombinerClass(DeterminePartitionsDimSelectionCombiner.class);
      dimSelectionJob.setReducerClass(DeterminePartitionsDimSelectionReducer.class);
      dimSelectionJob.setOutputKeyClass(BytesWritable.class);
      dimSelectionJob.setOutputValueClass(Text.class);
      dimSelectionJob.setOutputFormatClass(DeterminePartitionsDimSelectionOutputFormat.class);
      dimSelectionJob.setNumReduceTasks(Iterators.size(config.getGranularitySpec().sortedBucketIntervals().iterator()));
      JobHelper.setupClasspath(
          JobHelper.distributedClassPath(config.getWorkingPath()),
          JobHelper.distributedClassPath(config.makeIntermediatePath()),
          dimSelectionJob
      );

      config.intoConfiguration(dimSelectionJob);
      FileOutputFormat.setOutputPath(dimSelectionJob, config.makeIntermediatePath());

      dimSelectionJob.submit();
      log.info(
          "Job %s submitted, status available at: %s",
          dimSelectionJob.getJobName(),
          dimSelectionJob.getTrackingURL()
      );

      // Store the jobId in the file
      if (dimSelectionJob.getJobID() != null) {
        JobHelper.writeJobIdToFile(config.getHadoopJobIdFileName(), dimSelectionJob.getJobID().toString());
      }


      try {
        if (!dimSelectionJob.waitForCompletion(true)) {
          log.error("Job failed: %s", dimSelectionJob.getJobID().toString());
          failureCause = Utils.getFailureMessage(dimSelectionJob, HadoopDruidIndexerConfig.JSON_MAPPER);
          return false;
        }
      }
      catch (IOException ioe) {
        if (!Utils.checkAppSuccessForJobIOException(ioe, dimSelectionJob, config.isUseYarnRMJobStatusFallback())) {
          throw ioe;
        }
      }

      /*
       * Load partitions determined by the previous job.
       */

      log.info("Job completed, loading up partitions for intervals[%s].", config.getSegmentGranularIntervals());
      FileSystem fileSystem = null;
      Map<Long, List<HadoopyShardSpec>> shardSpecs = new TreeMap<>();
      int shardCount = 0;
      for (Interval segmentGranularity : config.getSegmentGranularIntervals()) {
        final Path partitionInfoPath = config.makeSegmentPartitionInfoPath(segmentGranularity);
        if (fileSystem == null) {
          fileSystem = partitionInfoPath.getFileSystem(dimSelectionJob.getConfiguration());
        }
        if (Utils.exists(dimSelectionJob, fileSystem, partitionInfoPath)) {
          List<ShardSpec> specs = HadoopDruidIndexerConfig.JSON_MAPPER.readValue(
              Utils.openInputStream(dimSelectionJob, partitionInfoPath), new TypeReference<>() {}
          );

          List<HadoopyShardSpec> actualSpecs = Lists.newArrayListWithExpectedSize(specs.size());
          for (int i = 0; i < specs.size(); ++i) {
            actualSpecs.add(new HadoopyShardSpec(specs.get(i), shardCount++));
            log.info("DateTime[%s], partition[%d], spec[%s]", segmentGranularity, i, actualSpecs.get(i));
          }

          shardSpecs.put(segmentGranularity.getStartMillis(), actualSpecs);
        } else {
          log.info("Path[%s] didn't exist!?", partitionInfoPath);
        }
      }
      config.setShardSpecs(shardSpecs);

      return true;
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Map<String, Object> getStats()
  {
    if (groupByJob == null) {
      return null;
    }

    try {
      Counters jobCounters = groupByJob.getCounters();

      return TaskMetricsUtils.makeIngestionRowMetrics(
          jobCounters.findCounter(HadoopDruidIndexerConfig.IndexJobCounters.ROWS_PROCESSED_COUNTER).getValue(),
          jobCounters.findCounter(HadoopDruidIndexerConfig.IndexJobCounters.ROWS_PROCESSED_WITH_ERRORS_COUNTER)
                     .getValue(),
          jobCounters.findCounter(HadoopDruidIndexerConfig.IndexJobCounters.ROWS_UNPARSEABLE_COUNTER).getValue(),
          jobCounters.findCounter(HadoopDruidIndexerConfig.IndexJobCounters.ROWS_THROWN_AWAY_COUNTER).getValue()
      );
    }
    catch (IllegalStateException ise) {
      log.debug("Couldn't get counters due to job state");
      return null;
    }
    catch (Exception e) {
      log.debug(e, "Encountered exception in getStats().");
      return null;
    }
  }

  @Nullable
  @Override
  public String getErrorMessage()
  {
    return failureCause;
  }

  private static DeterminePartitionsJobSampler createSampler(HadoopDruidIndexerConfig config)
  {
    HadoopTuningConfig tuningConfig = config.getSchema().getTuningConfig();
    final DimensionRangePartitionsSpec partitionsSpec = (DimensionRangePartitionsSpec) config.getPartitionsSpec();
    return new DeterminePartitionsJobSampler(
        tuningConfig.getDeterminePartitionsSamplingFactor(),
        config.getTargetPartitionSize(),
        partitionsSpec.getMaxRowsPerSegment()
    );
  }

  public static class DeterminePartitionsGroupByMapper extends HadoopDruidIndexerMapper<BytesWritable, NullWritable>
  {
    @Nullable
    private Granularity rollupGranularity = null;
    private DeterminePartitionsJobSampler sampler;

    @Override
    protected void setup(Context context)
        throws IOException, InterruptedException
    {
      super.setup(context);
      rollupGranularity = getConfig().getGranularitySpec().getQueryGranularity();
      sampler = createSampler(getConfig());
    }

    @Override
    protected void innerMap(
        InputRow inputRow,
        Context context
    ) throws IOException, InterruptedException
    {
      final List<Object> groupKey = Rows.toGroupKey(
          rollupGranularity.bucketStart(inputRow.getTimestamp()).getMillis(),
          inputRow
      );

      final byte[] groupKeyBytes = HadoopDruidIndexerConfig.JSON_MAPPER.writeValueAsBytes(groupKey);
      if (sampler.shouldEmitRow(groupKeyBytes)) {
        context.write(
            new BytesWritable(groupKeyBytes),
            NullWritable.get()
        );
      }

      context.getCounter(HadoopDruidIndexerConfig.IndexJobCounters.ROWS_PROCESSED_COUNTER).increment(1);
    }
  }

  public static class DeterminePartitionsGroupByReducer
      extends Reducer<BytesWritable, NullWritable, BytesWritable, NullWritable>
  {
    @Override
    protected void reduce(
        BytesWritable key,
        Iterable<NullWritable> values,
        Context context
    ) throws IOException, InterruptedException
    {
      context.write(key, NullWritable.get());
    }
  }

  /**
   * This DimSelection mapper runs on data generated by our GroupBy job.
   */
  public static class DeterminePartitionsDimSelectionPostGroupByMapper
      extends Mapper<BytesWritable, NullWritable, BytesWritable, Text>
  {
    @Nullable
    private DeterminePartitionsDimSelectionMapperHelper helper;

    @Override
    protected void setup(Context context)
    {
      final HadoopDruidIndexerConfig config = HadoopDruidIndexerConfig.fromConfiguration(context.getConfiguration());
      helper = new DeterminePartitionsDimSelectionMapperHelper(config);
    }

    @Override
    protected void map(BytesWritable key, NullWritable value, Context context) throws IOException, InterruptedException
    {
      final List<Object> timeAndDims = HadoopDruidIndexerConfig.JSON_MAPPER.readValue(key.getBytes(), List.class);

      final Object timestampObj = timeAndDims.get(0);
      final long longTimestamp = ((Number) timestampObj).longValue();
      final DateTime timestamp = new DateTime(longTimestamp, ISOChronology.getInstanceUTC());

      final Map<String, Iterable<String>> dims = (Map<String, Iterable<String>>) timeAndDims.get(1);

      helper.emitDimValueCounts(context, timestamp, dims);
    }
  }

  /**
   * This DimSelection mapper runs on raw input data that we assume has already been grouped.
   */
  public static class DeterminePartitionsDimSelectionAssumeGroupedMapper
      extends HadoopDruidIndexerMapper<BytesWritable, Text>
  {
    private DeterminePartitionsDimSelectionMapperHelper helper;
    private DeterminePartitionsJobSampler sampler;

    @Override
    protected void setup(Context context)
        throws IOException, InterruptedException
    {
      super.setup(context);
      final HadoopDruidIndexerConfig config = HadoopDruidIndexerConfig.fromConfiguration(context.getConfiguration());
      helper = new DeterminePartitionsDimSelectionMapperHelper(config);
      sampler = createSampler(getConfig());
    }

    @Override
    protected void innerMap(
        InputRow inputRow,
        Context context
    ) throws IOException, InterruptedException
    {
      if (sampler.shouldEmitRow()) {
        final Map<String, Iterable<String>> dims = new HashMap<>();
        for (final String dim : inputRow.getDimensions()) {
          dims.put(dim, inputRow.getDimension(dim));
        }
        helper.emitDimValueCounts(context, DateTimes.utc(inputRow.getTimestampFromEpoch()), dims);
      }
    }
  }

  /**
   * Since we have two slightly different DimSelectionMappers, this class encapsulates the shared logic for
   * emitting dimension value counts.
   */
  static class DeterminePartitionsDimSelectionMapperHelper
  {
    private final HadoopDruidIndexerConfig config;
    private final List<List<String>> dimensionGroupingSet;
    private final Map<Long, Integer> intervalIndexes;

    DeterminePartitionsDimSelectionMapperHelper(HadoopDruidIndexerConfig config)
    {
      this.config = config;

      DimensionRangePartitionsSpec spec = (DimensionRangePartitionsSpec) config.getPartitionsSpec();
      final DimensionsSpec dimensionsSpec = config.getSchema().getDataSchema().getDimensionsSpec();
      this.dimensionGroupingSet = new ArrayList<>();
      final List<String> partitionDimensions = spec.getPartitionDimensions();
      //if the partitionDimensions is not set, we just try every dimension to find the best one.
      if (partitionDimensions.isEmpty()) {
        for (DimensionSchema dimensionSchema : dimensionsSpec.getDimensions()) {
          dimensionGroupingSet.add(Collections.singletonList(dimensionSchema.getName()));
        }
      } else {
        dimensionGroupingSet.add(partitionDimensions);
      }

      final ImmutableMap.Builder<Long, Integer> timeIndexBuilder = ImmutableMap.builder();
      int idx = 0;
      for (final Interval bucketInterval : config.getGranularitySpec().sortedBucketIntervals()) {
        timeIndexBuilder.put(bucketInterval.getStartMillis(), idx);
        idx++;
      }

      this.intervalIndexes = timeIndexBuilder.build();
    }

    void emitDimValueCounts(
        TaskInputOutputContext<?, ?, BytesWritable, Text> context,
        DateTime timestamp,
        Map<String, Iterable<String>> dims
    ) throws IOException, InterruptedException
    {
      final Optional<Interval> maybeInterval = config.getGranularitySpec().bucketInterval(timestamp);

      if (!maybeInterval.isPresent()) {
        throw new ISE("No bucket found for timestamp: %s", timestamp);
      }

      final Interval interval = maybeInterval.get();
      final int intervalIndex = intervalIndexes.get(interval.getStartMillis());

      final ByteBuffer buf = ByteBuffer.allocate(4 + 8);
      buf.putInt(intervalIndex);
      buf.putLong(interval.getStartMillis());
      final byte[] groupKey = buf.array();

      // Emit row-counter value.
      write(context, groupKey, new DimValueCount(Collections.emptyList(), StringTuple.create(), 1));

      Iterator<List<String>> dimensionGroupIterator = dimensionGroupingSet.iterator();
      while (dimensionGroupIterator.hasNext()) {
        List<String> dimensionGroup = dimensionGroupIterator.next();
        String[] values = new String[dimensionGroup.size()];
        int numRow = 1;
        for (int i = 0; i < dimensionGroup.size(); i++) {
          String dimension = dimensionGroup.get(i);
          final Iterable<String> dimValues = dims.get(dimension);
          if (dimValues != null && Iterables.size(dimValues) == 1) {
            values[i] = Iterables.getOnlyElement(dimValues);
          } else if (dimValues == null || Iterables.isEmpty(dimValues)) {
            //just let values[i] be null when the dim value is null.
          } else {
            // This dimension is unsuitable for partitioning. Poison it by emitting a negative value.
            numRow = -1;
          }
        }
        write(context, groupKey, new DimValueCount(dimensionGroup, StringTuple.create(values), numRow));
        if (numRow == -1) {
          dimensionGroupIterator.remove();
        }
      }
    }
  }

  public static class DeterminePartitionsDimSelectionPartitioner
      extends Partitioner<BytesWritable, Text> implements Configurable
  {
    private Configuration config;

    @Override
    public int getPartition(BytesWritable bytesWritable, Text text, int numPartitions)
    {
      final ByteBuffer bytes = ByteBuffer.wrap(bytesWritable.getBytes());
      bytes.position(4); // Skip length added by SortableBytes
      final int index = bytes.getInt();
      String jobTrackerAddress = JobHelper.getJobTrackerAddress(config);
      if ("local".equals(jobTrackerAddress)) {
        return index % numPartitions;
      } else {
        if (index >= numPartitions) {
          throw new ISE(
              "Not enough partitions, index[%,d] >= numPartitions[%,d]. Please increase the number of reducers to the index size or check your config & settings!",
              index,
              numPartitions
          );
        }
      }

      return index;
    }

    @Override
    public Configuration getConf()
    {
      return config;
    }

    @Override
    public void setConf(Configuration config)
    {
      this.config = config;
    }
  }

  private abstract static class DeterminePartitionsDimSelectionBaseReducer
      extends Reducer<BytesWritable, Text, BytesWritable, Text>
  {
    @Nullable
    protected volatile HadoopDruidIndexerConfig config = null;

    @Override
    protected void setup(Context context)
    {
      if (config == null) {
        synchronized (DeterminePartitionsDimSelectionBaseReducer.class) {
          if (config == null) {
            config = HadoopDruidIndexerConfig.fromConfiguration(context.getConfiguration());
          }
        }
      }
    }

    @Override
    protected void reduce(BytesWritable key, Iterable<Text> values, Context context)
        throws IOException, InterruptedException
    {
      SortableBytes keyBytes = SortableBytes.fromBytesWritable(key);

      final Iterable<DimValueCount> combinedIterable = combineRows(values);
      innerReduce(context, keyBytes, combinedIterable);
    }

    protected abstract void innerReduce(
        Context context,
        SortableBytes keyBytes,
        Iterable<DimValueCount> combinedIterable
    ) throws IOException, InterruptedException;

    private static Iterable<DimValueCount> combineRows(Iterable<Text> input)
    {
      final Comparator<List<String>> dimsComparator = new Comparator<>()
      {
        @Override
        public int compare(List<String> o1, List<String> o2)
        {
          int len = Math.min(o1.size(), o2.size());
          for (int i = 0; i < len; i++) {
            int comparison = o1.get(i).compareTo(o2.get(i));
            if (comparison != 0) {
              return comparison;
            }
          }
          return Integer.compare(o1.size(), o2.size());
        }
      };
      return new CombiningIterable<>(
          Iterables.transform(
              input,
              DimValueCount::fromText
          ),
          (o1, o2) -> ComparisonChain.start()
                                     .compare(o1.dims, o2.dims, dimsComparator)
                                     .compare(o1.values, o2.values)
                                     .result(),
          (arg1, arg2) -> {
            if (arg2 == null) {
              return arg1;
            }

            // Respect "poisoning" (negative values mean we can't use this dimension)
            final long newNumRows = (arg1.numRows >= 0 && arg2.numRows >= 0 ? arg1.numRows + arg2.numRows : -1);
            return new DimValueCount(arg1.dims, arg1.values, newNumRows);
          }
      );
    }
  }

  public static class DeterminePartitionsDimSelectionCombiner extends DeterminePartitionsDimSelectionBaseReducer
  {
    @Override
    protected void innerReduce(Context context, SortableBytes keyBytes, Iterable<DimValueCount> combinedIterable)
        throws IOException, InterruptedException
    {
      for (DimValueCount dvc : combinedIterable) {
        write(context, keyBytes.getGroupKey(), dvc);
      }
    }
  }

  static DimensionRangeShardSpec createShardSpec(
      boolean isSingleDim,
      List<String> dimensions,
      @Nullable StringTuple start,
      @Nullable StringTuple end,
      int partitionNum,
      @Nullable Integer numCorePartitions
  )
  {
    if (isSingleDim) {
      return new SingleDimensionShardSpec(
          Iterables.getOnlyElement(dimensions),
          start == null ? null : start.get(0),
          end == null ? null : end.get(0),
          partitionNum,
          numCorePartitions
      );
    } else {
      return new DimensionRangeShardSpec(dimensions, start, end, partitionNum, numCorePartitions);
    }
  }

  public static class DeterminePartitionsDimSelectionReducer extends DeterminePartitionsDimSelectionBaseReducer
  {
    private static final double SHARD_COMBINE_THRESHOLD = 0.25;
    private static final int HIGH_CARDINALITY_THRESHOLD = 3000000;

    @Override
    protected void innerReduce(Context context, SortableBytes keyBytes, Iterable<DimValueCount> combinedIterable)
        throws IOException
    {
      final ByteBuffer groupKey = ByteBuffer.wrap(keyBytes.getGroupKey());
      groupKey.position(4); // Skip partition
      final DateTime bucket = DateTimes.utc(groupKey.getLong());
      final PeekingIterator<DimValueCount> iterator = Iterators.peekingIterator(combinedIterable.iterator());

      log.info(
          "Determining partitions for interval: %s",
          config.getGranularitySpec().bucketInterval(bucket).orNull()
      );

      // First DVC should be the total row count indicator
      final DimValueCount firstDvc = iterator.next();
      final long totalRows = firstDvc.numRows;

      if (!firstDvc.dims.isEmpty() || firstDvc.values.size() != 0) {
        throw new IllegalStateException("Expected total row indicator on first k/v pair");
      }

      // "iterator" will now take us over many candidate dimensions
      DimPartitions currentDimPartitions = null;
      DimPartition currentDimPartition = null;
      StringTuple currentDimPartitionStart = null;
      boolean currentDimSkip = false;

      // We'll store possible partitions in here
      final Map<List<String>, DimPartitions> dimPartitionss = new HashMap<>();
      final DimensionRangePartitionsSpec partitionsSpec = (DimensionRangePartitionsSpec) config.getPartitionsSpec();
      final DeterminePartitionsJobSampler sampler = createSampler(config);

      while (iterator.hasNext()) {
        final DimValueCount dvc = iterator.next();

        if (currentDimPartitions == null || !currentDimPartitions.dims.equals(dvc.dims)) {
          // Starting a new dimension! Exciting!
          currentDimPartitions = new DimPartitions(dvc.dims);
          currentDimPartition = new DimPartition();
          currentDimPartitionStart = null;
          currentDimSkip = false;
        }

        // Respect poisoning
        if (!currentDimSkip && dvc.numRows < 0) {
          log.info("Cannot partition on multi-value dimension: %s", dvc.dims);
          currentDimSkip = true;
        }

        if (currentDimSkip) {
          continue;
        }

        // See if we need to cut a new partition ending immediately before this dimension value
        if (currentDimPartition.rows > 0 && currentDimPartition.rows + dvc.numRows > sampler.getSampledTargetPartitionSize()) {
          final ShardSpec shardSpec = createShardSpec(
              partitionsSpec instanceof SingleDimensionPartitionsSpec,
              currentDimPartitions.dims,
              currentDimPartitionStart,
              dvc.values,
              currentDimPartitions.partitions.size(),
              // Set unknown core partitions size so that the legacy way is used for checking partitionHolder
              // completeness. See SingleDimensionShardSpec.createChunk().
              SingleDimensionShardSpec.UNKNOWN_NUM_CORE_PARTITIONS
          );

          log.info(
              "Adding possible shard with %,d rows and %,d unique values: %s",
              currentDimPartition.rows,
              currentDimPartition.cardinality,
              shardSpec
          );

          currentDimPartition.shardSpec = shardSpec;
          currentDimPartitions.partitions.add(currentDimPartition);
          currentDimPartition = new DimPartition();
          currentDimPartitionStart = dvc.values;
        }

        // Update counters
        currentDimPartition.cardinality++;
        currentDimPartition.rows += dvc.numRows;

        if (!iterator.hasNext() || !currentDimPartitions.dims.equals(iterator.peek().dims)) {
          // Finalize the current dimension

          if (currentDimPartition.rows > 0) {
            // One more shard to go
            final ShardSpec shardSpec;

            if (currentDimPartition.rows < sampler.getSampledTargetPartitionSize() * SHARD_COMBINE_THRESHOLD &&
                !currentDimPartitions.partitions.isEmpty()) {
              // Combine with previous shard if it exists and the current shard is small enough
              final DimPartition previousDimPartition = currentDimPartitions.partitions.remove(
                  currentDimPartitions.partitions.size() - 1
              );

              final DimensionRangeShardSpec previousShardSpec = (DimensionRangeShardSpec) previousDimPartition.shardSpec;

              shardSpec = createShardSpec(
                  partitionsSpec instanceof SingleDimensionPartitionsSpec,
                  currentDimPartitions.dims,
                  previousShardSpec.getStartTuple(),
                  null,
                  previousShardSpec.getPartitionNum(),
                  // Set unknown core partitions size so that the legacy way is used for checking partitionHolder
                  // completeness. See SingleDimensionShardSpec.createChunk().
                  SingleDimensionShardSpec.UNKNOWN_NUM_CORE_PARTITIONS
              );

              log.info("Removing possible shard: %s", previousShardSpec);

              currentDimPartition.rows += previousDimPartition.rows;
              currentDimPartition.cardinality += previousDimPartition.cardinality;
            } else {
              // Create new shard
              shardSpec = createShardSpec(
                  partitionsSpec instanceof SingleDimensionPartitionsSpec,
                  currentDimPartitions.dims,
                  currentDimPartitionStart,
                  null,
                  currentDimPartitions.partitions.size(),
                  // Set unknown core partitions size so that the legacy way is used for checking partitionHolder
                  // completeness. See SingleDimensionShardSpec.createChunk().
                  SingleDimensionShardSpec.UNKNOWN_NUM_CORE_PARTITIONS
              );
            }

            log.info(
                "Adding possible shard with %,d rows and %,d unique values: %s",
                currentDimPartition.rows,
                currentDimPartition.cardinality,
                shardSpec
            );

            currentDimPartition.shardSpec = shardSpec;
            currentDimPartitions.partitions.add(currentDimPartition);
          }

          log.info(
              "Completed dimension[%s]: %,d possible shards with %,d unique values",
              currentDimPartitions.dims,
              currentDimPartitions.partitions.size(),
              currentDimPartitions.getCardinality()
          );

          // Add ourselves to the partitions map
          dimPartitionss.put(currentDimPartitions.dims, currentDimPartitions);
        }
      }

      // Choose best dimension
      if (dimPartitionss.isEmpty()) {
        throw new ISE("No suitable partitioning dimension found!");
      }

      int maxCardinality = Integer.MIN_VALUE;
      long minDistance = Long.MAX_VALUE;
      DimPartitions minDistancePartitions = null;
      DimPartitions maxCardinalityPartitions = null;

      for (final DimPartitions dimPartitions : dimPartitionss.values()) {
        if (dimPartitions.getRows() != totalRows) {
          log.info(
              "Dimension[%s] is not present in all rows (row count %,d != expected row count %,d)",
              dimPartitions.dims,
              dimPartitions.getRows(),
              totalRows
          );

          continue;
        }

        // Make sure none of these shards are oversized
        boolean oversized = false;
        for (final DimPartition partition : dimPartitions.partitions) {
          if (partition.rows > sampler.getSampledMaxRowsPerSegment()) {
            log.info("Dimension[%s] has an oversized shard: %s", dimPartitions.dims, partition.shardSpec);
            oversized = true;
          }
        }

        if (oversized) {
          continue;
        }

        final int cardinality = dimPartitions.getCardinality();
        final long distance = dimPartitions.getDistanceSquaredFromTarget(sampler.getSampledTargetPartitionSize());

        if (cardinality > maxCardinality) {
          maxCardinality = cardinality;
          maxCardinalityPartitions = dimPartitions;
        }

        if (distance < minDistance) {
          minDistance = distance;
          minDistancePartitions = dimPartitions;
        }
      }

      if (maxCardinalityPartitions == null) {
        throw new ISE("No suitable partitioning dimension found!");
      }

      final OutputStream out = Utils.makePathAndOutputStream(
          context,
          config.makeSegmentPartitionInfoPath(config.getGranularitySpec().bucketInterval(bucket).get()),
          config.isOverwriteFiles()
      );

      final DimPartitions chosenPartitions = maxCardinality > HIGH_CARDINALITY_THRESHOLD
                                             ? maxCardinalityPartitions
                                             : minDistancePartitions;

      final List<ShardSpec> chosenShardSpecs = Lists.transform(
          chosenPartitions.partitions,
          dimPartition -> dimPartition.shardSpec
      );

      log.info("Chosen partitions:");
      for (ShardSpec shardSpec : chosenShardSpecs) {
        log.info("  %s", HadoopDruidIndexerConfig.JSON_MAPPER.writeValueAsString(shardSpec));
      }

      try {
        HadoopDruidIndexerConfig.JSON_MAPPER
            .writerFor(
                new TypeReference<List<ShardSpec>>()
                {
                }
            )
            .writeValue(out, chosenShardSpecs);
      }
      finally {
        Closeables.close(out, false);
      }
    }
  }

  public static class DeterminePartitionsDimSelectionOutputFormat extends FileOutputFormat
  {
    @Override
    public RecordWriter getRecordWriter(final TaskAttemptContext job)
    {
      return new RecordWriter<SortableBytes, List<ShardSpec>>()
      {
        @Override
        public void write(SortableBytes keyBytes, List<ShardSpec> partitions)
        {
        }

        @Override
        public void close(TaskAttemptContext context)
        {

        }
      };
    }

    @Override
    public void checkOutputSpecs(JobContext job) throws IOException
    {
      Path outDir = FileOutputFormat.getOutputPath(job);
      if (outDir == null) {
        throw new InvalidJobConfException("Output directory not set.");
      }
    }
  }

  private static class DimPartitions
  {
    public final List<String> dims;
    public final List<DimPartition> partitions = new ArrayList<>();

    private DimPartitions(List<String> dims)
    {
      this.dims = dims;
    }

    int getCardinality()
    {
      int sum = 0;
      for (final DimPartition dimPartition : partitions) {
        sum += dimPartition.cardinality;
      }
      return sum;
    }

    long getDistanceSquaredFromTarget(long target)
    {
      long distance = 0;
      for (final DimPartition dimPartition : partitions) {
        distance += (dimPartition.rows - target) * (dimPartition.rows - target);
      }

      distance /= partitions.size();
      return distance;
    }

    public long getRows()
    {
      long sum = 0;
      for (final DimPartition dimPartition : partitions) {
        sum += dimPartition.rows;
      }
      return sum;
    }
  }

  private static class DimPartition
  {
    @Nullable
    public ShardSpec shardSpec = null;
    int cardinality = 0;
    public long rows = 0;
  }

  public static class DimValueCount
  {
    @JsonProperty
    private final List<String> dims;
    @JsonProperty
    private final StringTuple values;
    @JsonProperty
    private final long numRows;

    @JsonCreator
    public DimValueCount(
        @JsonProperty("dims") List<String> dims,
        @JsonProperty("values") StringTuple values,
        @JsonProperty("numRows") long numRows
    )
    {
      this.dims = dims;
      this.values = values;
      this.numRows = numRows;
    }

    public Text toText()
    {
      try {
        String jsonValue = HadoopDruidIndexerConfig.JSON_MAPPER.writeValueAsString(this);
        return new Text(jsonValue);
      }
      catch (JsonProcessingException e) {
        throw new ISE(e, "to json %s error", toString());
      }
    }

    public static DimValueCount fromText(Text text)
    {
      try {
        return HadoopDruidIndexerConfig.JSON_MAPPER.readValue(text.toString(), DimValueCount.class);
      }
      catch (IOException e) {
        throw new ISE(e, "parse json %s error", text.toString());
      }
    }
  }

  private static void write(
      TaskInputOutputContext<?, ?, BytesWritable, Text> context,
      final byte[] groupKey,
      DimValueCount dimValueCount
  )
      throws IOException, InterruptedException
  {
    byte[] sortKey = TAB_JOINER.join(
        COMMA_JOINER.join(dimValueCount.dims),
        COMMA_JOINER.join(dimValueCount.values.toArray())
    ).getBytes(HadoopDruidIndexerConfig.JAVA_NATIVE_CHARSET);
    context.write(new SortableBytes(groupKey, sortKey).toBytesWritable(), dimValueCount.toText());
  }
}
