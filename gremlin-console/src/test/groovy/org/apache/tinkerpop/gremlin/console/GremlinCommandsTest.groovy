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

import org.apache.groovy.groovysh.jline.GroovyEngine
import org.apache.tinkerpop.gremlin.jsr223.GremlinPlugin
import org.jline.shell.CommandSession
import org.junit.After
import org.junit.Before
import org.junit.Test

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertNotNull
import static org.junit.Assert.assertTrue

/**
 * The Gremlin commands, run without a terminal or a Gremlin Server.
 */
class GremlinCommandsTest {

    // in-memory in tests (see InMemoryPreferencesFactory)
    private static final java.util.prefs.Preferences STORE =
            java.util.prefs.Preferences.userRoot().node("/org/apache/tinkerpop/gremlin/console")

    private ByteArrayOutputStream out
    private CommandSession session
    private Mediator mediator
    private GroovyEngine engine

    @Before
    void setUp() {
        out = new ByteArrayOutputStream()
        session = new CommandSession(null, new ByteArrayInputStream(new byte[0]), new PrintStream(out, true),
                new PrintStream(new ByteArrayOutputStream(), true))
        mediator = new Mediator(null)
        engine = new GroovyEngine()
    }

    @After
    void tearDown() {
        STORE.clear()
    }

    private static Object run(final org.jline.shell.Command command, final CommandSession session, final String... args) {
        return command.execute(session, args)
    }

    @Test
    void shouldNameCommandsWithTheirShortcuts() {
        final def commands = GremlinCommands.create(mediator, engine).commands()
        assertEquals(["/plugin", "/set", "/install", "/uninstall", "/cls"], commands.collect { it.name() })
        assertEquals(["/pin"], commands.find { it.name() == "/plugin" }.aliases())
        assertEquals(["/C"], commands.find { it.name() == "/cls" }.aliases())
    }

    @Test
    void shouldRequirePluginNameForUse() {
        assertTrue(((String) run(new GremlinCommands.PluginCommand(mediator), session, "use")).contains("Specify the name"))
    }

    @Test
    void shouldReportUnknownPluginForUse() {
        assertTrue(((String) run(new GremlinCommands.PluginCommand(mediator), session, "use", "no.such.plugin")).contains("could not be found"))
    }

    @Test
    void shouldRequirePluginNameForUnuse() {
        assertTrue(((String) run(new GremlinCommands.PluginCommand(mediator), session, "unuse")).contains("Specify the name"))
    }

    @Test
    void shouldReportUnknownPluginForUnuse() {
        assertTrue(((String) run(new GremlinCommands.PluginCommand(mediator), session, "unuse", "no.such.plugin")).contains("could not be found"))
    }

    @Test
    void shouldDeactivateAnAvailablePlugin() {
        def plugin = ServiceLoader.load(GremlinPlugin, engine.getClassLoader()).iterator().next()
        mediator.availablePlugins.put(plugin.class.name, new PluggedIn((GremlinPlugin) plugin, engine, false))
        def result = (String) run(new GremlinCommands.PluginCommand(mediator), session, "unuse", plugin.name)
        assertTrue(result, result.contains("deactivated"))
    }

    @Test
    void shouldListPlugins() {
        def plugin = ServiceLoader.load(GremlinPlugin, engine.getClassLoader()).iterator().next()
        mediator.availablePlugins.put(plugin.class.name, new PluggedIn((GremlinPlugin) plugin, engine, true))
        assertEquals([plugin.name + "[active]"], run(new GremlinCommands.PluginCommand(mediator), session, "list"))
    }

    @Test
    void shouldSetAndRetrieveKnownPreference() {
        run(new GremlinCommands.SetCommand(), session, Preferences.PREFERENCE_ITERATION_MAX, "42")
        assertEquals("42", STORE.get(Preferences.PREFERENCE_ITERATION_MAX, null))
    }

    @Test
    void shouldSetBooleanPreferenceToTrueWhenValueOmitted() {
        run(new GremlinCommands.SetCommand(), session, Preferences.PREF_WARNINGS)
        assertEquals("true", STORE.get(Preferences.PREF_WARNINGS, null))
    }

    @Test
    void shouldListPreferencesWhenNoArguments() {
        final def set = new GremlinCommands.SetCommand()
        run(set, session)
        assertTrue(out.toString(), out.toString().contains("No preferences are set"))

        run(set, session, Preferences.PREF_RESULT_PROMPT, "==>")
        out.reset()
        run(set, session)
        final String output = out.toString()
        assertTrue("expected a preferences listing but got: ${output}".toString(),
                output.contains("Preferences:") && output.contains(Preferences.PREF_RESULT_PROMPT))
    }

    @Test(expected = IllegalArgumentException)
    void shouldRejectTooManyArgumentsToSet() {
        run(new GremlinCommands.SetCommand(), session, "a", "b", "c")
    }

    @Test(expected = IllegalArgumentException)
    void shouldRejectMissingGroup() {
        run(new GremlinCommands.InstallCommand(mediator, engine), session)
    }

    @Test(expected = IllegalArgumentException)
    void shouldRejectMissingModule() {
        run(new GremlinCommands.InstallCommand(mediator, engine), session, "org.example")
    }

    @Test(expected = IllegalArgumentException)
    void shouldRejectMissingVersion() {
        run(new GremlinCommands.InstallCommand(mediator, engine), session, "org.example", "some-artifact")
    }

    @Test
    void shouldReturnErrorMessageWhenArtifactCannotBeResolved() {
        // needs the network
        def result = run(new GremlinCommands.InstallCommand(mediator, engine), session,
                "com.tinkerpop.nonexistent.fake", "no-such-artifact-xyz", "9.9.9")
        assertNotNull(result)
        assertTrue(result instanceof String)
        final String message = (String) result
        assertTrue(message, message.contains("unresolved dependency"))
        assertTrue(message, message.contains("no-such-artifact-xyz"))
    }

    @Test
    void shouldRegisterPluginsTheEngineCanFind() {
        // the plugins on the test classpath stand in for ones a successful install just loaded
        new GremlinCommands.InstallCommand(mediator, engine).registerNewPlugins()
        assertTrue(mediator.availablePlugins.size() > 0)
    }

    @Test
    void shouldReportMissingModuleArgument() {
        assertTrue(((String) run(new GremlinCommands.UninstallCommand(), session)).contains("Specify the name"))
    }

    @Test
    void shouldReportUnknownModule() {
        def result = (String) run(new GremlinCommands.UninstallCommand(), session, "no-such-module-xyz123")
        assertTrue(result.contains("no module with the name"))
    }

    @Test
    void shouldUninstallExistingModule() {
        def name = "test-uninstall-" + System.currentTimeMillis()
        def dir = new File(ConsoleFs.CONSOLE_HOME_DIR, name)
        dir.mkdirs()
        try {
            def result = (String) run(new GremlinCommands.UninstallCommand(), session, name)
            assertTrue(result.contains("Uninstalled"))
            assertTrue(!dir.exists())
        } finally {
            dir.deleteDir()
        }
    }
}
