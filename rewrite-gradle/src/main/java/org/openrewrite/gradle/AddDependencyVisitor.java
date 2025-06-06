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
package org.openrewrite.gradle;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.gradle.internal.InsertDependencyComparator;
import org.openrewrite.gradle.marker.GradleDependencyConfiguration;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.Markup;
import org.openrewrite.maven.MavenDownloadingException;
import org.openrewrite.maven.MavenDownloadingExceptions;
import org.openrewrite.maven.internal.MavenPomDownloader;
import org.openrewrite.maven.table.MavenMetadataFailures;
import org.openrewrite.maven.tree.*;
import org.openrewrite.tree.ParseError;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;

@RequiredArgsConstructor
public class AddDependencyVisitor extends JavaIsoVisitor<ExecutionContext> {
    private static final MethodMatcher DEPENDENCIES_DSL_MATCHER = new MethodMatcher("RewriteGradleProject dependencies(..)");
    private static final GradleParser GRADLE_PARSER = GradleParser.builder().build();

    private final String groupId;
    private final String artifactId;

    @Nullable
    private final String version;

    @Nullable
    private final String versionPattern;

    private final String configuration;

    @Nullable
    private final String classifier;

    @Nullable
    private final String extension;

    @Nullable
    private final MavenMetadataFailures metadataFailures;

    @Nullable
    private String resolvedVersion;

    @Nullable
    private final Predicate<Cursor> insertPredicate;

    @Override
    public @Nullable J visit(@Nullable Tree tree, ExecutionContext ctx) {
        if (tree instanceof JavaSourceFile) {
            JavaSourceFile cu = (JavaSourceFile) super.visit(tree, ctx);
            Optional<GradleProject> maybeGp = cu.getMarkers().findFirst(GradleProject.class);
            if (!maybeGp.isPresent()) {
                return cu;
            }

            GradleProject gp = maybeGp.get();
            GradleDependencyConfiguration gdc = gp.getConfiguration(configuration);
            if (gdc == null || gdc.findRequestedDependency(groupId, artifactId) != null) {
                return cu;
            }

            boolean dependenciesBlockMissing = true;
            if (cu instanceof G.CompilationUnit) {
                G.CompilationUnit g = (G.CompilationUnit) cu;
                for (Statement statement : g.getStatements()) {
                    if (statement instanceof J.MethodInvocation && DEPENDENCIES_DSL_MATCHER.matches((J.MethodInvocation) statement)) {
                        dependenciesBlockMissing = false;
                    }
                }
            } else if (cu instanceof K.CompilationUnit) {
                K.CompilationUnit k = (K.CompilationUnit) cu;
                for (Statement statement : ((J.Block) k.getStatements().get(0)).getStatements()) {
                    if (statement instanceof J.MethodInvocation && ((J.MethodInvocation) statement).getSimpleName().equals("dependencies")) {
                        dependenciesBlockMissing = false;
                    }
                }
            }

            if (dependenciesBlockMissing) {
                if (cu instanceof G.CompilationUnit) {
                    G.CompilationUnit g = (G.CompilationUnit) cu;
                    Statement dependenciesInvocation = GRADLE_PARSER.parse(ctx, "dependencies {}")
                            .findFirst()
                            .map(G.CompilationUnit.class::cast)
                            .orElseThrow(() -> new IllegalArgumentException("Could not parse as Gradle"))
                            .getStatements().get(0);
                    Cursor parent = getCursor();
                    setCursor(new Cursor(parent, g));
                    dependenciesInvocation = autoFormat(dependenciesInvocation, ctx, new Cursor(parent, g));
                    setCursor(parent);
                    cu = g.withStatements(ListUtils.concat(g.getStatements(),
                            g.getStatements().isEmpty() ?
                                    dependenciesInvocation :
                                    dependenciesInvocation.withPrefix(Space.format("\n\n"))));
                } else {
                    K.CompilationUnit k = (K.CompilationUnit) cu;
                    J.MethodInvocation dependenciesInvocation = (J.MethodInvocation) ((J.Block) GRADLE_PARSER.parseInputs(Collections.singletonList(new GradleParser.Input(Paths.get("build.gradle.kts"), () -> new ByteArrayInputStream("dependencies {}".getBytes(StandardCharsets.UTF_8)))), null, ctx)
                            .findFirst()
                            .map(K.CompilationUnit.class::cast)
                            .orElseThrow(() -> new IllegalArgumentException("Could not parse as Gradle"))
                            .getStatements().get(0)).getStatements().get(0);
                    Cursor parent = getCursor();
                    setCursor(new Cursor(parent, k));
                    dependenciesInvocation = autoFormat(dependenciesInvocation, ctx, new Cursor(getCursor(), k));
                    setCursor(parent);
                    dependenciesInvocation = dependenciesInvocation.withArguments(ListUtils.mapFirst(dependenciesInvocation.getArguments(), arg -> {
                        J.Lambda lambda = (J.Lambda) requireNonNull(arg);
                        J.Block block = (J.Block) lambda.getBody();
                        return lambda.withBody(block.withEnd(Space.format("\n")));
                    }));
                    cu = k.withStatements(ListUtils.concat(k.getStatements(),
                            k.getStatements().isEmpty() ?
                                    dependenciesInvocation :
                                    dependenciesInvocation.withPrefix(Space.format("\n\n"))));
                }
            }

            cu = (JavaSourceFile) new InsertDependencyInOrder(configuration, gp)
                    .visitNonNull(cu, ctx);

            if (cu != tree) {
                String versionWithPattern = StringUtils.isBlank(resolvedVersion) || resolvedVersion.startsWith("$") ? null : resolvedVersion;
                cu = addDependency(cu,
                        gdc,
                        new GroupArtifactVersion(groupId, artifactId, versionWithPattern),
                        classifier,
                        ctx);
            }
            return cu;
        }
        return super.visit(tree, ctx);
    }

