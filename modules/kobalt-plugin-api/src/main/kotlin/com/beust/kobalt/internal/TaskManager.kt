package com.beust.kobalt.internal

import com.beust.kobalt.*
import com.beust.kobalt.api.*
import com.beust.kobalt.api.annotation.IncrementalTask
import com.beust.kobalt.api.annotation.Task
import com.beust.kobalt.misc.Strings
import com.beust.kobalt.misc.benchmarkMillis
import com.beust.kobalt.misc.kobaltError
import com.beust.kobalt.misc.log
import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.ListMultimap
import com.google.common.collect.Multimap
import com.google.common.collect.TreeMultimap
import java.lang.reflect.Method
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskManager @Inject constructor(val args: Args,
        val incrementalManagerFactory: IncrementalManager.IFactory) {
    private val dependsOn = TreeMultimap.create<String, String>()
    private val reverseDependsOn = TreeMultimap.create<String, String>()
    private val runBefore = TreeMultimap.create<String, String>()
    private val runAfter = TreeMultimap.create<String, String>()
    private val alwaysRunAfter = TreeMultimap.create<String, String>()

    /**
     * Dependency: task2 depends on task 1.
     */
    fun dependsOn(task1: String, task2: String) = dependsOn.put(task2, task1)

    /**
     * Dependency: task2 depends on task 1.
     */
    fun reverseDependsOn(task1: String, task2: String) = reverseDependsOn.put(task2, task1)

    /**
     * Ordering: task1 runs before task 2.
     */
    fun runBefore(task1: String, task2: String) = runBefore.put(task2, task1)

    /**
     * Ordering: task2 runs after task 1.
     */
    fun runAfter(task1: String, task2: String) = runAfter.put(task2, task1)

    /**
     * Wrapper task: task2 runs after task 1.
     */
    fun alwaysRunAfter(task1: String, task2: String) = alwaysRunAfter.put(task2, task1)

    data class TaskInfo(val id: String) {
        constructor(project: String, task: String) : this(project + ":" + task)

        val project: String?
            get() = if (id.contains(":")) id.split(":")[0] else null
        val taskName: String
            get() = if (id.contains(":")) id.split(":")[1] else id

        fun matches(projectName: String) = project == null || project == projectName
    }

    class RunTargetResult(val exitCode: Int, val messages: List<String>)

    /**
     * @return the list of tasks available for the given project.
     *
     * There can be multiple tasks by the same name (e.g. PackagingPlugin and AndroidPlugin both
     * define "install"), so return a multimap.
     */
    fun tasksByNames(project: Project): ListMultimap<String, ITask> {
        return ArrayListMultimap.create<String, ITask>().apply {
            annotationTasks.filter {
                it.project.name == project.name
            }.forEach {
                put(it.name, it)
            }
        }
    }

    fun runTargets(passedTaskNames: List<String>, projects: List<Project>): RunTargetResult {
        var result = 0
        val failedProjects = hashSetOf<String>()
        val messages = Collections.synchronizedList(arrayListOf<String>())
        val taskNames = calculateDependentTaskNames(passedTaskNames, projects)
        projects.forEach { project ->
            AsciiArt.logBox("Building ${project.name}")

            // Does the current project depend on any failed projects?
            val fp = project.projectExtra.dependsOn.filter {
                failedProjects.contains(it.name)
            }.map {
                it.name
            }

            if (fp.size > 0) {
                log(2, "Marking project ${project.name} as skipped")
                failedProjects.add(project.name)
                kobaltError("Not building project ${project.name} since it depends on failed "
                        + Strings.pluralize(fp.size, "project")
                        + " " + fp.joinToString(","))
            } else {
                // There can be multiple tasks by the same name (e.g. PackagingPlugin and AndroidPlugin both
                // define "install"), so use a multimap
                val tasksByNames = tasksByNames(project)

                log(3, "Tasks:")
                tasksByNames.keys().forEach {
                    log(3, "  $it: " + tasksByNames.get(it))
                }

                val graph = createGraph2(project.name, taskNames, tasksByNames,
                        dependsOn, reverseDependsOn, runBefore, runAfter, alwaysRunAfter,
                        { task: ITask -> task.name },
                        { task: ITask -> task.plugin.accept(project) })

                //
                // Now that we have a full graph, run it
                //
                log(2, "About to run graph:\n  ${graph.dump()}  ")

                val factory = object : IThreadWorkerFactory<ITask> {
                    override fun createWorkers(nodes: Collection<ITask>)
                            = nodes.map { TaskWorker(listOf(it), args.dryRun, messages) }
                }

                val executor = DynamicGraphExecutor(graph, factory)
                val thisResult = executor.run()
                if (thisResult != 0) {
                    log(2, "Marking project ${project.name} as failed")
                    failedProjects.add(project.name)
                }
                if (result == 0) {
                    result = thisResult
                }
            }
        }
        return RunTargetResult(result, messages)
    }

    /**
     * If the user wants to run a single task on a single project (e.g. "kobalt:assemble"), we need to
     * see if that project depends on others and if it does, invoke these tasks on all of them. This
     * function returns all these task names (including dependent).
     */
    private fun calculateDependentTaskNames(taskNames: List<String>, projects: List<Project>): List<String> {
        val projectMap = hashMapOf<String, Project>().apply {
            projects.forEach { put(it.name, it)}
        }
        val result = ArrayList(taskNames)
        val toProcess = ArrayList(taskNames)
        val newToProcess = arrayListOf<String>()
        val seen = hashSetOf<String>()
        var stop = false
        while (! stop) {
            toProcess.forEach { taskName ->
                val ti = TaskInfo(taskName)
                projectMap[ti.project]?.let { project ->
                    project.projectExtra.dependsOn.forEach { dp ->
                        val newTask = TaskInfo(dp.projectName, ti.taskName).id
                        result.add(newTask)
                        if (! seen.contains(newTask)) {
                            newToProcess.add(newTask)
                            seen.add(newTask)
                        }
                    }
                }
            }
            stop = newToProcess.isEmpty()
            toProcess.clear()
            toProcess.addAll(newToProcess)
            newToProcess.clear()
        }

        return result
    }

    val LOG_LEVEL = 3

    /**
     * Create a graph representing the tasks and their dependencies. That graph will then be run
     * in topological order.
     *
     * @taskNames is the list of tasks requested by the user. @nodeMap maps these tasks to the nodes
     * we'll be adding to the graph while @toName extracts the name of a node.
     */
    @VisibleForTesting
    fun <T> createGraph2(projectName: String, taskNames: List<String>, nodeMap: Multimap<String, T>,
            dependsOn: Multimap<String, String>,
            reverseDependsOn: Multimap<String, String>,
            runBefore: Multimap<String, String>,
            runAfter: Multimap<String, String>,
            alwaysRunAfter: Multimap<String, String>,
            toName: (T) -> String,
            accept: (T) -> Boolean):
            DynamicGraph<T> {

        val result = DynamicGraph<T>()
        val newToProcess = arrayListOf<T>()
        val seen = hashSetOf<String>()

        //
        // Reverse the always map so that tasks can be looked up.
        //
        val always = ArrayListMultimap.create<String, String>().apply {
            alwaysRunAfter.keySet().forEach { k ->
                alwaysRunAfter[k].forEach { v ->
                    put(v, k)
                }
            }
        }

        //
        // Turn the task names into the more useful TaskInfo and do some sanity checking on the way
        //
        val taskInfos = taskNames.map { TaskInfo(it) }.filter {
            if (!nodeMap.keys().contains(it.taskName)) {
                throw KobaltException("Unknown task: $it")
            }
            it.matches(projectName)
        }

        // The nodes we need to process, initialized with the set of tasks requested by the user.
        // As we run the graph and discover dependencies, new nodes get added to @param[newToProcess]. At
        // the end of the loop, @param[toProcess] is cleared and all the new nodes get added to it. Then we loop.
        val toProcess = ArrayList(taskInfos)

        while (toProcess.size > 0) {

            /**
             * Add an edge from @param from to all its tasks.
             */
            fun addEdge(result: DynamicGraph<T>, from: String, to: String, newToProcess: ArrayList<T>, text: String) {
                val froms = nodeMap[from]
                froms.forEach { f: T ->
                    nodeMap[to].forEach { t: T ->
                        val tn = toName(t)
                        log(LOG_LEVEL, "                                  Adding edge ($text) $f -> $t")
                        result.addEdge(f, t)
                        newToProcess.add(t)
                    }
                }
            }

            /**
             * Whenever a task is added to the graph, we also add its alwaysRunAfter tasks.
             */
            fun processAlways(always: Multimap<String, String>, node: T) {
                log(LOG_LEVEL, "      Processing always for $node")
                always[toName(node)]?.let { to: Collection<String> ->
                    to.forEach { t ->
                        nodeMap[t].forEach { from ->
                            log(LOG_LEVEL, "                                  Adding always edge $from -> $node")
                            result.addEdge(from, node)
                        }
                    }
                    log(LOG_LEVEL, "        ... done processing always for $node")
                }
            }

            log(LOG_LEVEL, "  Current batch to process: $toProcess")

            //
            // Move dependsOn + reverseDependsOn in one multimap called allDepends
            //
            val allDependsOn = ArrayListMultimap.create<String, String>()
            dependsOn.keySet().forEach { key ->
                dependsOn[key].forEach { value ->
                    allDependsOn.put(key, value)
                }
            }
            reverseDependsOn.keySet().forEach { key ->
                reverseDependsOn[key].forEach { value ->
                    allDependsOn.put(value, key)
                }
            }

            //
            // Process each node one by one
            //
            toProcess.forEach { taskInfo ->
                val taskName = taskInfo.taskName
                log(LOG_LEVEL, "    ***** Current node: $taskName")
                nodeMap[taskName].forEach {
                    result.addNode(it)
                    processAlways(always, it)
                }

                //
                // dependsOn and reverseDependsOn are considered for all tasks, explicit and implicit
                //
                allDependsOn[taskName].forEach { to ->
                    addEdge(result, taskName, to, newToProcess, "dependsOn")
                }

                //
                // runBefore and runAfter (task ordering) are only considered for explicit tasks (tasks that were
                // explicitly requested by the user)
                //
                runBefore[taskName].forEach { from ->
                    if (taskNames.contains(from)) {
                        addEdge(result, from, taskName, newToProcess, "runBefore")
                    }
                }
                runAfter[taskName].forEach { to ->
                    if (taskNames.contains(to)) {
                        addEdge(result, taskName, to, newToProcess, "runAfter")
                    }
                }
                seen.add(taskName)
            }

            newToProcess.forEach { processAlways(always, it) }

            toProcess.clear()
            toProcess.addAll(newToProcess.filter { ! seen.contains(toName(it))}.map { TaskInfo(toName(it)) })
            newToProcess.clear()
        }
        return result
    }

    /////
    // Manage the tasks
    //

    // Both @Task and @IncrementalTask get stored as a TaskAnnotation so they can be treated uniformly.
    // They only differ in the way they are invoked (see below)
    private val taskAnnotations = arrayListOf<TaskAnnotation>()

    class TaskAnnotation(val method: Method, val plugin: IPlugin, val name: String, val description: String,
            val group: String,
            val dependsOn: Array<String>, val reverseDependsOn: Array<String>,
            val runBefore: Array<String>, val runAfter: Array<String>,
            val alwaysRunAfter: Array<String>,
            val callable: (Project) -> TaskResult) {
        override fun toString() = "[TaskAnnotation $name]"
    }

    /**
     * Invoking a @Task means simply calling the method and returning its returned TaskResult.
     */
    fun toTaskAnnotation(method: Method, plugin: IPlugin, ta: Task)
            = TaskAnnotation(method, plugin, ta.name, ta.description, ta.group, ta.dependsOn, ta.reverseDependsOn,
                ta.runBefore, ta.runAfter, ta.alwaysRunAfter,
            { project ->
                method.invoke(plugin, project) as TaskResult
            })

    /**
     * Invoking an @IncrementalTask means invoking the method and then deciding what to do based on the content
     * of the returned IncrementalTaskInfo.
     */
    fun toTaskAnnotation(method: Method, plugin: IPlugin, ta: IncrementalTask)
            = TaskAnnotation(method, plugin, ta.name, ta.description, ta.group, ta.dependsOn, ta.reverseDependsOn,
            ta.runBefore, ta.runAfter, ta.alwaysRunAfter,
            incrementalManagerFactory.create().toIncrementalTaskClosure(ta.name, { project ->
                method.invoke(plugin, project) as IncrementalTaskInfo
            }, Variant()))

    /** Tasks annotated with @Task or @IncrementalTask */
    val annotationTasks = arrayListOf<PluginTask>()

    /** Tasks provided by ITaskContributors */
    val dynamicTasks = arrayListOf<DynamicTask>()

    fun addAnnotationTask(plugin: IPlugin, method: Method, annotation: Task) =
        taskAnnotations.add(toTaskAnnotation(method, plugin, annotation))

    fun addIncrementalTask(plugin: IPlugin, method: Method, annotation: IncrementalTask) =
        taskAnnotations.add(toTaskAnnotation(method, plugin, annotation))

    /**
     * Turn all the static and dynamic tasks into plug-in tasks, which are then suitable to be executed.
     */
    fun computePluginTasks(projects: List<Project>) {
        installAnnotationTasks(projects)
        installDynamicTasks(projects)
    }

    private fun installDynamicTasks(projects: List<Project>) {
        dynamicTasks.forEach { task ->
            projects.filter { task.plugin.accept(it) }.forEach { project ->
                addTask(task.plugin, project, task.name, task.doc, task.group,
                        task.dependsOn, task.reverseDependsOn, task.runBefore, task.runAfter, task.alwaysRunAfter,
                        task.closure)
            }
        }
    }

    private fun installAnnotationTasks(projects: List<Project>) {
        taskAnnotations.forEach { staticTask ->
            val method = staticTask.method

            val methodName = method.declaringClass.toString() + "." + method.name
            log(3, "    Found task:${staticTask.name} method: $methodName")

            val plugin = staticTask.plugin
            projects.filter { plugin.accept(it) }.forEach { project ->
                addAnnotationTask(plugin, project, staticTask, staticTask.callable)
            }
        }
    }

    private fun addAnnotationTask(plugin: IPlugin, project: Project, annotation: TaskAnnotation,
            task: (Project) -> TaskResult) {
        addTask(plugin, project, annotation.name, annotation.description, annotation.group,
                annotation.dependsOn.toList(), annotation.reverseDependsOn.toList(),
                annotation.runBefore.toList(), annotation.runAfter.toList(),
                annotation.alwaysRunAfter.toList(), task)
    }

    fun addTask(plugin: IPlugin, project: Project, name: String, description: String = "", group: String,
            dependsOn: List<String> = listOf<String>(),
            reverseDependsOn: List<String> = listOf<String>(),
            runBefore: List<String> = listOf<String>(),
            runAfter: List<String> = listOf<String>(),
            alwaysRunAfter: List<String> = listOf<String>(),
            task: (Project) -> TaskResult) {
        annotationTasks.add(
                object : BasePluginTask(plugin, name, description, group, project) {
                    override fun call(): TaskResult2<ITask> {
                        val taskResult = task(project)
                        return TaskResult2(taskResult.success, taskResult.errorMessage, this)
                    }
                })
        dependsOn.forEach { dependsOn(it, name) }
        reverseDependsOn.forEach { reverseDependsOn(it, name) }
        runBefore.forEach { runBefore(it, name) }
        runAfter.forEach { runAfter(it, name) }
        alwaysRunAfter.forEach { alwaysRunAfter(it, name) }
    }

    /**
     * Invoked by the server whenever it's done processing a command so the state can be reset for the next command.
     */
    fun cleanUp() {
        annotationTasks.clear()
        dynamicTasks.clear()
        taskAnnotations.clear()
    }

    //
    // Manage the tasks
    /////
}

class TaskWorker(val tasks: List<ITask>, val dryRun: Boolean, val messages: MutableList<String>)
        : IWorker<ITask> {

    override fun call() : TaskResult2<ITask> {
        if (tasks.size > 0) {
            tasks[0].let {
                log(1, AsciiArt.taskColor(AsciiArt.horizontalSingleLine + " ${it.project.name}:${it.name}"))
            }
        }
        var success = true
        val errorMessages = arrayListOf<String>()
        tasks.forEach {
            val name = it.project.name + ":" + it.name
            val time = benchmarkMillis {
                val tr = if (dryRun) TaskResult() else it.call()
                success = success and tr.success
                if (tr.errorMessage != null) errorMessages.add(tr.errorMessage)
            }
            messages.add("$name: $time ms")
        }
        return TaskResult2(success, errorMessages.joinToString("\n"), tasks[0])
    }

//    override val timeOut : Long = 10000

    override val priority: Int = 0
}
