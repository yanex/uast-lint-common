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
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;

import org.jetbrains.uast.UAnnotated;
import org.jetbrains.uast.UAnnotation;
import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UConstantValue;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UNamedExpression;
import org.jetbrains.uast.UastContext;
import org.jetbrains.uast.visitor.UastVisitor;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class DumbUAnnotation implements UAnnotation {
    private final String fqName;
    private final String name;
    private final Map<String, UConstantValue<?>> values;
    private final List<UNamedExpression> valueArguments;
    private final UElement parent;

    public DumbUAnnotation(String fqName,
            Map<String, UConstantValue<?>> values,
            List<UNamedExpression> valueArguments, UElement parent) {
        this.fqName = fqName;
        this.values = values;
        this.valueArguments = valueArguments;
        this.parent = parent;

        int i = fqName.lastIndexOf('.');
        if (i < 0) {
            name = fqName;
        } else {
            name = fqName.substring(i + 1);
        }
    }

    @Nullable
    @Override
    public UClass resolve(UastContext uastContext) {
        if (!(uastContext instanceof JavaContext)) {
            return null;
        }

        JavaContext context = (JavaContext) uastContext;
        Project ideaProject = context.getParser().getIdeaProject();
        if (ideaProject == null) {
            return null;
        }

        GlobalSearchScope scope = GlobalSearchScope.allScope(ideaProject);
        PsiClass psiClass = JavaPsiFacade.getInstance(ideaProject).findClass(fqName, scope);
        if (psiClass == null) {
            return null;
        }

        UElement uelement = uastContext.convert(psiClass);
        if (uelement instanceof UClass) {
            return (UClass) uelement;
        } else {
            return null;
        }
    }

    @NonNull
    @Override
    public List<UNamedExpression> getValueArguments() {
        return valueArguments;
    }

    @Override
    public int getValueArgumentsCount() {
        return valueArguments.size();
    }

    @Nullable
    @Override
    public UElement getNameElement() {
        return null;
    }

    @Nullable
    @Override
    public UConstantValue<?> getValue(String s) {
        if (s == null) {
            Iterator<UConstantValue<?>> iterator = values.values().iterator();
            if (iterator.hasNext()) {
                return iterator.next();
            }
            return null;
        }
        return values.get(s);
    }

    @NonNull
    @Override
    public Map<String, UConstantValue<?>> getValues() {
        return values;
    }

    @Nullable
    @Override
    public String getFqName() {
        return fqName;
    }

    @NonNull
    @Override
    public String getName() {
        return name;
    }

    @Nullable
    @Override
    public UElement getParent() {
        return parent;
    }

    @Override
    public boolean matchesName(String s) {
        return UAnnotation.DefaultImpls.matchesName(this, s);
    }

    @Override
    public boolean matchesFqName(String s) {
        return UAnnotation.DefaultImpls.matchesFqName(this, s);
    }

    @NonNull
    @Override
    public String originalString() {
        return UAnnotation.DefaultImpls.originalString(this);
    }

    @Override
    public boolean isValid() {
        return UAnnotation.DefaultImpls.isValid(this);
    }

    @NonNull
    @Override
    public List<String> getComments() {
        return UAnnotation.DefaultImpls.getComments(this);
    }

    @NonNull
    @Override
    public String logString() {
        return UAnnotation.DefaultImpls.logString(this);
    }

    @NonNull
    @Override
    public String renderString() {
        return UAnnotation.DefaultImpls.renderString(this);
    }

    @Override
    public void accept(UastVisitor uastVisitor) {
        UAnnotation.DefaultImpls.accept(this, uastVisitor);
    }
}
