/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.gradle.internal;

import org.elasticsearch.gradle.OS;
import org.elasticsearch.gradle.VersionProperties;
import org.elasticsearch.gradle.internal.info.BuildParameterExtension;
import org.elasticsearch.gradle.internal.test.TestUtil;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.ApplicationPlugin;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;

import java.util.List;
import java.util.Map;

import static org.elasticsearch.gradle.internal.util.ParamsUtils.loadBuildParams;

/**
 * Convention plugin for JMH benchmark submodules (e.g. {@code :libs:simdvec:jmh}).
 *
 * <p> Applies the standard Elasticsearch Java base plugins, configures JMH dependencies,
 * annotation processing, and runtime settings. Each benchmark {@code build.gradle} only
 * needs to declare its project-specific dependencies.
 *
 * <p> Apply in a benchmark submodule's {@code build.gradle}:
 * <pre>{@code
 *   apply plugin: 'elasticsearch.jmh'
 * }</pre>
 */
public class JmhPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(ElasticsearchJavaBasePlugin.class);
        project.getPluginManager().apply(JavaLibraryPlugin.class);
        project.getPluginManager().apply(ApplicationPlugin.class);

        var buildParams = loadBuildParams(project).get();

        configureApplication(project);
        configureDependencies(project);
        configureCompileJava(project);
        configureRunTask(project, buildParams);
        configureTestTask(project, buildParams);
        wireParentCheck(project);
    }

    /**
     * Sets {@code org.openjdk.jmh.Main} as the application entry point and disables
     * the {@code assemble} and {@code javadoc} tasks, which are not useful for benchmarks.
     */
    private static void configureApplication(Project project) {
        project.getExtensions().configure(JavaApplication.class, app -> app.getMainClass().set("org.openjdk.jmh.Main"));
        project.getTasks().named("assemble").configure(t -> t.setEnabled(false));
        project.getTasks().named("javadoc").configure(t -> t.setEnabled(false));
    }

    /**
     * Adds the common JMH dependencies shared by all benchmark submodules: {@code jmh-core},
     * the JMH annotation processor, the Elasticsearch {@code ExtraParamProcessor}, the shared
     * {@code :benchmarks:common} utilities (Utils, ExtraParam), JMH's runtime dependencies
     * (jopt-simple, commons-math3), and the test framework.
     */
    private static void configureDependencies(Project project) {
        var deps = project.getDependencies();
        String jmhVersion = VersionProperties.getVersions().get("jmh");
        deps.add("api", "org.openjdk.jmh:jmh-core:" + jmhVersion);
        deps.add("annotationProcessor", "org.openjdk.jmh:jmh-generator-annprocess:" + jmhVersion);
        deps.add("annotationProcessor", deps.project(Map.of("path", ":benchmarks:processor")));
        deps.add("runtimeOnly", "net.sf.jopt-simple:jopt-simple:5.0.2");
        deps.add("runtimeOnly", "org.apache.commons:commons-math3:3.6.1");
        deps.add("implementation", deps.project(Map.of("path", ":benchmarks:common")));
        deps.add("testImplementation", deps.project(Map.of("path", ":test:framework")));
    }

    /**
     * Wires the JMH {@code BenchmarkProcessor} and Elasticsearch {@code ExtraParamProcessor}
     * into {@code compileJava}. The {@code -processor} flag must be passed explicitly because
     * Gradle would otherwise quote the comma-separated list, which javac rejects.
     */
    private static void configureCompileJava(Project project) {
        project.getTasks().named("compileJava", JavaCompile.class).configure(task -> {
            task.getOptions()
                .getCompilerArgs()
                .addAll(
                    List.of(
                        "-processor",
                        "org.openjdk.jmh.generators.BenchmarkProcessor,org.elasticsearch.benchmark.ExtraParamProcessor"
                    )
                );
        });
    }

    /**
     * Configures the {@code run} task to execute benchmarks with the runtime JDK, native
     * library path, and Lucene vectorization feature-version cap. The native libs path is
     * also forwarded to JMH-forked VMs via {@code -jvmArgsAppend}.
     */
    private static void configureRunTask(Project project, BuildParameterExtension buildParams) {
        FileCollection nativeConfigFiles = project.getConfigurations().getByName("resolvedNativeLibs");
        project.getTasks().named("run", JavaExec.class).configure(task -> {
            String javaBin = buildParams.getRuntimeJavaHome().get().getAbsolutePath() + "/bin/java";
            if (OS.current() == OS.WINDOWS) {
                javaBin += ".exe";
            }
            task.setExecutable(javaBin);
            task.dependsOn(nativeConfigFiles);
            String nativeLibsPath = TestUtil.getTestLibraryPath(nativeConfigFiles.getAsPath());
            task.systemProperty("es.nativelibs.path", nativeLibsPath);
            String runtimeMajor = buildParams.getRuntimeJavaVersion().map(v -> v.getMajorVersion()).get();
            task.jvmArgs("-Dorg.apache.lucene.vectorization.upperJavaFeatureVersion=" + runtimeMajor);
            task.args("-jvmArgsAppend", "-Des.nativelibs.path=" + nativeLibsPath);
        });
    }

    /**
     * Makes the parent project's {@code check} task depend on this project's {@code check},
     * so running e.g. {@code :libs:simdvec:check} also runs {@code :libs:simdvec:jmh:check}.
     */
    private static void wireParentCheck(Project project) {
        Project parent = project.getParent();
        if (parent != null) {
            parent.getTasks().named("check").configure(t -> t.dependsOn(project.getTasks().named("check")));
        }
    }

    /**
     * Adds {@code --add-modules=jdk.incubator.vector} and
     * {@code --add-opens=java.base/java.nio=ALL-UNNAMED} to test JVMs on JDK 21+,
     * enabling the Vector API and direct NIO buffer access used by benchmark tests.
     */
    private static void configureTestTask(Project project, BuildParameterExtension buildParams) {
        project.getTasks().withType(Test.class).configureEach(test -> {
            int runtimeMajor = buildParams.getRuntimeJavaVersion().map(v -> Integer.parseInt(v.getMajorVersion())).get();
            if (runtimeMajor >= 21) {
                test.jvmArgs("--add-modules=jdk.incubator.vector", "--add-opens=java.base/java.nio=ALL-UNNAMED");
            }
        });
    }
}
