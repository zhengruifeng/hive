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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.reexec;

import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.exec.tez.TezRuntimeException;
import org.apache.hadoop.hive.ql.hooks.ExecuteWithHookContext;
import org.apache.hadoop.hive.ql.hooks.HookContext;
import org.apache.hadoop.hive.ql.plan.mapper.PlanMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Re-Executes a query if tez AM failed because of node/container failure.
 */
public class ReExecuteLostAMQueryPlugin implements IReExecutionPlugin {
  private static final Logger LOG = LoggerFactory.getLogger(ReExecuteLostAMQueryPlugin.class);

  // Lost am container have exit code -100, due to node failures. This pattern of exception is thrown when
  // AM is managed by HS2.
  private static final Pattern LOST_AM_CONTAINER_ERROR_PATTERN =
      Pattern.compile(".*AM Container for .* exited .* exitCode: -100.*", Pattern.DOTALL);
  // When HS2 does not manage the AMs, tez AMs are registered with zookeeper and HS2 discovers it,
  // failure of unmanaged AMs will throw AM record not being found in zookeeper.
  private static final String UNMANAGED_AM_FAILURE = "AM record not found (likely died)";
  // DAG lost in the scenario described at TEZ-4543
  private static final String DAG_LOST_FAILURE = "No running DAG at present";

  private boolean retryPossible;
  // a list to track DAG ids seen by this re-execution plugin during the same query
  // it can help a lot with identifying the previous DAGs in case of retries
  private Set<String> dagIds = new HashSet<>();

  class LocalHook implements ExecuteWithHookContext {
    @Override
    public void run(HookContext hookContext) throws Exception {
      if (hookContext.getHookType() == HookContext.HookType.ON_FAILURE_HOOK) {
        Throwable exception = hookContext.getException();

        if (!(exception instanceof TezRuntimeException)) {
          LOG.info("Exception is not a TezRuntimeException, no need to check further with ReExecuteLostAMQueryPlugin");
          return;
        }

        TezRuntimeException tre = (TezRuntimeException)exception;
        String message = tre.getMessage();
        if (message != null) {
          dagIds.add(tre.getDagId());

          if (LOST_AM_CONTAINER_ERROR_PATTERN.matcher(message).matches()
              || message.contains(UNMANAGED_AM_FAILURE)
              || message.contains(DAG_LOST_FAILURE)) {
            retryPossible = true;
          }
          LOG.info("Got exception message: {} retryPossible: {}, dags seen so far: {}", message, retryPossible,
              dagIds);
        }
      }
    }
  }

  @Override
  public void initialize(Driver driver) {
    driver.getHookRunner().addOnFailureHook(new LocalHook());
  }

  @Override
  public boolean shouldReExecute(int executionNum) {
    return retryPossible;
  }

  @Override
  public boolean shouldReExecuteAfterCompile(int executionNum, PlanMapper oldPlanMapper, PlanMapper newPlanMapper) {
    return retryPossible;
  }
}
