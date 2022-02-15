/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.gradle.internal


import org.elasticsearch.gradle.fixtures.AbstractJavaModulesPluginFuncTest
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.IgnoreRest
import spock.util.environment.RestoreSystemProperties

@RestoreSystemProperties
class ElasticsearchJavaModulePluginFuncTest extends AbstractJavaModulesPluginFuncTest {

    def setup() {
        settingsFile << "include 'providing'\n"
        settingsFile << "include 'consuming'\n"
        settingsFile << """
                // TODO: make those tests work in idea for now. requires general idea fix
                System.clearProperty("idea.active")
        """
    }

    def "can not compile against module project internals"() {
        given:
        module()
        consumingInternalsModule()

        when:
        file('providing/build.gradle') << """
            plugins {
             id 'elasticsearch.java'
            }
        """

        file('consuming/build.gradle') << """
            plugins {
             id 'elasticsearch.java'
            }
            
            dependencies {
                moduleImplementation project(':providing')
            }
        """

        then:
        def result = gradleRunner("compileJava").buildAndFail()
        result.task(":consuming:compileJava").outcome == TaskOutcome.FAILED
        result.getOutput().contains("ConsumingComponent.java:2: error: package org.acme.providing.impl is not visible")
//        result.getOutput().contains("package org.acme.providing.impl is declared in module org.acme.providing, which does not export it")
    }

    def "can compile module against module project"() {
        given:
        module()
        consumingModule()

        when:
        file('providing/build.gradle') << """
            plugins {
             id 'elasticsearch.java'
            }
        """

        file('consuming/build.gradle') << """
            plugins {
             id 'elasticsearch.java'
            }
            
            dependencies {
                moduleImplementation project(':providing')
            }
        """

        then:
        def result = gradleRunner("compileJava").build()
        result.task(":consuming:compileJava").outcome == TaskOutcome.SUCCESS
    }

    def "can compile module against project"() {
        given:
        componentSource(file("providing"))
        internalSource(file("providing"))
        consumingModule()

        when:
        file('providing/build.gradle') << """
            plugins {
             id 'elasticsearch.java'
            }
        """

        file('consuming/build.gradle') << """
            plugins {
             id 'elasticsearch.java'
            }
            
            dependencies {
                moduleImplementation project(':providing')
            }
            
            // disable fail on warning
            tasks.named('compileJava').configure { t ->
                t.options.compilerArgs = t.options.compilerArgs.findAll { it.equals('-Werror') == false }
            }
        """

        then:
        def result = gradleRunner("compileJava").build()
        result.task(":consuming:compileJava").outcome == TaskOutcome.SUCCESS
        result.output.contains("consuming/src/main/java/module-info.java:3: warning: requires directive for an automatic module")
    }

}
