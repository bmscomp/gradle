/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.java.compile.incremental

import groovy.transform.NotYetImplemented
import org.gradle.integtests.fixtures.CompiledLanguage
import org.gradle.util.ToBeImplemented
import spock.lang.Issue
abstract class BaseJavaSourceIncrementalCompilationIntegrationTest extends AbstractSourceIncrementalCompilationIntegrationTest {
    CompiledLanguage language = CompiledLanguage.JAVA

    void recompiledWithFailure(String expectedFailure, String... recompiledClasses) {
        fails language.compileTaskName
        failure.assertHasErrorOutput(expectedFailure)
    }

    def "change to #retention retention annotation class recompiles #desc"() {
        def annotationClass = file("src/main/${language.name}/SomeAnnotation.${language.name}") << """
            import java.lang.annotation.*;

            @Retention(RetentionPolicy.$retention)
            public @interface SomeAnnotation {}
        """
        source "@SomeAnnotation class A {}", "class B {}"
        outputs.snapshot { run language.compileTaskName }

        when:
        annotationClass.text += "/* change */"
        run language.compileTaskName

        then:
        outputs.recompiledClasses(expected as String[])

        where:
        desc              | retention | expected
        'all'             | 'SOURCE'  | ['A', 'B', 'SomeAnnotation']
        'annotated types' | 'CLASS'   | ['SomeAnnotation', 'A']
        'annotated types' | 'RUNTIME' | ['SomeAnnotation', 'A']
    }

    def "deletes headers when source file is deleted"() {
        given:
        def sourceFile = file("src/main/java/my/org/Foo.java")
        sourceFile.text = """
            package my.org;

            public class Foo {
                public native void foo();

                public static class Inner {
                    public native void anotherNative();
                }
            }
        """
        def generatedHeaderFile = file("build/generated/sources/headers/java/main/my_org_Foo.h")
        def generatedInnerClassHeaderFile = file("build/generated/sources/headers/java/main/my_org_Foo_Inner.h")

        source("""class Bar {
            public native void bar();
        }""")

        succeeds language.compileTaskName
        generatedHeaderFile.assertExists()
        generatedInnerClassHeaderFile.assertExists()

        when:
        sourceFile.delete()
        succeeds language.compileTaskName

        then:
        generatedHeaderFile.assertDoesNotExist()
        generatedInnerClassHeaderFile.assertDoesNotExist()
        file("build/generated/sources/headers/java/main/Bar.h").assertExists()
    }

    def "changed class with used non-private constant incurs full rebuild"() {
        source "class A { int foo() { return 1; } }", "class B { final static int x = 1;}"
        outputs.snapshot { run language.compileTaskName }

        when:
        source "class B { /* change */ }"
        run language.compileTaskName

        then:
        outputs.recompiledClasses 'B', 'A'
    }

    @NotYetImplemented
    //  Can re-enable with compiler plugins. See gradle/gradle#1474
    def "changing an unused non-private constant incurs partial rebuild"() {
        source "class A { int foo() { return 2; } }", "class B { final static int x = 1;}"
        outputs.snapshot { run language.compileTaskName }

        when:
        source "class B { /* change */ }"
        run language.compileTaskName

        then:
        outputs.recompiledClasses 'B'
    }

    def "reports source type that does not support detection of source root"() {
        given:
        buildFile << "${language.compileTaskName}.source([file('extra'), file('other'), file('text-file.txt')])"

        source("class A extends B {}")
        file("extra/B.${language.name}") << "class B {}"
        file("extra/C.${language.name}") << "class C {}"
        def textFile = file('text-file.txt')
        textFile.text = "text file as root"

        outputs.snapshot { run language.compileTaskName }

        when:
        file("extra/B.${language.name}").text = "class B { String change; }"
        executer.withArgument "--info"
        run language.compileTaskName

        then:
        outputs.recompiledClasses("A", "B", "C")
        output.contains("Cannot infer source root(s) for source `file '${textFile.absolutePath}'`. Supported types are `File` (directories only), `DirectoryTree` and `SourceDirectorySet`.")
        output.contains("Full recompilation is required because the source roots could not be inferred.")
    }

    def "does not recompile when a resource changes"() {
        given:
        buildFile << """
            ${language.compileTaskName}.source 'src/main/resources'
        """
        source("class A {}")
        source("class B {}")
        def resource = file("src/main/resources/foo.txt")
        resource.text = 'foo'

        outputs.snapshot { succeeds language.compileTaskName }

        when:
        resource.text = 'bar'

        then:
        succeeds language.compileTaskName
        outputs.noneRecompiled()
    }

    @ToBeImplemented
    @Issue("https://github.com/gradle/gradle/issues/8590")
    def "adding a class with higher resolution priority should trigger recompilation"() {
        file("src/main/java/foo/A.java") << """
package foo;
import bar.*;
public class A {
  Other getOther() { return null; }
}
"""
        file("src/main/java/bar/Other.java") << """
package bar;
public class Other {}
"""
        outputs.snapshot { run language.compileTaskName }

        when:
        file("src/main/java/foo/Other.java") << """
package foo;
public class Other {}
        """

        then:
        succeeds language.compileTaskName
        outputs.recompiledFqn("foo.Other") // should be foo.A and foo.Other
    }

}
