/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.tools.lint.client.api;

import static com.android.tools.lint.detector.api.LintConstants.ANDROID_PKG;
import static com.android.tools.lint.detector.api.LintConstants.R_CLASS;

import com.android.annotations.NonNull;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Detector.JavaScanner;
import com.android.tools.lint.detector.api.Detector.XmlScanner;
import com.android.tools.lint.detector.api.JavaContext;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.ast.AlternateConstructorInvocation;
import lombok.ast.Annotation;
import lombok.ast.AnnotationDeclaration;
import lombok.ast.AnnotationElement;
import lombok.ast.AnnotationMethodDeclaration;
import lombok.ast.AnnotationValueArray;
import lombok.ast.ArrayAccess;
import lombok.ast.ArrayCreation;
import lombok.ast.ArrayDimension;
import lombok.ast.ArrayInitializer;
import lombok.ast.Assert;
import lombok.ast.AstVisitor;
import lombok.ast.BinaryExpression;
import lombok.ast.Block;
import lombok.ast.BooleanLiteral;
import lombok.ast.Break;
import lombok.ast.Case;
import lombok.ast.Cast;
import lombok.ast.Catch;
import lombok.ast.CharLiteral;
import lombok.ast.ClassDeclaration;
import lombok.ast.ClassLiteral;
import lombok.ast.Comment;
import lombok.ast.CompilationUnit;
import lombok.ast.ConstructorDeclaration;
import lombok.ast.ConstructorInvocation;
import lombok.ast.Continue;
import lombok.ast.Default;
import lombok.ast.DoWhile;
import lombok.ast.EmptyDeclaration;
import lombok.ast.EmptyStatement;
import lombok.ast.EnumConstant;
import lombok.ast.EnumDeclaration;
import lombok.ast.EnumTypeBody;
import lombok.ast.Expression;
import lombok.ast.ExpressionStatement;
import lombok.ast.FloatingPointLiteral;
import lombok.ast.For;
import lombok.ast.ForEach;
import lombok.ast.ForwardingAstVisitor;
import lombok.ast.Identifier;
import lombok.ast.If;
import lombok.ast.ImportDeclaration;
import lombok.ast.InlineIfExpression;
import lombok.ast.InstanceInitializer;
import lombok.ast.InstanceOf;
import lombok.ast.IntegralLiteral;
import lombok.ast.InterfaceDeclaration;
import lombok.ast.KeywordModifier;
import lombok.ast.LabelledStatement;
import lombok.ast.MethodDeclaration;
import lombok.ast.MethodInvocation;
import lombok.ast.Modifiers;
import lombok.ast.Node;
import lombok.ast.NormalTypeBody;
import lombok.ast.NullLiteral;
import lombok.ast.PackageDeclaration;
import lombok.ast.Return;
import lombok.ast.Select;
import lombok.ast.StaticInitializer;
import lombok.ast.StringLiteral;
import lombok.ast.Super;
import lombok.ast.SuperConstructorInvocation;
import lombok.ast.Switch;
import lombok.ast.Synchronized;
import lombok.ast.This;
import lombok.ast.Throw;
import lombok.ast.Try;
import lombok.ast.TypeReference;
import lombok.ast.TypeReferencePart;
import lombok.ast.TypeVariable;
import lombok.ast.UnaryExpression;
import lombok.ast.VariableDeclaration;
import lombok.ast.VariableDefinition;
import lombok.ast.VariableDefinitionEntry;
import lombok.ast.VariableReference;
import lombok.ast.While;


/**
 * Specialized visitor for running detectors on a Java AST.
 * It operates in three phases:
 * <ol>
 *   <li> First, it computes a set of maps where it generates a map from each
 *        significant AST attribute (such as method call names) to a list
 *        of detectors to consult whenever that attribute is encountered.
 *        Examples of "attributes" are method names, Android resource identifiers,
 *        and general AST node types such as "cast" nodes etc. These are
 *        defined on the {@link JavaScanner} interface.
 *   <li> Second, it iterates over the document a single time, delegating to
 *        the detectors found at each relevant AST attribute.
 *   <li> Finally, it calls the remaining visitors (those that need to process a
 *        whole document on their own).
 * </ol>
 * It also notifies all the detectors before and after the document is processed
 * such that they can do pre- and post-processing.
 */
public class JavaVisitor {
    /** Default size of lists holding detectors of the same type for a given node type */
    private static final int SAME_TYPE_COUNT = 8;

    private final Map<String, List<VisitingDetector>> mMethodDetectors =
            new HashMap<String, List<VisitingDetector>>();
    private final List<VisitingDetector> mResourceFieldDetectors =
            new ArrayList<VisitingDetector>();
    private final List<VisitingDetector> mAllDetectors;
    private final List<VisitingDetector> mFullTreeDetectors;
    private Map<Class<? extends Node>, List<VisitingDetector>> mNodeTypeDetectors =
            new HashMap<Class<? extends Node>, List<VisitingDetector>>();
    private final IJavaParser mParser;

