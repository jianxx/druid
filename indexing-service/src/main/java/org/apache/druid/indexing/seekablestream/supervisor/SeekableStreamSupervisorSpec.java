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

package org.apache.druid.indexing.seekablestream.supervisor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import org.apache.druid.common.config.Configs;
import org.apache.druid.error.DruidException;
import org.apache.druid.error.InvalidInput;
import org.apache.druid.guice.annotations.Json;
import org.apache.druid.indexing.overlord.IndexerMetadataStorageCoordinator;
import org.apache.druid.indexing.overlord.TaskMaster;
import org.apache.druid.indexing.overlord.TaskStorage;
import org.apache.druid.indexing.overlord.supervisor.Supervisor;
import org.apache.druid.indexing.overlord.supervisor.SupervisorSpec;
import org.apache.druid.indexing.overlord.supervisor.SupervisorStateManagerConfig;
import org.apache.druid.indexing.overlord.supervisor.autoscaler.SupervisorTaskAutoScaler;
import org.apache.druid.indexing.seekablestream.SeekableStreamIndexTaskClientFactory;
import org.apache.druid.indexing.seekablestream.supervisor.autoscaler.AutoScalerConfig;
import org.apache.druid.indexing.seekablestream.supervisor.autoscaler.NoopTaskAutoScaler;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.java.util.metrics.DruidMonitorSchedulerConfig;
import org.apache.druid.segment.incremental.RowIngestionMetersFactory;
import org.apache.druid.segment.indexing.DataSchema;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

public abstract class SeekableStreamSupervisorSpec implements SupervisorSpec
{
  protected static final String ILLEGAL_INPUT_SOURCE_UPDATE_ERROR_MESSAGE = "Update of the input source stream from [%s] to [%s] is not supported for a running supervisor."
                                                                   + "%nTo perform the update safely, follow these steps:"
                                                                   + "%n(1) Suspend this supervisor, reset its offsets and then terminate it. "
                                                                   + "%n(2) Create a new supervisor with the new input source stream."
                                                                   + "%nNote that doing the reset can cause data duplication or loss if any topic used in the old supervisor is included in the new one too.";

  private static SeekableStreamSupervisorIngestionSpec checkIngestionSchema(
      SeekableStreamSupervisorIngestionSpec ingestionSchema
  )
  {
    Preconditions.checkNotNull(ingestionSchema, "ingestionSchema");
    Preconditions.checkNotNull(ingestionSchema.getDataSchema(), "dataSchema");
    Preconditions.checkNotNull(ingestionSchema.getIOConfig(), "ioConfig");
    return ingestionSchema;
  }

  protected final String id;
  protected final TaskStorage taskStorage;
  protected final TaskMaster taskMaster;
  protected final IndexerMetadataStorageCoordinator indexerMetadataStorageCoordinator;
  protected final SeekableStreamIndexTaskClientFactory indexTaskClientFactory;
  protected final ObjectMapper mapper;
  protected final RowIngestionMetersFactory rowIngestionMetersFactory;
  private final SeekableStreamSupervisorIngestionSpec ingestionSchema;
  @Nullable
  private final Map<String, Object> context;
  protected final ServiceEmitter emitter;
  protected final DruidMonitorSchedulerConfig monitorSchedulerConfig;
  private final boolean suspended;
  protected final SupervisorStateManagerConfig supervisorStateManagerConfig;

  /**
   * Base constructor for SeekableStreamSupervisors.
   * The unique identifier for the supervisor. A null {@code id} implies the constructor will use the
   * non-null `dataSource` in `ingestionSchema` for backwards compatibility.
   */
  public SeekableStreamSupervisorSpec(
      @Nullable final String id,
      final SeekableStreamSupervisorIngestionSpec ingestionSchema,
      @Nullable Map<String, Object> context,
      Boolean suspended,
      TaskStorage taskStorage,
      TaskMaster taskMaster,
      IndexerMetadataStorageCoordinator indexerMetadataStorageCoordinator,
      SeekableStreamIndexTaskClientFactory indexTaskClientFactory,
      @Json ObjectMapper mapper,
      ServiceEmitter emitter,
      DruidMonitorSchedulerConfig monitorSchedulerConfig,
      RowIngestionMetersFactory rowIngestionMetersFactory,
      SupervisorStateManagerConfig supervisorStateManagerConfig
  )
  {
    this.ingestionSchema = checkIngestionSchema(ingestionSchema);
    this.id = Preconditions.checkNotNull(
        Configs.valueOrDefault(id, ingestionSchema.getDataSchema().getDataSource()),
        "spec id cannot be null!"
    );
    this.context = context;

    this.taskStorage = taskStorage;
    this.taskMaster = taskMaster;
    this.indexerMetadataStorageCoordinator = indexerMetadataStorageCoordinator;
    this.indexTaskClientFactory = indexTaskClientFactory;
    this.mapper = mapper;
    this.emitter = emitter;
    this.monitorSchedulerConfig = monitorSchedulerConfig;
    this.rowIngestionMetersFactory = rowIngestionMetersFactory;
    this.suspended = suspended != null ? suspended : false;
    this.supervisorStateManagerConfig = supervisorStateManagerConfig;
  }

