/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.workers.internal

import spock.lang.Timeout

@Timeout(60)
class WorkerDaemonLifecycleTest extends AbstractDaemonWorkerExecutorIntegrationSpec {
    String logSnapshot = ""

    def "worker daemons are reused across builds"() {
        withRunnableClassInBuildScript()
        buildFile << """
            task runInWorker1(type: WorkerTask) {
                isolationMode = IsolationMode.PROCESS
            }
            
            task runInWorker2(type: WorkerTask) {
                isolationMode = IsolationMode.PROCESS
            }
        """

        when:
        succeeds "runInWorker1"

        and:
        succeeds "runInWorker2"

        then:
        assertSameDaemonWasUsed("runInWorker1", "runInWorker2")
    }

    def "worker daemons can be restarted when daemon is stopped"() {
        withRunnableClassInBuildScript()
        buildFile << """
            task runInWorker1(type: WorkerTask) {
                isolationMode = IsolationMode.PROCESS
            }
            
            task runInWorker2(type: WorkerTask) {
                isolationMode = IsolationMode.PROCESS
            }
        """

        when:
        succeeds "runInWorker1"

        then:
        stopDaemonsNow()

        when:
        succeeds "runInWorker2"

        then:
        assertDifferentDaemonsWereUsed("runInWorker1", "runInWorker2")
    }

    def "worker daemons are stopped when daemon is stopped"() {
        withRunnableClassInBuildScript()
        buildFile << """
            task runInWorker(type: WorkerTask) {
                isolationMode = IsolationMode.PROCESS
            }
        """

        when:
        args("--info")
        succeeds "runInWorker"

        then:
        newSnapshot()

        when:
        stopDaemonsNow()

        then:
        daemons.daemon.stops()
        sinceSnapshot().contains("Stopped 1 worker daemon(s).")
    }

    def "worker daemons are stopped and not reused when log level is changed"() {
        withRunnableClassInBuildScript()
        buildFile << """
            task runInWorker1(type: WorkerTask) {
                isolationMode = IsolationMode.PROCESS
            }
            
            task runInWorker2(type: WorkerTask) {
                isolationMode = IsolationMode.PROCESS
            }
        """

        when:
        args("--warn")
        succeeds "runInWorker1"

        then:
        newSnapshot()

        when:
        args("--info")
        succeeds "runInWorker2"

        then:
        sinceSnapshot().contains("Log level has changed, stopping idle worker daemon with out-of-date log level.")

        and:
        assertDifferentDaemonsWereUsed("runInWorker1", "runInWorker2")
    }

    def "worker daemons are not reused when classpath changes"() {
        withRunnableClassInBuildScript()
        buildFile << """
            task runInWorker1(type: WorkerTask) {
                isolationMode = IsolationMode.PROCESS
            }
            
            task runInWorker2(type: WorkerTask) {
                isolationMode = IsolationMode.PROCESS
            }
        """

        when:
        succeeds "runInWorker1"

        then:
        buildFile << """
            task someNewTask
        """

        when:
        succeeds "runInWorker2"

        then:
        assertDifferentDaemonsWereUsed("runInWorker1", "runInWorker2")

        when:
        file("buildSrc/src/main/java/NewClass.java") << "public class NewClass { }"

        then:
        succeeds "runInWorker1"

        and:
        assertDifferentDaemonsWereUsed("runInWorker1", "runInWorker2")
    }

    def "only compiler daemons are stopped with the build session"() {
        withRunnableClassInBuildScript()
        file('src/main/java').createDir()
        file('src/main/java/Test.java') << "public class Test {}"
        buildFile << """
            apply plugin: "java"
            
            task runInWorker(type: WorkerTask) {
                isolationMode = IsolationMode.PROCESS
            }
            
            tasks.withType(JavaCompile) {
                options.fork = true
            }
        """

        when:
        args("--info")
        succeeds "compileJava", "runInWorker"

        then:
        sinceSnapshot().count("Started Gradle worker daemon") == 2
        sinceSnapshot().contains("Stopped 1 worker daemon(s).")
        newSnapshot()

        when:
        stopDaemonsNow()

        then:
        daemons.daemon.stops()
        sinceSnapshot().contains("Stopped 1 worker daemon(s).")
    }

    void newSnapshot() {
        logSnapshot = daemons.daemon.log
    }

    String sinceSnapshot() {
        return daemons.daemon.log - logSnapshot
    }
}
