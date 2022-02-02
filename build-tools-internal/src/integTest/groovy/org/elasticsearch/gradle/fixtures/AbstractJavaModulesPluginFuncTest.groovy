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
    ${requires.collect {"requires ${it};"}.join('\n')}
    exports org.acme.${name}.api;
    uses org.acme.${name}.api.${componentName(name)};}
""")
    }

    void module(File root = new File(testProjectDir.root, 'providing'), String moduleName = root.name) {
        String component = componentName(moduleName);
        moduleInfo(root)
        file(root, "src/main/java/org/acme/${moduleName}/impl/SomethingInternal.java") << """package org.acme.${moduleName}.impl;

public class SomethingInternal {

    public void doSomething() {
        System.out.println("Something internal");
    }
}
"""

        file(root, "src/main/java/org/acme/${moduleName}/api/${component}.java") << """package org.acme.${moduleName}.api;
import org.acme.${moduleName}.impl.SomethingInternal;
public class ${component} {

    public ${component}() {
    }
    
    public void doSomething() {
         new SomethingInternal().doSomething();
    }

}
"""
    }


    void consumingModule(File root = new File(testProjectDir.root, 'consuming'), String consumingModule = root.name, String providingModule = 'providing') {
        String component = componentName(consumingModule);
        String providingComponent = componentName(providingModule)
        moduleInfo(root, consumingModule, ["org.acme.$providingModule"])
        file(root, "src/main/java/org/acme/${consumingModule}/api/${component}.java") << """package org.acme.${consumingModule}.api;

import org.acme.${providingModule}.api.${providingComponent};

public class ${component} {

    public ${component}() {
    }
    
    public void doSomething() {
        new ${providingComponent}().doSomething();
    }

}
"""
    }

    void consumingInternalsModule(File root = new File(testProjectDir.root, 'consuming'), String providingModule = 'providing') {
        def moduleName = root.name
        def componentName = this.componentName(moduleName)
        moduleInfo(root, moduleName, ["org.acme.$providingModule"])
        file(root, "src/main/java/org/acme/${moduleName}/api/${componentName}.java") << """package org.acme.${moduleName}.api;

import org.acme.${providingModule}.impl.SomethingInternal;

public class $componentName {
    public void run() {
       SomethingInternal i = new SomethingInternal();
       i.doSomething();
    }

}
"""
    }

    void writeConsumingJavaSource(File root = testProjectDir.root, String providingModuleName = 'providing') {
        String name = root.name;
        def componentName = "Consuming${providingModuleName.capitalize()}"
        def providingComponentName = componentName(providingModuleName)
        file(root, "src/main/java/org/${name}/" + componentName + ".java") << """package org.${name};

import org.acme.${providingModuleName}.api.${providingComponentName};

public class $componentName {
    $providingComponentName c = new ${providingComponentName}();
    
    public void run() {
       c.doSomething();
    }

}
"""
    }

    String componentName(String moduleName) {
        moduleName.capitalize() + "Component"
    }
}