    JavaVisitor(@NonNull IJavaParser parser, @NonNull List<Detector> detectors) {
        mParser = parser;
        mAllDetectors = new ArrayList<VisitingDetector>(detectors.size());
        mFullTreeDetectors = new ArrayList<VisitingDetector>(detectors.size());

        for (Detector detector : detectors) {
            VisitingDetector v = new VisitingDetector(detector, (JavaScanner) detector);
            mAllDetectors.add(v);

            List<Class<? extends Node>> nodeTypes = detector.getApplicableNodeTypes();
            if (nodeTypes != null) {
                for (Class<? extends Node> type : nodeTypes) {
                    List<VisitingDetector> list = mNodeTypeDetectors.get(type);
                    if (list == null) {
                        list = new ArrayList<VisitingDetector>(SAME_TYPE_COUNT);
                        mNodeTypeDetectors.put(type, list);
                    }
                    list.add(v);
                }
            }

            List<String> names = detector.getApplicableMethodNames();
            if (names != null) {
                // not supported in Java visitors; adding a method invocation node is trivial
                // for that case.
                assert names != XmlScanner.ALL;

                for (String name : names) {
                    List<VisitingDetector> list = mMethodDetectors.get(name);
                    if (list == null) {
                        list = new ArrayList<VisitingDetector>(SAME_TYPE_COUNT);
                        mMethodDetectors.put(name, list);
                    }
                    list.add(v);
                }
            }

            if (detector.appliesToResourceRefs()) {
                mResourceFieldDetectors.add(v);
            } else if ((names == null || names.size() == 0)
                    && (nodeTypes == null || nodeTypes.size() ==0)) {
                mFullTreeDetectors.add(v);
            }
        }
    }

    void visitFile(@NonNull JavaContext context, @NonNull File file) {
        context.parser = mParser;

        Node compilationUnit = null;
        try {
            compilationUnit = mParser.parseJava(context);
            if (compilationUnit == null) {
                // No need to log this; the parser should be reporting
                // a full warning (such as IssueRegistry#PARSER_ERROR)
                // with details, location, etc.
                return;
            }
            context.compilationUnit = compilationUnit;

            for (VisitingDetector v : mAllDetectors) {
                v.setContext(context);
                v.getDetector().beforeCheckFile(context);
            }

            for (VisitingDetector v : mFullTreeDetectors) {
                AstVisitor visitor = v.getVisitor();
                if (visitor != null) {
                    compilationUnit.accept(visitor);
                }
            }

            if (mMethodDetectors.size() > 0 || mResourceFieldDetectors.size() > 0) {
                AstVisitor visitor = new DelegatingJavaVisitor(context);
                compilationUnit.accept(visitor);
            } else if (mNodeTypeDetectors.size() > 0) {
                AstVisitor visitor = new DispatchVisitor();
                compilationUnit.accept(visitor);
            }

            for (VisitingDetector v : mAllDetectors) {
                v.getDetector().afterCheckFile(context);
            }
        } finally {
            if (compilationUnit != null) {
                mParser.dispose(context, compilationUnit);
            }
        }
    }

    private static class VisitingDetector {
        private AstVisitor mVisitor; // construct lazily, and clear out on context switch!
        private JavaContext mContext;
        public final Detector mDetector;
        public final JavaScanner mJavaScanner;

        public VisitingDetector(@NonNull Detector detector, @NonNull JavaScanner javaScanner) {
            mDetector = detector;
            mJavaScanner = javaScanner;
        }

        @NonNull
        public Detector getDetector() {
            return mDetector;
        }

        @NonNull
        public JavaScanner getJavaScanner() {
            return mJavaScanner;
        }

        public void setContext(@NonNull JavaContext context) {
            mContext = context;

            // The visitors are one-per-context, so clear them out here and construct
            // lazily only if needed
            mVisitor = null;
        }

        @NonNull
        AstVisitor getVisitor() {
            if (mVisitor == null) {
                mVisitor = mDetector.createJavaVisitor(mContext);
                if (mVisitor == null) {
                    mVisitor = new ForwardingAstVisitor() {
                    };
                }
            }
            return mVisitor;
        }
    }

    /**
     * Generic dispatcher which visits all nodes (once) and dispatches to
     * specific visitors for each node. Each visitor typically only wants to
     * look at a small part of a tree, such as a method call or a class
     * declaration, so this means we avoid visiting all "uninteresting" nodes in
     * the tree repeatedly.
     */
    private class DispatchVisitor extends AstVisitor {
        @Override
        public void endVisit(Node node) {
        }

