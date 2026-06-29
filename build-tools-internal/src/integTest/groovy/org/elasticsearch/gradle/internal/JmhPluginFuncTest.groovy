/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.gradle.internal

import org.elasticsearch.gradle.fixtures.AbstractGradleInternalPluginFuncTest
import org.gradle.api.Plugin
import org.gradle.testkit.runner.TaskOutcome

class JmhPluginFuncTest extends AbstractGradleInternalPluginFuncTest {

    Class<? extends Plugin> pluginClassUnderTest = JmhPlugin

    def setup() {
        configurationCacheCompatible = false

        propertiesFile << "org.gradle.java.installations.fromEnv=" +
            "JAVA_HOME,RUNTIME_JAVA_HOME,JAVA21_HOME\n"

        subProject(":benchmarks:common") << """
            plugins { id 'java' }
        """
        subProject(":benchmarks:processor") << """
            plugins { id 'java' }
        """
        subProject(":test:framework") << """
            plugins { id 'java' }
        """
    }

    def "plugin applies java-library and application plugins"() {
        given:
        buildFile << """
            assert project.plugins.hasPlugin('java-library')
            assert project.plugins.hasPlugin('application')
            assert project.application.mainClass.get() == 'org.openjdk.jmh.Main'
        """

        when:
        def result = gradleRunner('help').build()

        then:
        result.task(":help").outcome == TaskOutcome.SUCCESS
    }

    def "plugin disables assemble and javadoc tasks"() {
        given:
        buildFile << """
            assert tasks.named('assemble').get().enabled == false
            assert tasks.named('javadoc').get().enabled == false
        """

        when:
        def result = gradleRunner('help').build()

        then:
        result.task(":help").outcome == TaskOutcome.SUCCESS
    }

    def "plugin adds all common dependencies"() {
        given:
        buildFile << """
            // api: jmh-core
            def apiDeps = configurations.api.dependencies
            def jmhCore = apiDeps.find { it.group == 'org.openjdk.jmh' && it.name == 'jmh-core' }
            assert jmhCore != null : "jmh-core not found in api dependencies"
            assert jmhCore.version == '1.37' : "expected jmh 1.37, got \${jmhCore.version}"

            // annotationProcessor: jmh-generator-annprocess + :benchmarks:processor
            def apDeps = configurations.annotationProcessor.dependencies
            def jmhAp = apDeps.find { it.group == 'org.openjdk.jmh' && it.name == 'jmh-generator-annprocess' }
            assert jmhAp != null : "jmh-generator-annprocess not found in annotationProcessor deps"
            assert jmhAp.version == '1.37' : "expected jmh 1.37, got \${jmhAp.version}"
            def processor = apDeps.find { it instanceof org.gradle.api.artifacts.ProjectDependency &&
                it.path == ':benchmarks:processor' }
            assert processor != null : ":benchmarks:processor not found in annotationProcessor deps"

            // implementation: :benchmarks:common
            def implDeps = configurations.implementation.dependencies
            def common = implDeps.find { it instanceof org.gradle.api.artifacts.ProjectDependency &&
                it.path == ':benchmarks:common' }
            assert common != null : ":benchmarks:common not found in implementation deps"

            // runtimeOnly: jopt-simple + commons-math3
            def rtDeps = configurations.runtimeOnly.dependencies
            assert rtDeps.find { it.name == 'jopt-simple' } != null : "jopt-simple not found in runtimeOnly"
            assert rtDeps.find { it.name == 'commons-math3' } != null : "commons-math3 not found in runtimeOnly"

            // testImplementation: :test:framework
            def testDeps = configurations.testImplementation.dependencies
            def tf = testDeps.find { it instanceof org.gradle.api.artifacts.ProjectDependency &&
                it.path == ':test:framework' }
            assert tf != null : ":test:framework not found in testImplementation deps"
        """

        when:
        def result = gradleRunner('help').build()

        then:
        result.task(":help").outcome == TaskOutcome.SUCCESS
    }

    def "plugin configures compileJava with BenchmarkProcessor and ExtraParamProcessor"() {
        given:
        buildFile << """
            def args = tasks.named('compileJava').get().options.compilerArgs
            def idx = args.indexOf('-processor')
            assert idx >= 0 : "expected -processor in compiler args, got: \${args}"
            def processorValue = args[idx + 1]
            assert processorValue.contains('org.openjdk.jmh.generators.BenchmarkProcessor') :
                "expected BenchmarkProcessor, got: \${processorValue}"
            assert processorValue.contains('org.elasticsearch.benchmark.ExtraParamProcessor') :
                "expected ExtraParamProcessor, got: \${processorValue}"
        """

        when:
        def result = gradleRunner('help').build()

        then:
        result.task(":help").outcome == TaskOutcome.SUCCESS
    }

    def "plugin configures run task with JMH runtime settings"() {
        given:
        buildFile << """
            def runTask = tasks.named('run', JavaExec).get()

            assert runTask.mainClass.get() == 'org.openjdk.jmh.Main'

            assert runTask.systemProperties.containsKey('es.nativelibs.path') :
                "expected es.nativelibs.path system property"

            def jvmArgs = runTask.jvmArgs
            def cap = jvmArgs.find { it.startsWith('-Dorg.apache.lucene.vectorization.upperJavaFeatureVersion=') }
            assert cap != null : "expected upperJavaFeatureVersion jvmArg, got: \${jvmArgs}"

            def runArgs = runTask.args
            def idx = runArgs.indexOf('-jvmArgsAppend')
            assert idx >= 0 : "expected -jvmArgsAppend in args, got: \${runArgs}"
            assert runArgs[idx + 1].contains('es.nativelibs.path') :
                "expected es.nativelibs.path in -jvmArgsAppend value"
        """

        when:
        def result = gradleRunner('help').build()

        then:
        result.task(":help").outcome == TaskOutcome.SUCCESS
    }

    def "plugin configures test task with vector module and NIO access on JDK 21+"() {
        given:
        buildFile << """
            def testTask = tasks.named('test', Test).get()
            def jvmArgs = testTask.jvmArgs
            assert jvmArgs.contains('--add-modules=jdk.incubator.vector') :
                "expected --add-modules=jdk.incubator.vector, got: \${jvmArgs}"
            assert jvmArgs.contains('--add-opens=java.base/java.nio=ALL-UNNAMED') :
                "expected --add-opens for java.nio, got: \${jvmArgs}"
        """

        when:
        def result = gradleRunner('help').build()

        then:
        result.task(":help").outcome == TaskOutcome.SUCCESS
    }
}
