package com.beust.kobalt.api

import com.beust.kobalt.BasePluginTask
import com.beust.kobalt.Plugins
import com.beust.kobalt.internal.TaskManager
import com.beust.kobalt.internal.TaskResult
import com.beust.kobalt.internal.TaskResult2
import java.util.ArrayList
import java.util.concurrent.Callable
import kotlin.properties.Delegates

abstract public class BasePlugin : Plugin {
    override val tasks: ArrayList<PluginTask> = arrayListOf()
    override var taskManager : TaskManager by Delegates.notNull()
    override var methodTasks = arrayListOf<Plugin.MethodTask>()
    override fun accept(project: Project) = true
    var plugins : Plugins by Delegates.notNull()

    fun addSyntheticTask(name: String, project: Project, task: (Project) -> TaskResult) {
        val task = object: PluginTask() {
            override val doc = "A synthetic task"
            override val name = name
            override val plugin = this@BasePlugin
            override val project = project

            override fun call(): TaskResult2<PluginTask>? {
                val taskResult = task(project)
                return TaskResult2(taskResult.success, this)
            }
        }
        tasks.add(task)
    }

}