    /**
     * Update the dependency model, adding the specified dependency to the specified configuration and all configurations
     * which extend from it.
     *
     * @param buildScript   compilation unit owning the {@link GradleProject} marker
     * @param configuration the configuration to add the dependency to
     * @param gav           the group, artifact, and version of the dependency to add
     * @param classifier    the classifier of the dependency to add
     * @param ctx           context which will be used to download the pom for the dependency
     * @return a copy of buildScript with the dependency added
     */
    static JavaSourceFile addDependency(
            JavaSourceFile buildScript,
            @Nullable GradleDependencyConfiguration configuration,
            GroupArtifactVersion gav,
            @Nullable String classifier,
            ExecutionContext ctx) {
        if (gav.getGroupId() == null || gav.getArtifactId() == null || configuration == null) {
            return buildScript;
        }
        GradleProject gp = buildScript.getMarkers().findFirst(GradleProject.class)
                .orElseThrow(() -> new IllegalArgumentException("Could not find GradleProject"));

        try {
            ResolvedGroupArtifactVersion resolvedGav;
            List<ResolvedDependency> transitiveDependencies;
            if (gav.getVersion() == null) {
                resolvedGav = null;
                transitiveDependencies = Collections.emptyList();
            } else {
                MavenPomDownloader mpd = new MavenPomDownloader(ctx);
                Pom pom = mpd.download(gav, null, null, gp.getMavenRepositories());
                ResolvedPom resolvedPom = pom.resolve(emptyList(), mpd, gp.getMavenRepositories(), ctx);
                resolvedGav = resolvedPom.getGav();
                transitiveDependencies = resolvedPom.resolveDependencies(Scope.Runtime, mpd, ctx);
            }
            Map<String, GradleDependencyConfiguration> nameToConfiguration = gp.getNameToConfiguration();
            Map<String, GradleDependencyConfiguration> newNameToConfiguration = new HashMap<>(nameToConfiguration.size());

            Set<GradleDependencyConfiguration> configurationsToAdd = Stream.concat(
                            Stream.of(configuration),
                            gp.configurationsExtendingFrom(configuration, true).stream())
                    .collect(Collectors.toSet());

            for (GradleDependencyConfiguration gdc : nameToConfiguration.values()) {
                if (!configurationsToAdd.contains(gdc)) {
                    newNameToConfiguration.put(gdc.getName(), gdc);
                    continue;
                }

                GradleDependencyConfiguration newGdc = gdc;
                org.openrewrite.maven.tree.Dependency newRequested = new org.openrewrite.maven.tree.Dependency(
                        gav, classifier, "jar", gdc.getName(), emptyList(), null);
                newGdc = newGdc.withRequested(ListUtils.concat(
                        ListUtils.map(gdc.getRequested(), requested -> {
                            // Remove any existing dependency with the same group and artifact id
                            if (Objects.equals(requested.getGroupId(), gav.getGroupId()) && Objects.equals(requested.getArtifactId(), gav.getArtifactId())) {
                                return null;
                            }
                            return requested;
                        }),
                        newRequested));
                if (newGdc.isCanBeResolved() && resolvedGav != null) {
                    newGdc = newGdc.withDirectResolved(ListUtils.concat(
                            ListUtils.map(gdc.getDirectResolved(), resolved -> {
                                // Remove any existing dependency with the same group and artifact id
                                if (Objects.equals(resolved.getGroupId(), resolvedGav.getGroupId()) && Objects.equals(resolved.getArtifactId(), resolvedGav.getArtifactId())) {
                                    return null;
                                }
                                return resolved;
                            }),
                            new ResolvedDependency(null, resolvedGav, newRequested, transitiveDependencies,
                                    emptyList(), "jar", classifier, null, 0, null)));
                }
                newNameToConfiguration.put(newGdc.getName(), newGdc);
            }
            gp = gp.withNameToConfiguration(newNameToConfiguration);
        } catch (MavenDownloadingException | MavenDownloadingExceptions | IllegalArgumentException e) {
            return Markup.warn(buildScript, e);
        }
        return buildScript.withMarkers(buildScript.getMarkers().setByType(gp));
    }

