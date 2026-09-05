package uz.disastrouspumpkin.wdb.plugin

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.remote.RemoteConfiguration
import com.intellij.execution.remote.RemoteConfigurationType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project

/**
 * One-click debugger attach (design D5): build a transient (unsaved) Remote JVM Debug
 * configuration pointed at the local tunnel port and execute it under the Debug executor,
 * reusing the platform's real remote-debug runner. [onEnd] fires when the session terminates
 * (used to release the tunnel — design "tunnel is released when debugging ends"). EDT only.
 */
fun attachRemoteDebugger(project: Project, name: String, localPort: Int, onEnd: () -> Unit) {
    val type = ConfigurationTypeUtil.findConfigurationType(RemoteConfigurationType::class.java)
    val factory = type.configurationFactories.first()
    val settings = RunManager.getInstance(project).createConfiguration("wdb: attach $name", factory)
    (settings.configuration as RemoteConfiguration).apply {
        HOST = "localhost"
        PORT = localPort.toString()
        USE_SOCKET_TRANSPORT = true
        SERVER_MODE = false // we attach (client) to the remote JVM
    }

    val connection = project.messageBus.connect()
    connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
        override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
            if (env.runProfile === settings.configuration) {
                onEnd()
                connection.disconnect()
            }
        }
    })

    ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
}
