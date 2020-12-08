/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.execution.steps;

import com.google.common.collect.ImmutableSortedMap;
import org.gradle.internal.Try;
import org.gradle.internal.execution.ExecutionOutcome;
import org.gradle.internal.execution.ExecutionResult;
import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.execution.history.AfterPreviousExecutionState;
import org.gradle.internal.execution.history.changes.InputChangesInternal;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.BuildOperationType;
import org.gradle.internal.operations.CallableBuildOperation;
import org.gradle.internal.snapshot.FileSystemSnapshot;
import org.gradle.work.InputChanges;

import java.io.File;
import java.util.Optional;

public class ExecuteStep<C extends InputChangesContext> implements Step<C, Result> {

    private final BuildOperationExecutor buildOperationExecutor;

    public ExecuteStep(BuildOperationExecutor buildOperationExecutor) {
        this.buildOperationExecutor = buildOperationExecutor;
    }

    @Override
    public Result execute(UnitOfWork work, C context) {
        return buildOperationExecutor.call(new CallableBuildOperation<Result>() {
            @Override
            public Result call(BuildOperationContext operationContext) {
                Result result = executeInternal(work, context);
                operationContext.setResult(Operation.Result.INSTANCE);
                return result;
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                return BuildOperationDescriptor
                    .displayName("Executing " + work.getDisplayName())
                    .details(Operation.Details.INSTANCE);
            }
        });
    }

    private static Result executeInternal(UnitOfWork work, InputChangesContext context) {
        try {
            UnitOfWork.ExecutionRequest executionRequest = new UnitOfWork.ExecutionRequest() {
                @Override
                public File getWorkspace() {
                    return context.getWorkspace();
                }

                @Override
                public Optional<InputChangesInternal> getInputChanges() {
                    return context.getInputChanges();
                }

                @Override
                public Optional<ImmutableSortedMap<String, FileSystemSnapshot>> getPreviouslyProducedOutputs() {
                    return context.getAfterPreviousExecutionState()
                        .map(AfterPreviousExecutionState::getOutputFilesProducedByWork);
                }
            };
            UnitOfWork.WorkOutput workOutput = work.execute(executionRequest);
            ExecutionOutcome outcome = determineOutcome(context, workOutput);
            ExecutionResult executionResult = new ExecutionResult() {
                @Override
                public ExecutionOutcome getOutcome() {
                    return outcome;
                }

                @Override
                public Object getOutput() {
                    return workOutput.getOutput();
                }
            };
            return () -> Try.successful(executionResult);
        } catch (Throwable t) {
            return () -> Try.failure(t);
        }
    }

    private static ExecutionOutcome determineOutcome(InputChangesContext context, UnitOfWork.WorkOutput workOutput) {
        ExecutionOutcome outcome;
        switch (workOutput.getDidWork()) {
            case DID_NO_WORK:
                outcome = ExecutionOutcome.UP_TO_DATE;
                break;
            case DID_WORK:
                boolean incremental = context.getInputChanges()
                    .map(InputChanges::isIncremental)
                    .orElse(false);
                outcome = incremental
                    ? ExecutionOutcome.EXECUTED_INCREMENTALLY
                    : ExecutionOutcome.EXECUTED_NON_INCREMENTALLY;
                break;
            default:
                throw new AssertionError();
        }
        return outcome;
    }

    /*
     * This operation is only used here temporarily. Should be replaced with a more stable operation in the long term.
     */
    public interface Operation extends BuildOperationType<Operation.Details, Operation.Result> {
        interface Details {
            Operation.Details INSTANCE = new Operation.Details() {};
        }

        interface Result {
            Operation.Result INSTANCE = new Operation.Result() {};
        }
    }
}