    @RequiredArgsConstructor
    private class InsertDependencyInOrder extends JavaIsoVisitor<ExecutionContext> {
        private final String configuration;

        private final GradleProject gp;

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
            boolean isKotlinDsl = getCursor().firstEnclosing(JavaSourceFile.class) instanceof K.CompilationUnit;
            if (!DEPENDENCIES_DSL_MATCHER.matches(m) && !(isKotlinDsl && m.getSimpleName().equals("dependencies"))) {
                return m;
            }

            if (insertPredicate != null && !insertPredicate.test(getCursor())) {
                return m;
            }

            J.Lambda dependenciesBlock = (J.Lambda) m.getArguments().get(0);
            if (!(dependenciesBlock.getBody() instanceof J.Block)) {
                return m;
            }

            if (version != null) {
                if (version.startsWith("$")) {
                    resolvedVersion = version;
                } else {
                    try {
                        resolvedVersion = new DependencyVersionSelector(metadataFailures, gp, null)
                                .select(new GroupArtifact(groupId, artifactId), configuration, version, versionPattern, ctx);
                    } catch (MavenDownloadingException e) {
                        return e.warn(m);
                    }
                }
            }

            J.Block body = (J.Block) dependenciesBlock.getBody();

            String codeTemplate;
            DependencyStyle style = autodetectDependencyStyle(body.getStatements());
            if (style == DependencyStyle.String) {
                if (!isKotlinDsl) {
                    codeTemplate = "dependencies {\n" +
                                   escapeIfNecessary(configuration) + " \"" + groupId + ":" + artifactId + (resolvedVersion == null ? "" : ":" + resolvedVersion) + (resolvedVersion == null || classifier == null ? "" : ":" + classifier) + (extension == null ? "" : "@" + extension) + "\"" +
                                   "\n}";
                } else {
                    codeTemplate = "dependencies {\n" +
                                   configuration + "(\"" + groupId + ":" + artifactId + (resolvedVersion == null ? "" : ":" + resolvedVersion) + (resolvedVersion == null || classifier == null ? "" : ":" + classifier) + (extension == null ? "" : "@" + extension) + "\")" +
                                   "\n}";
                }
            } else {
                if (!isKotlinDsl) {
                    codeTemplate = "dependencies {\n" +
                                   escapeIfNecessary(configuration) + " group: \"" + groupId + "\", name: \"" + artifactId + "\"" + (resolvedVersion == null ? "" : ", version: \"" + resolvedVersion + "\"") + (classifier == null ? "" : ", classifier: \"" + classifier + "\"") + (extension == null ? "" : ", ext: \"" + extension + "\"") +
                                   "\n}";
                } else {
                    codeTemplate = "dependencies {\n" +
                                   configuration + "(group = \"" + groupId + "\", name = \"" + artifactId + "\"" + (resolvedVersion == null ? "" : ", version = \"" + resolvedVersion + "\"") + (classifier == null ? "" : ", classifier = \"" + classifier + "\"") + (extension == null ? "" : ", ext = \"" + extension + "\"") + ")" +
                                   "\n}";
                }
            }

            Boolean requirePrintEqualsInput = ctx.getMessage(ExecutionContext.REQUIRE_PRINT_EQUALS_INPUT);
            ctx.putMessage(ExecutionContext.REQUIRE_PRINT_EQUALS_INPUT, false);
            SourceFile parsed;
            if (!isKotlinDsl) {
                parsed = GRADLE_PARSER.parse(ctx, codeTemplate)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Could not parse as Gradle"));
            } else {
                parsed = GRADLE_PARSER.parseInputs(Collections.singletonList(new GradleParser.Input(Paths.get("build.gradle.kts"), () -> new ByteArrayInputStream(codeTemplate.getBytes(StandardCharsets.UTF_8)))), null, ctx)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Could not parse as Gradle"));
            }
            ctx.putMessage(ExecutionContext.REQUIRE_PRINT_EQUALS_INPUT, requirePrintEqualsInput);

            if (parsed instanceof ParseError) {
                throw ((ParseError) parsed).toException();
            }

