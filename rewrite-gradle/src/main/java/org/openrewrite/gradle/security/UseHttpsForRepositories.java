/*
 * Copyright 2022 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle.security;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.internal.ChangeStringLiteral;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.kotlin.tree.K;

import java.time.Duration;
import java.util.Set;

import static java.util.Collections.singleton;

public class UseHttpsForRepositories extends Recipe {

    @Override
    public String getDisplayName() {
        return "Use HTTPS for repositories";
    }

    @Override
    public String getDescription() {
        return "Use HTTPS for repository URLs.";
    }

    @Override
    public Set<String> getTags() {
        return singleton("security");
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(5);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<ExecutionContext>() {
            private J.Literal fixupLiteralIfNeeded(J.Literal arg) {
                String url = (String) arg.getValue();
                //noinspection HttpUrlsUsage
                if (url != null && url.startsWith("http://")) {
                    String newUrl = url.replaceAll("^http://(.*)", "https://$1");
                    return ChangeStringLiteral.withStringValue(arg, newUrl);
                }
                return arg;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if ("url".equals(m.getSimpleName()) || "uri".equals(m.getSimpleName())) {
                    try {
                        getCursor()
                                .dropParentUntil(e -> e instanceof J.MethodInvocation && "maven".equals(((J.MethodInvocation) e).getSimpleName()))
                                .dropParentUntil(e -> e instanceof J.MethodInvocation && "repositories".equals(((J.MethodInvocation) e).getSimpleName()));
                        m = m.withArguments(ListUtils.mapFirst(m.getArguments(), arg -> {
                            if (arg instanceof J.Literal) {
                                return fixupLiteralIfNeeded((J.Literal) arg);
                            } else if (arg instanceof G.GString) {
                                G.GString garg = (G.GString) arg;
                                return garg.withStrings(ListUtils.mapFirst(garg.getStrings(),
                                        lit -> lit instanceof J.Literal ? fixupLiteralIfNeeded((J.Literal) lit) : lit));
                            } else if (arg instanceof K.StringTemplate) {
                                K.StringTemplate karg = (K.StringTemplate) arg;
                                return karg.withStrings(ListUtils.mapFirst(karg.getStrings(),
                                        lit -> lit instanceof J.Literal ? fixupLiteralIfNeeded((J.Literal) lit) : lit));
                            }
                            return arg;
                        }));
                    } catch (Exception ignored) {}
                }
                return m;
            }
        });
    }
}
