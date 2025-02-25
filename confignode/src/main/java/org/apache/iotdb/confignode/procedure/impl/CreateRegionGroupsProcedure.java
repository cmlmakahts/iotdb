/*
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.confignode.procedure.impl;

import org.apache.iotdb.common.rpc.thrift.TConsensusGroupId;
import org.apache.iotdb.common.rpc.thrift.TRegionReplicaSet;
import org.apache.iotdb.confignode.consensus.request.write.CreateRegionGroupsPlan;
import org.apache.iotdb.confignode.consensus.request.write.DeleteRegionGroupsPlan;
import org.apache.iotdb.confignode.procedure.StateMachineProcedure;
import org.apache.iotdb.confignode.procedure.env.ConfigNodeProcedureEnv;
import org.apache.iotdb.confignode.procedure.exception.ProcedureException;
import org.apache.iotdb.confignode.procedure.state.CreateRegionGroupsState;

import java.util.Map;

public class CreateRegionGroupsProcedure
    extends StateMachineProcedure<ConfigNodeProcedureEnv, CreateRegionGroupsState> {

  private final CreateRegionGroupsPlan createRegionGroupsPlan;
  // Map<TConsensusGroupId, Failed RegionReplicas>
  private Map<TConsensusGroupId, TRegionReplicaSet> failedRegions;

  public CreateRegionGroupsProcedure(CreateRegionGroupsPlan createRegionGroupsPlan) {
    this.createRegionGroupsPlan = createRegionGroupsPlan;
  }

  @Override
  protected Flow executeFromState(ConfigNodeProcedureEnv env, CreateRegionGroupsState state) {
    switch (state) {
      case CREATE_REGION_GROUPS_PREPARE:
        setNextState(CreateRegionGroupsState.CREATE_REGION_GROUPS);
        break;
      case CREATE_REGION_GROUPS:
        failedRegions = env.doRegionCreation(createRegionGroupsPlan);
        setNextState(CreateRegionGroupsState.PERSIST_AND_BROADCAST);
        break;
      case PERSIST_AND_BROADCAST:
        // Filter those RegionGroups that created successfully
        CreateRegionGroupsPlan persistPlan = new CreateRegionGroupsPlan();
        createRegionGroupsPlan
            .getRegionGroupMap()
            .forEach(
                (storageGroup, regionReplicaSets) ->
                    regionReplicaSets.forEach(
                        regionReplicaSet -> {
                          if (!failedRegions.containsKey(regionReplicaSet.getRegionId())) {
                            persistPlan.addRegionGroup(storageGroup, regionReplicaSet);
                          }
                        }));
        env.persistAndBroadcastRegionGroup(persistPlan);
        setNextState(
            failedRegions.size() > 0
                ? CreateRegionGroupsState.DELETE_FAILED_REGION_GROUPS
                : CreateRegionGroupsState.CREATE_REGION_GROUPS_FINISH);
        break;
      case DELETE_FAILED_REGION_GROUPS:
        DeleteRegionGroupsPlan deletePlan = new DeleteRegionGroupsPlan();
        // We don't need to wipe the PartitionTable here
        // since the failed RegionGroups are not recorded
        deletePlan.setNeedsDeleteInPartitionTable(false);
        createRegionGroupsPlan
            .getRegionGroupMap()
            .forEach(
                (storageGroup, regionReplicaSets) ->
                    regionReplicaSets.forEach(
                        regionReplicaSet -> {
                          if (failedRegions.containsKey(regionReplicaSet.getRegionId())) {
                            TRegionReplicaSet failedReplicaSet =
                                failedRegions.get(regionReplicaSet.getRegionId());
                            TRegionReplicaSet redundantReplicaSet =
                                new TRegionReplicaSet().setRegionId(regionReplicaSet.getRegionId());
                            regionReplicaSet
                                .getDataNodeLocations()
                                .forEach(
                                    dataNodeLocation -> {
                                      if (!failedReplicaSet
                                          .getDataNodeLocations()
                                          .contains(dataNodeLocation)) {
                                        redundantReplicaSet.addToDataNodeLocations(
                                            dataNodeLocation);
                                      }
                                    });
                            deletePlan.addRegionGroup(storageGroup, redundantReplicaSet);
                          }
                        }));
        env.submitFailedRegionReplicas(deletePlan);
        setFailure(
            new ProcedureException(
                "There are some RegionGroups failed to create, please check former logs in ConfigNode-leader."));
        return Flow.NO_MORE_STATE;
      case CREATE_REGION_GROUPS_FINISH:
        return Flow.NO_MORE_STATE;
    }

    return Flow.HAS_MORE_STATE;
  }

  @Override
  protected void rollbackState(
      ConfigNodeProcedureEnv configNodeProcedureEnv,
      CreateRegionGroupsState createRegionGroupsState) {
    // Do nothing
  }

  @Override
  protected CreateRegionGroupsState getState(int stateId) {
    return CreateRegionGroupsState.values()[stateId];
  }

  @Override
  protected int getStateId(CreateRegionGroupsState createRegionGroupsState) {
    return createRegionGroupsState.ordinal();
  }

  @Override
  protected CreateRegionGroupsState getInitialState() {
    return CreateRegionGroupsState.CREATE_REGION_GROUPS;
  }
}
