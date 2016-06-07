/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.tools.lint.checks;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.tools.lint.client.api.JavaEvaluator;
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;

import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UFunction;
import org.jetbrains.uast.UType;
import org.jetbrains.uast.visitor.UastVisitor;

import java.util.Arrays;
import java.util.List;

public class SslCertificateSocketFactoryDetector extends Detector
        implements Detector.UastScanner {

    private static final Implementation IMPLEMENTATION_JAVA = new Implementation(
            SslCertificateSocketFactoryDetector.class,
            Scope.JAVA_FILE_SCOPE);

    public static final Issue CREATE_SOCKET = Issue.create(
            "SSLCertificateSocketFactoryCreateSocket", //$NON-NLS-1$
            "Insecure call to `SSLCertificateSocketFactory.createSocket()`",
            "When `SSLCertificateSocketFactory.createSocket()` is called with an `InetAddress` " +
            "as the first parameter, TLS/SSL hostname verification is not performed, which " +
            "could result in insecure network traffic caused by trusting arbitrary " +
            "hostnames in TLS/SSL certificates presented by peers. In this case, developers " +
            "must ensure that the `InetAddress` is explicitly verified against the certificate " +
            "through other means, such as by calling " +
            "`SSLCertificateSocketFactory.getDefaultHostnameVerifier() to get a " +
            "`HostnameVerifier` and calling `HostnameVerifier.verify()`.",
            Category.SECURITY,
            6,
            Severity.WARNING,
            IMPLEMENTATION_JAVA);

    public static final Issue GET_INSECURE = Issue.create(
            "SSLCertificateSocketFactoryGetInsecure", //$NON-NLS-1$
            "Call to `SSLCertificateSocketFactory.getInsecure()`",
            "The `SSLCertificateSocketFactory.getInsecure()` method returns " +
            "an SSLSocketFactory with all TLS/SSL security checks disabled, which " +
            "could result in insecure network traffic caused by trusting arbitrary " +
            "TLS/SSL certificates presented by peers. This method should be " +
            "avoided unless needed for a special circumstance such as " +
            "debugging. Instead, `SSLCertificateSocketFactory.getDefault()` " +
            "should be used.",
            Category.SECURITY,
            6,
            Severity.WARNING,
            IMPLEMENTATION_JAVA);

    private static final String INET_ADDRESS_CLASS =
            "java.net.InetAddress";

    private static final String SSL_CERTIFICATE_SOCKET_FACTORY_CLASS =
            "android.net.SSLCertificateSocketFactory";

    // ---- Implements UastScanner ----

    @Override
    public List<String> getApplicableFunctionNames() {
        // Detect calls to potentially insecure SSLCertificateSocketFactory methods
        return Arrays.asList("createSocket", "getInsecure");
    }

    @Override
    public void visitFunctionCallExpression(@NonNull JavaContext context,
            @Nullable UastVisitor visitor, @NonNull UCallExpression call,
            @NonNull UFunction function) {
        if (JavaEvaluator.isMemberInSubClassOf(function,
                SSL_CERTIFICATE_SOCKET_FACTORY_CLASS, false)) {
            if (function.matchesName("createSocket")) {
                List<UExpression> args = call.getValueArguments();
                if (!args.isEmpty()) {
                    UType type = args.get(0).getExpressionType();
                    if (type != null
                            && ((type.matchesFqName(INET_ADDRESS_CLASS))
                            || type.resolveToClassOrEmpty(context).isSubclassOf(INET_ADDRESS_CLASS, false))) {
                        context.report(CREATE_SOCKET, call, context.getLocation(call),
                                "Use of `SSLCertificateSocketFactory.createSocket()` " +
                                        "with an InetAddress parameter can cause insecure " +
                                        "network traffic due to trusting arbitrary hostnames in " +
                                        "TLS/SSL certificates presented by peers");
                    }
                }
            } else if (function.matchesName("getInsecure")) {
                context.report(GET_INSECURE, call, context.getLocation(call),
                        "Use of `SSLCertificateSocketFactory.getInsecure()` can cause " +
                                "insecure network traffic due to trusting arbitrary TLS/SSL " +
                                "certificates presented by peers");
            }
        }

    }
}
