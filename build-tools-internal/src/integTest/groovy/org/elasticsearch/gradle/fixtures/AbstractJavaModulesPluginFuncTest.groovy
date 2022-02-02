/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.gradle.fixtures

abstract class AbstractJavaModulesPluginFuncTest extends AbstractGradleFuncTest {

    File moduleInfo(File root = testProjectDir.root, String name = root.name, List<String> requires = []) {
        file(root, 'src/main/java/module-info.java', """
module org.acme.${name} {
    ${requires.collect { "requires ${it};" }.join('\n')}
    exports org.acme.${name}.api;
    uses org.acme.${name}.api.${componentName(name)};}
""")
    }

    void module(File root = new File(testProjectDir.root, 'providing'), String moduleName = root.name) {
        moduleInfo(root)
        internalSource(root, moduleName)
        componentSource(root, moduleName)
    }

    void consumingModule(File root = new File(testProjectDir.root, 'consuming'), String module = root.name, String providingModule = 'providing') {
        moduleInfo(root, module, ["org.acme.$providingModule"])
        componentSource(root, module, "org.acme.${providingModule}.api.${componentName(providingModule)}")
    }

    void consumingInternalsModule(File root = new File(testProjectDir.root, 'consuming'), String module = root.name, String providingModule = 'providing') {
        moduleInfo(root, module, ["org.acme.$providingModule"])
        componentSource(root, module, "org.acme.${providingModule}.impl.SomethingInternal")
    }

    void writeConsumingJavaSource(File root = testProjectDir.root, String providingModuleName = 'providing') {
        String name = root.name;
        def componentName = "Consuming${providingModuleName.capitalize()}"
        def providingComponentName = componentName(providingModuleName)
        file(root, "src/main/java/org/${name}/" + componentName + ".java") << """package org.${name};
import org.acme.${providingModuleName}.api.${providingComponentName};

public class $componentName {
    
    public void run() {
       $providingComponentName c = new ${providingComponentName}();
       c.doSomething();
    }

}
"""
    }

    File componentSource(File root, String module, String usedClazz = "org.acme.${module}.impl.SomethingInternal") {
        String component = componentName(module)
        String simpleUsedClazz = usedClazz.substring(usedClazz.lastIndexOf('.') + 1)
        file(root, "src/main/java/org/acme/${module}/api/${component}.java", """package org.acme.${module}.api;
import $usedClazz;
public class ${component} {

    public ${component}() {
    }
    
    public void doSomething() {
         $simpleUsedClazz s = new ${simpleUsedClazz}();
         s.doSomething();
    }

}
""")
    }

    File internalSource(File root, String moduleName) {
        file(root, "src/main/java/org/acme/${moduleName}/impl/SomethingInternal.java") << """package org.acme.${moduleName}.impl;

public class SomethingInternal {

    public void doSomething() {
        System.out.println("Something internal");
    }
}
"""
    }

    String componentName(String moduleName) {
        moduleName.capitalize() + "Component"
    }
}