            J.MethodInvocation addDependencyInvocation;
            if (!isKotlinDsl) {
                addDependencyInvocation = requireNonNull((J.MethodInvocation) ((J.Return) (((J.Block) ((J.Lambda) ((J.MethodInvocation)
                        ((G.CompilationUnit) parsed).getStatements().get(0)).getArguments().get(0)).getBody()).getStatements().get(0))).getExpression());
            } else {
                addDependencyInvocation = requireNonNull((J.MethodInvocation) (((J.Block) ((J.Lambda) ((J.MethodInvocation)
                        ((J.Block) ((K.CompilationUnit) parsed).getStatements().get(0)).getStatements().get(0)).getArguments().get(0)).getBody()).getStatements().get(0)));
            }
            addDependencyInvocation = autoFormat(addDependencyInvocation, ctx, new Cursor(getCursor(), body));
            InsertDependencyComparator dependencyComparator = new InsertDependencyComparator(body.getStatements(), addDependencyInvocation);

            List<Statement> statements = new ArrayList<>(body.getStatements());
            int i = 0;
            for (; i < body.getStatements().size(); i++) {
                Statement currentStatement = body.getStatements().get(i);
                if (dependencyComparator.compare(currentStatement, addDependencyInvocation) > 0) {
                    if (dependencyComparator.getBeforeDependency() != null) {
                        J.MethodInvocation beforeDependency = (J.MethodInvocation) (dependencyComparator.getBeforeDependency() instanceof J.Return ?
                                requireNonNull(((J.Return) dependencyComparator.getBeforeDependency()).getExpression()) :
                                dependencyComparator.getBeforeDependency());
                        if (i == 0) {
                            if (!addDependencyInvocation.getSimpleName().equals(beforeDependency.getSimpleName())) {
                                statements.set(i, currentStatement.withPrefix(Space.format("\n\n" + currentStatement.getPrefix().getIndent())));
                            }
                        } else {
                            Space originalPrefix = addDependencyInvocation.getPrefix();
                            addDependencyInvocation = addDependencyInvocation.withPrefix(currentStatement.getPrefix());

                            if (addDependencyInvocation.getSimpleName().equals(beforeDependency.getSimpleName())) {
                                if (!currentStatement.getPrefix().equals(originalPrefix)) {
                                    statements.set(i, currentStatement.withPrefix(originalPrefix));
                                }
                            }
                        }
                    }

                    statements.add(i, addDependencyInvocation);
                    break;
                }
            }
            if (body.getStatements().size() == i) {
                if (!body.getStatements().isEmpty()) {
                    Statement lastStatement;
                    if (statements.get(i - 1) instanceof J.Return) {
                        J.Return r = (J.Return) statements.remove(i - 1);
                        lastStatement = requireNonNull(r.getExpression()).withPrefix(r.getPrefix());
                        statements.add(lastStatement);
                    } else {
                        lastStatement = statements.get(i - 1);
                    }
                    if (lastStatement instanceof J.MethodInvocation && !((J.MethodInvocation) lastStatement).getSimpleName().equals(addDependencyInvocation.getSimpleName())) {
                        addDependencyInvocation = addDependencyInvocation.withPrefix(Space.format("\n\n" + addDependencyInvocation.getPrefix().getIndent()));
                    }
                }
                statements.add(addDependencyInvocation);
            }
            body = body.withStatements(statements);
            m = m.withArguments(singletonList(dependenciesBlock.withBody(body)));

            return m;
        }
    }

    private String escapeIfNecessary(String configurationName) {
        // default is a gradle configuration created by the base plugin and a groovy keyword if
        // it is used it needs to be escaped
        return configurationName.equals("default") ? "'" + configurationName + "'" : configurationName;
    }

    enum DependencyStyle {
        Map, String
    }

    private DependencyStyle autodetectDependencyStyle(List<Statement> statements) {
        int string = 0;
        int map = 0;
        for (Statement statement : statements) {
            if (statement instanceof J.Return && ((J.Return) statement).getExpression() instanceof J.MethodInvocation) {
                J.MethodInvocation invocation = (J.MethodInvocation) ((J.Return) statement).getExpression();
                if (invocation.getArguments().get(0) instanceof J.Literal || invocation.getArguments().get(0) instanceof G.GString) {
                    string++;
                } else if (invocation.getArguments().get(0) instanceof G.MapEntry) {
                    map++;
                } else if (invocation.getArguments().get(0) instanceof J.Assignment) {
                    map++;
                }
            } else if (statement instanceof J.MethodInvocation) {
                J.MethodInvocation invocation = (J.MethodInvocation) statement;
                if (invocation.getArguments().get(0) instanceof J.Literal || invocation.getArguments().get(0) instanceof G.GString) {
                    string++;
                } else if (invocation.getArguments().get(0) instanceof G.MapEntry) {
                    map++;
                } else if (invocation.getArguments().get(0) instanceof J.Assignment) {
                    map++;
                }
            }
        }

        return string >= map ? DependencyStyle.String : DependencyStyle.Map;
    }
}
