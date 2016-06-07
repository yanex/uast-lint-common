/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.tools.lint.uast;

import com.android.tools.lint.detector.api.JavaContext;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.search.GlobalSearchScope;

import org.jetbrains.uast.UDeclaration;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UQualifiedExpression;
import org.jetbrains.uast.UType;
import org.jetbrains.uast.UastContext;
import org.jetbrains.uast.UastQualifiedExpressionAccessType;
import org.jetbrains.uast.visitor.UastVisitor;

import java.util.List;

public class DumbUQualifiedExpression implements UQualifiedExpression {
    private UExpression receiver;
    private UExpression selector;
    private UElement parent;

    public void setReceiver(UExpression receiver) {
        this.receiver = receiver;
    }

    public void setSelector(UExpression selector) {
        this.selector = selector;
    }

    public void setParent(UElement parent) {
        this.parent = parent;
    }

    public static UExpression make(String fqName, UElement parent) {
        assert !fqName.isEmpty();

        List<String> parts = StringUtil.split(fqName, ".");
        if (parts.size() == 1) {
            return new DumbUSimpleReferenceExpression(parts.get(0), parent);
        } else {
            return make(dropLast(parts), parts.get(parts.size() - 1), parent);
        }
    }

    private static UExpression make(List<String> parts, String last, UElement parent) {
        DumbUQualifiedExpression expr = new DumbUQualifiedExpression();
        expr.receiver = parts.size() == 1
                ? make(parts.get(0), expr)
                : make(dropLast(parts), parts.get(parts.size() - 1), parent);
        expr.selector = make(last, expr);
        expr.parent = parent;
        return expr;
    }

    private static <T> List<T> dropLast(List<T> list) {
        return list.subList(0, list.size() - 1);
    }

    @NonNull
    @Override
    public UExpression getReceiver() {
        return receiver;
    }

    @NonNull
    @Override
    public UExpression getSelector() {
        return selector;
    }

    @NonNull
    @Override
    public UastQualifiedExpressionAccessType getAccessType() {
        return UastQualifiedExpressionAccessType.SIMPLE;
    }

    @Nullable
    @Override
    public UElement getParent() {
        return parent;
    }

    @Nullable
    @Override
    public UDeclaration resolve(UastContext uastContext) {
        if (parent instanceof DumbUQualifiedExpression) {
            return ((DumbUQualifiedExpression) parent).resolve(uastContext);
        }

        if (!(uastContext instanceof JavaContext)) {
            return null;
        }

        JavaContext context = (JavaContext) uastContext;
        Project ideaProject = context.getParser().getIdeaProject();
        if (ideaProject == null) {
            return null;
        }

        GlobalSearchScope scope = GlobalSearchScope.allScope(ideaProject);

        PsiElementFactory factory = PsiElementFactory.SERVICE.getInstance(ideaProject);
        // We need a context reference element to create an expression
        PsiJavaCodeReferenceElement reference = factory
                .createReferenceElementByFQClassName("java.lang.String", scope);
        PsiExpression expression = factory.createExpressionFromText(renderString(), reference);
        if (expression instanceof PsiReferenceExpression) {
            UElement uelement = uastContext.convert(((PsiReferenceExpression) expression).resolve());
            if (uelement instanceof UDeclaration) {
                return (UDeclaration) uelement;
            }
        }

        return null;
    }

    @Nullable
    @Override
    public String getIdentifier() {
        return UQualifiedExpression.DefaultImpls.getIdentifier(this);
    }

    @NonNull
    @Override
    public String renderString() {
        return UQualifiedExpression.DefaultImpls.renderString(this);
    }

    @Override
    public void accept(UastVisitor uastVisitor) {
        UQualifiedExpression.DefaultImpls.accept(this, uastVisitor);
    }

    @NonNull
    @Override
    public String logString() {
        return UQualifiedExpression.DefaultImpls.logString(this);
    }

    @Override
    public boolean matchesIdentifier(String s) {
        return UQualifiedExpression.DefaultImpls.matchesIdentifier(this, s);
    }

    @Nullable
    @Override
    public Object evaluate() {
        return UQualifiedExpression.DefaultImpls.evaluate(this);
    }

    @Nullable
    @Override
    public String evaluateString() {
        return UQualifiedExpression.DefaultImpls.evaluateString(this);
    }

    @NonNull
    @Override
    public UDeclaration resolveOrEmpty(UastContext uastContext) {
        return UQualifiedExpression.DefaultImpls.resolveOrEmpty(this, uastContext);
    }

    @NonNull
    @Override
    public String originalString() {
        return UQualifiedExpression.DefaultImpls.originalString(this);
    }

    @NonNull
    @Override
    public List<String> getComments() {
        return UQualifiedExpression.DefaultImpls.getComments(this);
    }

    @Override
    public boolean isValid() {
        return UQualifiedExpression.DefaultImpls.isValid(this);
    }

    @Nullable
    @Override
    public UType getExpressionType() {
        return UQualifiedExpression.DefaultImpls.getExpressionType(this);
    }

    @Override
    public boolean isStatement() {
        return UQualifiedExpression.DefaultImpls.isStatement(this);
    }
}
