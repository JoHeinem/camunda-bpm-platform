/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.optimize.qa;

import org.camunda.bpm.engine.DecisionService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.identity.Group;
import org.camunda.bpm.engine.identity.User;
import org.camunda.bpm.engine.repository.DeploymentBuilder;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.dmn.DmnModelInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.camunda.optimize.qa.DmnHelper.createSimpleDmnModel;

public class EngineDataGenerator {

  private Logger logger = LoggerFactory.getLogger(this.getClass());

  private static final String userId = "testUser";
  private static final String groupId = "testGroup";
  private static final String USER_TASK_PROCESS_KEY = "userTaskProcess";
  private static final String AUTO_COMPLETE_PROCESS_KEY = "autoCompleteProcess";
  private static final String DECISION_KEY = "simpleDecisionKey";

  private final IdentityService identityService;
  private final DecisionService decisionService;
  private final RepositoryService repositoryService;
  private final RuntimeService runtimeService;
  private final TaskService taskService;

  public final int numberOfInstancesToGenerate;

  public EngineDataGenerator(final ProcessEngine processEngine, final int optimizePageSize) {
    this.identityService = processEngine.getIdentityService();
    this.decisionService = processEngine.getDecisionService();
    this.repositoryService = processEngine.getRepositoryService();
    this.runtimeService = processEngine.getRuntimeService();
    this.taskService = processEngine.getTaskService();

    // we double the amount of instances to generate to make sure that there are at least two pages
    // of each entity available
    numberOfInstancesToGenerate = optimizePageSize * 2;
  }

  public void generateData() {
    logger.info("Generating engine data for Optimize rest tests...");
    deployDefinitions();
    generateUserTaskData();
    generateCompletedProcessInstanceData();
    generateDecisionInstanceData();
    logger.info("Generation of engine data has been completed.");
  }

  private void generateDecisionInstanceData() {
    logger.info("Generating decision instance data...");
    for (int i = 0; i < numberOfInstancesToGenerate; i++) {
      decisionService
        .evaluateDecisionByKey(DECISION_KEY)
        .variables(createSimpleVariables())
        .evaluate();
    }
    logger.info("Successfully generated decision instance data.");
  }

  private void generateCompletedProcessInstanceData() {
    logger.info("Generating completed process instance data...");
    for (int i = 0; i < numberOfInstancesToGenerate; i++) {
      runtimeService.startProcessInstanceByKey(AUTO_COMPLETE_PROCESS_KEY, createSimpleVariables());
    }
    logger.info("Successfully generated completed process instance data...");
  }

  private void generateUserTaskData() {
    // the user task data includes data for tasks, identity link log, operations log
    logger.info("Generating user task data....");
    for (int i = 0; i < numberOfInstancesToGenerate; i++) {
      runtimeService.startProcessInstanceByKey(USER_TASK_PROCESS_KEY, createSimpleVariables());
    }
    createUser();
    createGroup();
    setCandidateUserAndGroupForAllUserTask();
    completeAllUserTasks();
    logger.info("User task data successfully generated.");
  }

  private void deployDefinitions() {
    logger.info("Deploying process & decision definitions...");
    BpmnModelInstance userTaskProcessModelInstance = createUserTaskProcess();
    BpmnModelInstance autoCompleteProcessModelInstance = createSimpleServiceTaskProcess();
    final DmnModelInstance decisionModelInstance = createSimpleDmnModel(DECISION_KEY);
    DeploymentBuilder deploymentbuilder = repositoryService.createDeployment();
    deploymentbuilder.addModelInstance("userTaskProcess.bpmn", userTaskProcessModelInstance);
    deploymentbuilder.addModelInstance("autoCompleteProcess.bpmn", autoCompleteProcessModelInstance);
    deploymentbuilder.addModelInstance("simpleDecision.dmn", decisionModelInstance);
    deploymentbuilder.deploy();
    logger.info("Definitions successfully deployed.");
  }

  private void createUser() {
    User user = identityService.newUser(EngineDataGenerator.userId);
    identityService.saveUser(user);
  }

  private void createGroup() {
    Group group = identityService.newGroup(groupId);
    identityService.saveGroup(group);
  }

  private void setCandidateUserAndGroupForAllUserTask() {
    List<Task> list = taskService.createTaskQuery().list();
    identityService.setAuthenticatedUserId(userId);
    for (Task task : list) {
      taskService.addCandidateUser(task.getId(), userId);
      taskService.addCandidateGroup(task.getId(), groupId);
    }
  }

  private void completeAllUserTasks() {
    List<Task> list = taskService.createTaskQuery().list();
    for (Task task : list) {
      taskService.claim(task.getId(), userId);
      taskService.complete(task.getId());
    }
  }

  private Map<String, Object> createSimpleVariables() {
    Random random = new Random();
    Map<String, Object> variables = new HashMap<>();
    int integer = random.nextInt();
    variables.put("stringVar", "aStringValue");
    variables.put("boolVar", random.nextBoolean());
    variables.put("integerVar", random.nextInt());
    variables.put("shortVar", (short) integer);
    variables.put("longVar", random.nextLong());
    variables.put("doubleVar", random.nextDouble());
    variables.put("dateVar", new Date(random.nextInt()));
    return variables;
  }

  private BpmnModelInstance createSimpleServiceTaskProcess() {
    return Bpmn.createExecutableProcess(AUTO_COMPLETE_PROCESS_KEY)
      .startEvent()
      .serviceTask()
        .camundaExpression("${true}")
      .endEvent()
      .done();
  }

  private static BpmnModelInstance createUserTaskProcess() {
    return Bpmn.createExecutableProcess(USER_TASK_PROCESS_KEY)
      .startEvent()
      .userTask("userTaskToComplete")
      .userTask("pendingUserTask")
      .endEvent()
      .done();
  }
}
