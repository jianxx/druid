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

package org.apache.druid.math.expr;

import com.google.common.collect.ImmutableList;
import org.apache.druid.error.DruidException;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.math.expr.vector.ExprVectorProcessor;
import org.apache.druid.math.expr.vector.FallbackVectorProcessor;
import org.apache.druid.query.filter.ColumnIndexSelector;
import org.apache.druid.segment.column.ColumnIndexSupplier;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.Types;
import org.apache.druid.segment.index.BitmapColumnIndex;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

@SuppressWarnings("unused")
final class FunctionalExpr
{
  // phony class to enable maven to track the compilation of this class
}

/**
 * {@link Expr} node for a {@link Function} call. {@link FunctionExpr} has children {@link Expr} in the form of the
 * list of arguments that are passed to the {@link Function} along with the {@link Expr.ObjectBinding} when it is
 * evaluated.
 */
@SuppressWarnings("ClassName")
class FunctionExpr implements Expr
{
  final Function function;
  final ImmutableList<Expr> args;
  private final String name;

  FunctionExpr(Function function, String name, List<Expr> args)
  {
    this.function = function;
    this.name = name;
    this.args = ImmutableList.copyOf(args);
    function.validateArguments(args);
  }

  @Override
  public Expr asSingleThreaded(InputBindingInspector inspector)
  {
    return new FunctionExpr(
        function.asSingleThreaded(args, inspector),
        name,
        args
    );
  }

  @Override
  public String toString()
  {
    return StringUtils.format("(%s %s)", name, args);
  }

  @Override
  public ExprEval eval(ObjectBinding bindings)
  {
    try {
      return function.apply(args, bindings);
    }
    catch (ExpressionValidationException e) {
      // ExpressionValidationException already contain function name
      throw DruidException.forPersona(DruidException.Persona.USER)
                          .ofCategory(DruidException.Category.INVALID_INPUT)
                          .build(e, e.getMessage());
    }
    catch (Types.InvalidCastException | Types.InvalidCastBooleanException e) {
      throw DruidException.forPersona(DruidException.Persona.USER)
                          .ofCategory(DruidException.Category.INVALID_INPUT)
                          .build(e, "Function[%s] encountered exception: %s", name, e.getMessage());
    }
    catch (DruidException e) {
      throw e;
    }
    catch (Exception e) {
      throw DruidException.defensive().build(e, "Function[%s] encountered unknown exception.", name);
    }
  }

  @Override
  public boolean canVectorize(InputBindingInspector inspector)
  {
    return function.canVectorize(inspector, args) || canFallbackVectorize(inspector, args);
  }

  @Override
  public ExprVectorProcessor<?> asVectorProcessor(VectorInputBindingInspector inspector)
  {
    if (function.canVectorize(inspector, args)) {
      return function.asVectorProcessor(inspector, args);
    } else {
      return FallbackVectorProcessor.create(function, args, inspector);
    }
  }

  @Nullable
  @Override
  public ColumnIndexSupplier asColumnIndexSupplier(
      ColumnIndexSelector columnIndexSelector,
      @Nullable ColumnType outputType
  )
  {
    final ColumnIndexSupplier indexSupplier = function.asSingleThreaded(args, columnIndexSelector)
                                                      .asColumnIndexSupplier(columnIndexSelector, outputType, args);
    if (indexSupplier != null) {
      return indexSupplier;
    }
    return Expr.super.asColumnIndexSupplier(columnIndexSelector, outputType);
  }

  @Nullable
  @Override
  public BitmapColumnIndex asBitmapColumnIndex(ColumnIndexSelector selector)
  {
    final BitmapColumnIndex functionIndex = function.asSingleThreaded(args, selector)
                                                    .asBitmapColumnIndex(selector, args);
    if (functionIndex != null) {
      return functionIndex;
    }
    return Expr.super.asBitmapColumnIndex(selector);
  }

  @Override
  public String stringify()
  {
    return StringUtils.format("%s(%s)", name, ARG_JOINER.join(args.stream().map(Expr::stringify).iterator()));
  }

  @Override
  public Expr visit(Shuttle shuttle)
  {
    return shuttle.visit(new FunctionExpr(function, name, shuttle.visitAll(args)));
  }

  @Override
  public BindingAnalysis analyzeInputs()
  {
    return BindingAnalysis.collectExprs(args)
                          .withScalarArguments(function.getScalarInputs(args))
                          .withArrayArguments(function.getArrayInputs(args))
                          .withArrayInputs(function.hasArrayInputs())
                          .withArrayOutput(function.hasArrayOutput());
  }

