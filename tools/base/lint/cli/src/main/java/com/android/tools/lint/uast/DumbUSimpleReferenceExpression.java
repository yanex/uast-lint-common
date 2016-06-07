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

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import org.jetbrains.uast.UDeclaration;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.USimpleReferenceExpression;
import org.jetbrains.uast.UType;
import org.jetbrains.uast.UastContext;
import org.jetbrains.uast.visitor.UastVisitor;

import java.util.List;

class DumbUSimpleReferenceExpression implements USimpleReferenceExpression {
    private final String identifier;
    private final UElement parent;

    DumbUSimpleReferenceExpression(String identifier, UElement parent) {
        this.identifier = identifier;
        this.parent = parent;
    }

    @NonNull
    @Override
    public String getIdentifier() {
        return identifier;
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
        return null;
    }

    @Override
    public void accept(UastVisitor uastVisitor) {
        USimpleReferenceExpression.DefaultImpls.accept(this, uastVisitor);
    }

    @NonNull
    @Override
    public String logString() {
        return USimpleReferenceExpression.DefaultImpls.logString(this);
    }

    @NonNull
    @Override
    public String renderString() {
        return USimpleReferenceExpression.DefaultImpls.renderString(this);
    }

    @Override
    public boolean matchesIdentifier(String s) {
        return USimpleReferenceExpression.DefaultImpls.matchesIdentifier(this, s);
    }

    @Nullable
    @Override
    public Object evaluate() {
        return USimpleReferenceExpression.DefaultImpls.evaluate(this);
    }

    @Nullable
    @Override
    public String evaluateString() {
        return USimpleReferenceExpression.DefaultImpls.evaluateString(this);
    }

    @Override
    public boolean isStatement() {
        return USimpleReferenceExpression.DefaultImpls.isStatement(this);
    }

    @Nullable
    @Override
    public UType getExpressionType() {
        return USimpleReferenceExpression.DefaultImpls.getExpressionType(this);
    }

    @NonNull
    @Override
    public String originalString() {
        return USimpleReferenceExpression.DefaultImpls.originalString(this);
    }

    @Override
    public boolean isValid() {
        return USimpleReferenceExpression.DefaultImpls.isValid(this);
    }

    @NonNull
    @Override
    public UDeclaration resolveOrEmpty(UastContext uastContext) {
        return USimpleReferenceExpression.DefaultImpls.resolveOrEmpty(this, uastContext);
    }

    @NonNull
    @Override
    public List<String> getComments() {
        return USimpleReferenceExpression.DefaultImpls.getComments(this);
    }
}