  @JsonProperty
  public SeekableStreamSupervisorIngestionSpec getSpec()
  {
    return ingestionSchema;
  }

  @Deprecated
  @JsonProperty
  public DataSchema getDataSchema()
  {
    return ingestionSchema.getDataSchema();
  }

  @JsonProperty
  public SeekableStreamSupervisorTuningConfig getTuningConfig()
  {
    return ingestionSchema.getTuningConfig();
  }

  @JsonProperty
  public SeekableStreamSupervisorIOConfig getIoConfig()
  {
    return ingestionSchema.getIOConfig();
  }

  @Nullable
  @JsonProperty
  public Map<String, Object> getContext()
  {
    return context;
  }

  @Nullable
  public <ContextValueType> ContextValueType getContextValue(String key)
  {
    return context == null ? null : (ContextValueType) context.get(key);
  }

  public ServiceEmitter getEmitter()
  {
    return emitter;
  }

  /**
   * Returns the identifier for this supervisor.
   * If unspecified, defaults to the dataSource being written to.
   */
  @Override
  @JsonProperty
  public String getId()
  {
    return id;
  }

  public DruidMonitorSchedulerConfig getMonitorSchedulerConfig()
  {
    return monitorSchedulerConfig;
  }

  @Override
  public abstract Supervisor createSupervisor();

  /**
   * An autoScaler instance will be returned depending on the autoScalerConfig. In case autoScalerConfig is null or autoScaler is disabled then NoopTaskAutoScaler will be returned.
   * @param supervisor
   * @return autoScaler
   */
  @Override
  public SupervisorTaskAutoScaler createAutoscaler(Supervisor supervisor)
  {
    AutoScalerConfig autoScalerConfig = ingestionSchema.getIOConfig().getAutoScalerConfig();
    if (autoScalerConfig != null && autoScalerConfig.getEnableTaskAutoScaler() && supervisor instanceof SeekableStreamSupervisor) {
      return autoScalerConfig.createAutoScaler(supervisor, this, emitter);
    }
    return new NoopTaskAutoScaler();
  }

  @Override
  public List<String> getDataSources()
  {
    return ImmutableList.of(getDataSchema().getDataSource());
  }

  @Override
  public SeekableStreamSupervisorSpec createSuspendedSpec()
  {
    return toggleSuspend(true);
  }

  @Override
  public SeekableStreamSupervisorSpec createRunningSpec()
  {
    return toggleSuspend(false);
  }

  public SupervisorStateManagerConfig getSupervisorStateManagerConfig()
  {
    return supervisorStateManagerConfig;
  }

  @Override
  @JsonProperty("suspended")
  public boolean isSuspended()
  {
    return suspended;
  }

  /**
   * Default implementation that prevents unsupported evolution of the supervisor spec
   * <ul>
   * <li>You cannot migrate between types of supervisors.</li>
   * <li>You cannot change the input source stream of a running supervisor.</li>
   * </ul>
   * @param proposedSpec the proposed supervisor spec
   * @throws DruidException if the proposed spec update is not allowed
   */
  @Override
  public void validateSpecUpdateTo(SupervisorSpec proposedSpec) throws DruidException
  {
    if (!(proposedSpec instanceof SeekableStreamSupervisorSpec)) {
      throw InvalidInput.exception(
          "Cannot update supervisor spec from type[%s] to type[%s]", getClass().getSimpleName(), proposedSpec.getClass().getSimpleName()
      );
    }
    SeekableStreamSupervisorSpec other = (SeekableStreamSupervisorSpec) proposedSpec;
    if (this.getSource() == null || other.getSource() == null) {
      // Not likely to happen, but covering just in case.
      throw InvalidInput.exception("Cannot update supervisor spec since one or both of "
                                   + "the specs have not provided an input source stream in the 'ioConfig'.");
    }

    if (!this.getSource().equals(other.getSource())) {
      throw InvalidInput.exception(ILLEGAL_INPUT_SOURCE_UPDATE_ERROR_MESSAGE, this.getSource(), other.getSource());
    }
  }

  protected abstract SeekableStreamSupervisorSpec toggleSuspend(boolean suspend);

}