  @Override
  public ExpressionType getOutputType(InputBindingInspector inspector)
  {
    return function.getOutputType(inspector, args);
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    FunctionExpr that = (FunctionExpr) o;
    return args.equals(that.args) &&
           name.equals(that.name);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(args, name);
  }
}

/**
 * This {@link Expr} node is representative of an {@link ApplyFunction}, and has children in the form of a
 * {@link LambdaExpr} and the list of {@link Expr} arguments that are combined with {@link Expr.ObjectBinding} to
 * evaluate the {@link LambdaExpr}.
 */
@SuppressWarnings("ClassName")
class ApplyFunctionExpr implements Expr
{
  final ApplyFunction function;
  final String name;
  final LambdaExpr lambdaExpr;
  final ImmutableList<Expr> argsExpr;
  final BindingAnalysis bindingAnalysis;
  final BindingAnalysis lambdaBindingAnalysis;
  final ImmutableList<BindingAnalysis> argsBindingAnalyses;

  ApplyFunctionExpr(ApplyFunction function, String name, LambdaExpr expr, List<Expr> args)
  {
    this.function = function;
    this.name = name;
    this.argsExpr = ImmutableList.copyOf(args);
    this.lambdaExpr = expr;

    function.validateArguments(expr, args);

    // apply function expressions are examined during expression selector creation, so precompute and cache the
    // binding details of children
    ImmutableList.Builder<BindingAnalysis> argBindingDetailsBuilder = ImmutableList.builder();
    for (Expr arg : argsExpr) {
      argBindingDetailsBuilder.add(arg.analyzeInputs());
    }

    lambdaBindingAnalysis = lambdaExpr.analyzeInputs();
    argsBindingAnalyses = argBindingDetailsBuilder.build();
    bindingAnalysis = BindingAnalysis.collect(argBindingDetailsBuilder.add(lambdaBindingAnalysis).build())
                                     .withArrayArguments(function.getArrayInputs(argsExpr))
                                     .withArrayInputs(true)
                                     .withArrayOutput(function.hasArrayOutput(lambdaExpr));
  }

  @Override
  public String toString()
  {
    return StringUtils.format("(%s %s, %s)", name, lambdaExpr, argsExpr);
  }

  @Override
  public ExprEval eval(ObjectBinding bindings)
  {
    return function.apply(lambdaExpr, argsExpr, bindings);
  }

  @Override
  public boolean canVectorize(InputBindingInspector inspector)
  {
    return canVectorizeNative(inspector) ||
           (canFallbackVectorize(inspector, argsExpr) && lambdaExpr.canVectorize(inspector));
  }

  @Override
  public <T> ExprVectorProcessor<T> asVectorProcessor(VectorInputBindingInspector inspector)
  {
    if (canVectorizeNative(inspector)) {
      return function.asVectorProcessor(inspector, lambdaExpr, argsExpr);
    } else {
      return FallbackVectorProcessor.create(function, lambdaExpr, argsExpr, inspector);
    }
  }

  private boolean canVectorizeNative(InputBindingInspector inspector)
  {
    return function.canVectorize(inspector, lambdaExpr, argsExpr) &&
           lambdaExpr.canVectorize(inspector) &&
           argsExpr.stream().allMatch(expr -> expr.canVectorize(inspector));
  }

  @Override
  public String stringify()
  {
    return StringUtils.format(
        "%s(%s, %s)",
        name,
        lambdaExpr.stringify(),
        ARG_JOINER.join(argsExpr.stream().map(Expr::stringify).iterator())
    );
  }

  @Override
  public Expr visit(Shuttle shuttle)
  {
    LambdaExpr newLambda = (LambdaExpr) lambdaExpr.visit(shuttle);
    return shuttle.visit(new ApplyFunctionExpr(function, name, newLambda, shuttle.visitAll(argsExpr)));
  }

  @Override
  public BindingAnalysis analyzeInputs()
  {
    return bindingAnalysis;
  }

  @Nullable
  @Override
  public ExpressionType getOutputType(InputBindingInspector inspector)
  {
    return function.getOutputType(inspector, lambdaExpr, argsExpr);
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ApplyFunctionExpr that = (ApplyFunctionExpr) o;
    return name.equals(that.name) &&
           lambdaExpr.equals(that.lambdaExpr) &&
           argsExpr.equals(that.argsExpr);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(name, lambdaExpr, argsExpr);
  }
}
