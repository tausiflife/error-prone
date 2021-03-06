/*
 * Copyright 2017 The Error Prone Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

package com.google.errorprone.bugpatterns;

import static com.google.errorprone.BugPattern.SeverityLevel.ERROR;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static com.google.errorprone.matchers.method.MethodMatchers.staticMethod;
import static com.google.errorprone.util.ASTHelpers.findSuperMethods;
import static com.google.errorprone.util.ASTHelpers.hasAnnotation;

import com.google.common.collect.ImmutableMap;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker.MemberReferenceTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.MethodInvocationTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.MethodTreeMatcher;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.fixes.SuggestedFixes;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.util.ASTHelpers;
import com.google.errorprone.util.MoreAnnotations;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import java.util.Map;
import javax.lang.model.element.Modifier;

/** A {@link BugChecker}; see the associated {@link BugPattern} annotation for details. */
// TODO(cushon): this should subsume ImmutableModification and LocalizableWrongToString
@BugPattern(name = "DoNotCall", summary = "This method should not be called.", severity = ERROR)
public class DoNotCallChecker extends BugChecker
    implements MethodTreeMatcher, MethodInvocationTreeMatcher, MemberReferenceTreeMatcher {

  // If your method cannot be annotated with @DoNotCall (e.g., it's a JDK or thirdparty method),
  // then add it to this Map with an explanation.
  private static final ImmutableMap<Matcher<ExpressionTree>, String> THIRD_PARTY_METHODS =
      ImmutableMap.of(
          staticMethod()
                  .onClass("org.junit.Assert")
                  .named("assertEquals")
                  .withParameters("double", "double"),
              "This method always throws java.lang.AssertionError. Use assertEquals("
                  + "expected, actual, delta) to compare floating-point numbers",
          staticMethod()
                  .onClass("org.junit.Assert")
                  .named("assertEquals")
                  .withParameters("java.lang.String", "double", "double"),
              "This method always throws java.lang.AssertionError. Use assertEquals("
                  + "String, expected, actual, delta) to compare floating-point numbers");

  private static final String DO_NOT_CALL = "com.google.errorprone.annotations.DoNotCall";

  @Override
  public Description matchMethod(MethodTree tree, VisitorState state) {
    MethodSymbol symbol = ASTHelpers.getSymbol(tree);
    if (symbol == null) {
      return NO_MATCH;
    }
    if (hasAnnotation(tree, DO_NOT_CALL, state)) {
      if (symbol.getModifiers().contains(Modifier.PRIVATE)) {
        return buildDescription(tree)
            .setMessage("A private method that should not be called should simply be removed.")
            .build();
      }
      if (symbol.getModifiers().contains(Modifier.ABSTRACT)) {
        return NO_MATCH;
      }
      if (!ASTHelpers.methodCanBeOverridden(symbol)) {
        return NO_MATCH;
      }
      return buildDescription(tree)
          .setMessage("Methods annotated with @DoNotCall should be final or static.")
          .addFix(SuggestedFixes.addModifiers(tree, state, Modifier.FINAL))
          .build();
    }
    return findSuperMethods(symbol, state.getTypes()).stream()
        .filter(s -> hasAnnotation(s, DO_NOT_CALL, state))
        .findAny()
        .map(
            s -> {
              String message =
                  String.format(
                      "Method overrides %s in %s which is annotated @DoNotCall,"
                          + " it should also be annotated.",
                      s.getSimpleName(), s.owner.getSimpleName());
              return buildDescription(tree)
                  .setMessage(message)
                  .addFix(
                      SuggestedFix.builder()
                          .addImport(DO_NOT_CALL)
                          .prefixWith(tree, "@DoNotCall ")
                          .build())
                  .build();
            })
        .orElse(NO_MATCH);
  }

  @Override
  public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
    for (Map.Entry<Matcher<ExpressionTree>, String> matcher : THIRD_PARTY_METHODS.entrySet()) {
      if (matcher.getKey().matches(tree, state)) {
        return buildDescription(tree).setMessage(matcher.getValue()).build();
      }
    }
    return checkTree(tree, ASTHelpers.getSymbol(tree), state);
  }

  @Override
  public Description matchMemberReference(MemberReferenceTree tree, VisitorState state) {
    return checkTree(tree, ASTHelpers.getSymbol(tree), state);
  }

  private Description checkTree(Tree tree, MethodSymbol sym, VisitorState state) {
    if (!hasAnnotation(sym, DO_NOT_CALL, state)) {
      return NO_MATCH;
    }
    String doNotCall = getDoNotCallValue(sym);
    StringBuilder message = new StringBuilder("This method should not be called");
    if (doNotCall.isEmpty()) {
      message.append(", see its documentation for details.");
    } else {
      message.append(": ").append(doNotCall);
    }
    return buildDescription(tree).setMessage(message.toString()).build();
  }

  private static String getDoNotCallValue(Symbol symbol) {
    for (Attribute.Compound a : symbol.getRawAttributes()) {
      if (!a.type.tsym.getQualifiedName().contentEquals(DO_NOT_CALL)) {
        continue;
      }
      return MoreAnnotations.getAnnotationValue(a, "value")
          .flatMap(MoreAnnotations::asStringValue)
          .orElse("");
    }
    throw new IllegalStateException();
  }
}
