/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.console

import groovy.grape.Grape
import org.apache.groovy.groovysh.jline.GroovyEngine
import org.apache.tinkerpop.gremlin.groovy.util.Artifact
import org.apache.tinkerpop.gremlin.groovy.util.DependencyGrabber
import org.apache.tinkerpop.gremlin.jsr223.GremlinPlugin
import org.jline.shell.Command
import org.jline.shell.CommandGroup
import org.jline.shell.CommandSession
import org.jline.shell.impl.AbstractCommand
import org.jline.shell.impl.SimpleCommandGroup

/**
 * The commands the Gremlin Console adds to groovysh. groovysh names commands {@code /name} and also accepts a typed
 * {@code :name} for any command or alias that exists.
 */
class GremlinCommands {

    static CommandGroup create(final Mediator mediator, final GroovyEngine engine) {
        return new SimpleCommandGroup("gremlin", [
                new PluginCommand(mediator),
                new SetCommand(),
                new InstallCommand(mediator, engine),
                new UninstallCommand(),
                new ClsCommand(mediator)] as Command[])
    }

    static class PluginCommand extends AbstractCommand {
        private final Mediator mediator

        PluginCommand(final Mediator mediator) {
            super("/plugin", "/pin")
            this.mediator = mediator
        }

        @Override
        String description() { "Manage plugins for the Console" }

        @Override
        Object execute(final CommandSession session, final String[] args) {
            final List<String> arguments = args as List
            final String function = arguments.isEmpty() ? "use" : arguments[0]
            final String pluginName = arguments.size() == 2 ? arguments[1] : null
            switch (function) {
                case "list":
                    return mediator.availablePlugins.collect { k, v -> v.plugin.name + (v.activated ? "[active]" : "") }
                case "use":
                    if (!pluginName) return "Specify the name of the plugin to use"
                    final PluggedIn plugin = find(pluginName)
                    if (!plugin) return notFound(pluginName)
                    mediator.showShellEvaluationOutput(false)
                    plugin.activate()
                    mediator.showShellEvaluationOutput(true)
                    if (mediator.writePluginState())
                        return "$pluginName activated"
                    else
                        return "$pluginName activated but " + ConsoleFs.PLUGIN_CONFIG_FILE + " is readonly - the plugin list will remain unchanged on console restart"
                case "unuse":
                    if (!pluginName) return "Specify the name of the plugin to deactivate"
                    final PluggedIn plugin = find(pluginName)
                    if (!plugin) return notFound(pluginName)
                    plugin.deactivate()
                    if (mediator.writePluginState())
                        return "$pluginName deactivated - restart your console for this to take effect"
                    else
                        return "$pluginName deactivated but " + ConsoleFs.PLUGIN_CONFIG_FILE + " is readonly - the plugin list will remain unchanged on console restart"
                default:
                    throw new IllegalArgumentException("Unknown function name: '$function'. Valid arguments: [use, list, unuse]")
            }
        }

        private PluggedIn find(final String pluginName) {
            return mediator.availablePlugins.values().find { it.plugin.name == pluginName }
        }

        private static String notFound(final String pluginName) {
            return "$pluginName could not be found - use ':plugin list' to see available plugins"
        }
    }

    static class SetCommand extends AbstractCommand {
        private static final java.util.prefs.Preferences STORE =
                java.util.prefs.Preferences.userRoot().node("/org/apache/tinkerpop/gremlin/console")

        SetCommand() { super("/set") }

        @Override
        String description() { "Set (or list) preferences" }

        @Override
        Object execute(final CommandSession session, final String[] args) {
            if (args.length == 0) {
                final String[] keys = STORE.keys()
                if (keys.length == 0) {
                    session.out().println("No preferences are set")
                } else {
                    session.out().println("Preferences:")
                    keys.each { session.out().println("    $it=${STORE.get(it, null)}") }
                }
                return null
            }
            if (args.length > 2) throw new IllegalArgumentException("Command ':set' requires arguments: <name> [<value>]")
            STORE.put(args[0], args.length == 1 ? "true" : args[1])
            return null
        }
    }

    /**
     * Downloads a library into the {@code ext} directory and loads it, so it can be used without a restart.
     */
    static class InstallCommand extends AbstractCommand {
        private final Mediator mediator
        private final GroovyEngine engine

        InstallCommand(final Mediator mediator, final GroovyEngine engine) {
            super("/install")
            this.mediator = mediator
            this.engine = engine
        }

        @Override
        String description() { "Install a Maven library and its dependencies into the Gremlin Console" }

        @Override
        Object execute(final CommandSession session, final String[] args) {
            final String group = args.length >= 1 ? args[0] : null
            final String module = args.length >= 2 ? args[1] : null
            final String version = args.length >= 3 ? args[2] : null
            if (!group) throw new IllegalArgumentException("Group cannot be null or empty")
            if (!module) throw new IllegalArgumentException("Module cannot be null or empty")
            if (!version) throw new IllegalArgumentException("Version cannot be null or empty")

            final Artifact artifact = new Artifact(group, module, version)
            try {
                final DependencyGrabber grabber = new DependencyGrabber(engine.getClassLoader(), ConsoleFs.CONSOLE_HOME_DIR)
                grabber.copyDependenciesToPath(artifact)
                Grape.grab(grabber.makeDepsMap(artifact))
                final Set<String> pluginsThatNeedRestart = registerNewPlugins()
                return "Loaded: " + (args as List) + (pluginsThatNeedRestart.isEmpty() ? "" : " - restart the console to use $pluginsThatNeedRestart")
            } catch (Exception ex) {
                return ex.message
            }
        }

        /**
         * Registers plugins the engine can now find and returns the names of those that need a restart.
         */
        Set<String> registerNewPlugins() {
            final Set<String> pluginsThatNeedRestart = [] as Set
            ServiceLoader.load(GremlinPlugin, engine.getClassLoader()).forEach { plugin ->
                if (!mediator.availablePlugins.containsKey(plugin.class.name)) {
                    mediator.availablePlugins.put(plugin.class.name, new PluggedIn(plugin, engine, false))
                    if (plugin.requireRestart())
                        pluginsThatNeedRestart << plugin.name
                }
            }
            return pluginsThatNeedRestart
        }
    }

    static class UninstallCommand extends AbstractCommand {
        UninstallCommand() { super("/uninstall") }

        @Override
        String description() { "Uninstall a Maven library and its dependencies from the Gremlin Console" }

        @Override
        Object execute(final CommandSession session, final String[] args) {
            final String module = args.length >= 1 ? args[0] : null
            if (!module) return "Specify the name of the module containing plugins to uninstall"
            final File extClassPath = new File(ConsoleFs.CONSOLE_HOME_DIR, module)
            if (!extClassPath.exists()) return "There is no module with the name $module to remove - $extClassPath"
            extClassPath.deleteDir()
            return "Uninstalled $module - restart the console for removal to take effect"
        }
    }

    static class ClsCommand extends AbstractCommand {
        private final Mediator mediator

        ClsCommand(final Mediator mediator) {
            super("/cls", "/C")
            this.mediator = mediator
        }

        @Override
        String description() { "Clear the screen." }

        @Override
        Object execute(final CommandSession session, final String[] args) {
            mediator.clear()
            return null
        }
    }
}