        @Override
        public boolean visitAlternateConstructorInvocation(AlternateConstructorInvocation node) {
            List<VisitingDetector> list =
                    mNodeTypeDetectors.get(AlternateConstructorInvocation.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitAlternateConstructorInvocation(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitAnnotation(Annotation node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Annotation.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitAnnotation(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitAnnotationDeclaration(AnnotationDeclaration node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(AnnotationDeclaration.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitAnnotationDeclaration(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitAnnotationElement(AnnotationElement node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(AnnotationElement.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitAnnotationElement(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitAnnotationMethodDeclaration(AnnotationMethodDeclaration node) {
            List<VisitingDetector> list =
                    mNodeTypeDetectors.get(AnnotationMethodDeclaration.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitAnnotationMethodDeclaration(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitAnnotationValueArray(AnnotationValueArray node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(AnnotationValueArray.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitAnnotationValueArray(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitArrayAccess(ArrayAccess node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(ArrayAccess.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitArrayAccess(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitArrayCreation(ArrayCreation node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(ArrayCreation.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitArrayCreation(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitArrayDimension(ArrayDimension node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(ArrayDimension.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitArrayDimension(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitArrayInitializer(ArrayInitializer node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(ArrayInitializer.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitArrayInitializer(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitAssert(Assert node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Assert.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitAssert(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitBinaryExpression(BinaryExpression node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(BinaryExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitBinaryExpression(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitBlock(Block node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Block.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitBlock(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitBooleanLiteral(BooleanLiteral node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(BooleanLiteral.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitBooleanLiteral(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitBreak(Break node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Break.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitBreak(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitCase(Case node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Case.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitCase(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitCast(Cast node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Cast.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitCast(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitCatch(Catch node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Catch.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitCatch(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitCharLiteral(CharLiteral node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(CharLiteral.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitCharLiteral(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitClassDeclaration(ClassDeclaration node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(ClassDeclaration.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitClassDeclaration(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitClassLiteral(ClassLiteral node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(ClassLiteral.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitClassLiteral(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitComment(Comment node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Comment.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitComment(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitCompilationUnit(CompilationUnit node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(CompilationUnit.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitCompilationUnit(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitConstructorDeclaration(ConstructorDeclaration node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(ConstructorDeclaration.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitConstructorDeclaration(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitConstructorInvocation(ConstructorInvocation node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(ConstructorInvocation.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitConstructorInvocation(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitContinue(Continue node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Continue.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitContinue(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitDefault(Default node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Default.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitDefault(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitDoWhile(DoWhile node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(DoWhile.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitDoWhile(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitEmptyDeclaration(EmptyDeclaration node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(EmptyDeclaration.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitEmptyDeclaration(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitEmptyStatement(EmptyStatement node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(EmptyStatement.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitEmptyStatement(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitEnumConstant(EnumConstant node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(EnumConstant.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitEnumConstant(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitEnumDeclaration(EnumDeclaration node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(EnumDeclaration.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitEnumDeclaration(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitEnumTypeBody(EnumTypeBody node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(EnumTypeBody.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitEnumTypeBody(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitExpressionStatement(ExpressionStatement node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(ExpressionStatement.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitExpressionStatement(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitFloatingPointLiteral(FloatingPointLiteral node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(FloatingPointLiteral.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitFloatingPointLiteral(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitFor(For node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(For.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitFor(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitForEach(ForEach node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(ForEach.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitForEach(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitIdentifier(Identifier node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Identifier.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitIdentifier(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitIf(If node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(If.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitIf(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitImportDeclaration(ImportDeclaration node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(ImportDeclaration.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitImportDeclaration(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitInlineIfExpression(InlineIfExpression node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(InlineIfExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitInlineIfExpression(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitInstanceInitializer(InstanceInitializer node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(InstanceInitializer.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitInstanceInitializer(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitInstanceOf(InstanceOf node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(InstanceOf.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitInstanceOf(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitIntegralLiteral(IntegralLiteral node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(IntegralLiteral.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitIntegralLiteral(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitInterfaceDeclaration(InterfaceDeclaration node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(InterfaceDeclaration.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitInterfaceDeclaration(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitKeywordModifier(KeywordModifier node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(KeywordModifier.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitKeywordModifier(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitLabelledStatement(LabelledStatement node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(LabelledStatement.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitLabelledStatement(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitMethodDeclaration(MethodDeclaration node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(MethodDeclaration.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitMethodDeclaration(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitMethodInvocation(MethodInvocation node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(MethodInvocation.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitMethodInvocation(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitModifiers(Modifiers node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Modifiers.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitModifiers(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitNormalTypeBody(NormalTypeBody node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(NormalTypeBody.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitNormalTypeBody(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitNullLiteral(NullLiteral node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(NullLiteral.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitNullLiteral(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitPackageDeclaration(PackageDeclaration node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(PackageDeclaration.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitPackageDeclaration(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitParseArtefact(Node node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Node.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitParseArtefact(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitReturn(Return node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Return.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitReturn(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitSelect(Select node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Select.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitSelect(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitStaticInitializer(StaticInitializer node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(StaticInitializer.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitStaticInitializer(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitStringLiteral(StringLiteral node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(StringLiteral.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitStringLiteral(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitSuper(Super node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Super.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitSuper(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitSuperConstructorInvocation(SuperConstructorInvocation node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(SuperConstructorInvocation.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitSuperConstructorInvocation(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitSwitch(Switch node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Switch.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitSwitch(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitSynchronized(Synchronized node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Synchronized.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitSynchronized(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitThis(This node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(This.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitThis(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitThrow(Throw node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Throw.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitThrow(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitTry(Try node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(Try.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitTry(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitTypeReference(TypeReference node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(TypeReference.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitTypeReference(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitTypeReferencePart(TypeReferencePart node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(TypeReferencePart.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitTypeReferencePart(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitTypeVariable(TypeVariable node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(TypeVariable.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitTypeVariable(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitUnaryExpression(UnaryExpression node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(UnaryExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitUnaryExpression(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitVariableDeclaration(VariableDeclaration node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(VariableDeclaration.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitVariableDeclaration(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitVariableDefinition(VariableDefinition node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(VariableDefinition.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitVariableDefinition(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitVariableDefinitionEntry(VariableDefinitionEntry node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(VariableDefinitionEntry.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitVariableDefinitionEntry(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitVariableReference(VariableReference node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(VariableReference.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitVariableReference(node);
                }
            }
            return false;
        }

        @Override
        public boolean visitWhile(While node) {
            List<VisitingDetector> list = mNodeTypeDetectors.get(While.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitWhile(node);
                }
            }
            return false;
        }
    }

    /** Performs common AST searches for method calls and R-type-field references.
     * Note that this is a specialized form of the {@link DispatchVisitor}. */
    private class DelegatingJavaVisitor extends DispatchVisitor {
        private final JavaContext mContext;
        private final boolean mVisitResources;
        private final boolean mVisitMethods;

        public DelegatingJavaVisitor(JavaContext context) {
            mContext = context;

            mVisitMethods = mMethodDetectors.size() > 0;
            mVisitResources = mResourceFieldDetectors.size() > 0;
        }

        @Override
        public boolean visitSelect(Select node) {
            if (mVisitResources) {
                // R.type.name
                if (node.astOperand() instanceof Select) {
                    Select select = (Select) node.astOperand();
                    if (select.astOperand() instanceof VariableReference) {
                        VariableReference reference = (VariableReference) select.astOperand();
                        if (reference.astIdentifier().astValue().equals(R_CLASS)) {
                            String type = select.astIdentifier().astValue();
                            String name = node.astIdentifier().astValue();

                            // R -could- be referenced locally and really have been
                            // imported as "import android.R;" in the import statements,
                            // but this is not recommended (and in fact there's a specific
                            // lint rule warning against it)
                            boolean isFramework = false;

                            for (VisitingDetector v : mResourceFieldDetectors) {
                                JavaScanner detector = v.getJavaScanner();
                                detector.visitResourceReference(mContext, v.getVisitor(),
                                        node, type, name, isFramework);
                            }

                            return super.visitSelect(node);
                        }
                    }
                }

                // Arbitrary packages -- android.R.type.name, foo.bar.R.type.name
                if (node.astIdentifier().astValue().equals(R_CLASS)) {
                    Node parent = node.getParent();
                    if (parent instanceof Select) {
                        Node grandParent = parent.getParent();
                        if (grandParent instanceof Select) {
                            Select select = (Select) grandParent;
                            String name = select.astIdentifier().astValue();
                            Expression typeOperand = select.astOperand();
                            if (typeOperand instanceof Select) {
                                Select typeSelect = (Select) typeOperand;
                                String type = typeSelect.astIdentifier().astValue();
                                boolean isFramework =
                                        node.astIdentifier().astValue().equals(ANDROID_PKG);
                                for (VisitingDetector v : mResourceFieldDetectors) {
                                    JavaScanner detector = v.getJavaScanner();
                                    detector.visitResourceReference(mContext, v.getVisitor(),
                                            node, type, name, isFramework);
                                }
                            }
                        }
                    }
                }
            }

            return super.visitSelect(node);
        }

        @Override
        public boolean visitMethodInvocation(MethodInvocation node) {
            if (mVisitMethods) {
                String methodName = node.astName().getDescription();
                List<VisitingDetector> list = mMethodDetectors.get(methodName);
                if (list != null) {
                    for (VisitingDetector v : list) {
                        v.getJavaScanner().visitMethod(mContext, v.getVisitor(), node);
                    }
                }
            }

            return super.visitMethodInvocation(node);
        }
    }
}
