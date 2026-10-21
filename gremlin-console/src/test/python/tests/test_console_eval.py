'''
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
'''

"""How typed Groovy is evaluated: state kept between lines, imports, multi-line input, errors and Ctrl-C."""

import time

import pytest

from .console_helpers import (PROMPT, STACK_TRACE_PROMPT, answer_stack_trace_prompt, exit_console, load_modern,
                              results, send, writes_preferences)

CONTINUATION_1 = "......1> "
CONTINUATION_2 = "......2> "


def test_variables_persist_between_lines(console):
    child, _ = console.start("-C")
    assert results(child, "x = 1") == ["==>1"]
    assert results(child, "x + 1") == ["==>2"]
    assert results(child, "x = x * 10") == ["==>10"]
    assert results(child, "x") == ["==>10"]
    assert exit_console(child) == 0


def test_def_variables_are_not_kept_between_lines(console):
    # without groovysh's interpreterMode a "def" variable is local to the line that declared it
    child, _ = console.start("-C")
    send(child, "def y = 1", prompt=PROMPT)
    child.sendline("y")
    child.expect_exact("No such property: y")
    answer_stack_trace_prompt(child)
    assert exit_console(child) == 0


def test_method_defined_on_one_line_can_be_called_on_the_next(console):
    child, _ = console.start("-C")
    # a declaration on its own evaluates to true
    assert results(child, "int add(int a, int b) { a + b }") == ["==>true"]
    assert results(child, "add(1, 2)") == ["==>3"]
    assert exit_console(child) == 0


def test_class_defined_on_one_line_can_be_used_on_the_next(console):
    child, _ = console.start("-C")
    assert results(child, "class Foo { int a }") == ["==>true"]
    assert results(child, "new Foo(a: 1).a") == ["==>1"]
    assert exit_console(child) == 0


def test_user_import_is_kept_between_lines(console):
    child, _ = console.start("-C")
    # the import is a console command that prints the list of imports added so far
    assert results(child, "import java.util.concurrent.TimeUnit") == ["==>java.util.concurrent.TimeUnit"]
    assert results(child, "TimeUnit.SECONDS") == ["==>SECONDS"]
    assert results(child, "TimeUnit.MINUTES.toSeconds(2)") == ["==>120"]
    assert exit_console(child) == 0


def test_default_gremlin_imports_need_no_import_statement(console):
    child, _ = console.start("-C")
    # TinkerFactory comes from the tinkergraph plugin, traversal() from the core imports
    load_modern(child)
    # static enum import (Order.desc)
    assert results(child, "g.V().hasLabel('person').order().by('age', desc).values('name')") == \
        ["==>peter", "==>josh", "==>marko", "==>vadas"]
    # static method import (P.gt)
    assert results(child, "g.V().has('age', gt(30)).values('name').order()") == ["==>josh", "==>peter"]
    # anonymous traversal class (__)
    assert results(child, "g.V(1).where(__.out('created')).values('name')") == ["==>marko"]
    # class imports (T, Direction) and static enum import (Scope.local)
    assert results(child, "g.V().has(T.label, 'software').count()") == ["==>2"]
    assert results(child, "Direction.OUT") == ["==>OUT"]
    assert results(child, "g.V().hasLabel('person').values('age').fold().count(local)") == ["==>4"]
    assert exit_console(child) == 0


def test_plugin_imports_are_available(console):
    child, _ = console.start("-C")
    # tinkerpop.remote plugin
    assert results(child, "Cluster.class.name") == ["==>org.apache.tinkerpop.gremlin.driver.Cluster"]
    assert results(child, "DriverRemoteConnection.class.name") == \
        ["==>org.apache.tinkerpop.gremlin.driver.remote.DriverRemoteConnection"]
    # tinkerpop.tinkergraph plugin
    assert results(child, "TinkerGraph.open()") == ["==>tinkermemorygraph[vertices:0 edges:0]"]
    assert exit_console(child) == 0


def test_utilities_plugin_script_defines_describe_graph(console):
    child, _ = console.start("-C")
    output = "\n".join(send(child, "describeGraph(TinkerGraph)"))
    assert "IMPLEMENTATION - org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph" in output
    assert "TINKERPOP TEST SUITE" in output
    assert exit_console(child) == 0


def test_continuation_prompt_numbers_each_extra_line(console):
    child, _ = console.start("-C")
    # the continuation prompt is the line number padded with dots to the width of "gremlin>"
    send(child, "[1,2,", prompt=CONTINUATION_1)
    send(child, "3,", prompt=CONTINUATION_2)
    assert results(child, "4]") == ["==>1", "==>2", "==>3", "==>4"]
    assert exit_console(child) == 0


def test_multiline_closure_and_map(console):
    child, _ = console.start("-C")
    send(child, "add3 = { a ->", prompt=CONTINUATION_1)
    send(child, "  a + 3", prompt=CONTINUATION_2)
    send(child, "}")
    assert results(child, "add3(1)") == ["==>4"]

    send(child, "m = [a: 1,", prompt=CONTINUATION_1)
    assert results(child, "b: 2]") == ["==>a=1", "==>b=2"]
    assert exit_console(child) == 0


def test_clear_discards_an_unfinished_statement(console):
    child, _ = console.start("-C")
    send(child, "[1,2,", prompt=CONTINUATION_1)
    assert send(child, ":clear") == []
    # the unfinished list is gone, so this is evaluated on its own
    assert results(child, "1+1") == ["==>2"]
    assert exit_console(child) == 0


def test_syntax_error_is_reported_without_compiler_prefix(console):
    child, _ = console.start("-C")
    child.sendline("x = )")
    child.expect_exact(STACK_TRACE_PROMPT)
    error_output = child.before
    # the "startup failed:" prefix Groovy puts on compilation errors is stripped
    assert "startup failed:" not in error_output
    assert "Type ':help' or ':h' for help." in error_output
    child.sendline("n")
    child.expect_exact(PROMPT)
    # the broken line was discarded rather than kept as an unfinished statement
    assert results(child, "1+1") == ["==>2"]
    assert exit_console(child) == 0


def test_multiline_statement_in_execute_script(console):
    # script files are fed to the console one line at a time, so statements spanning lines must still work
    # (the closure is not called "sum": that name is taken by the statically imported Operator.sum)
    script = console.write_file("multi.groovy",
                                "adder = { a, b ->\n"
                                "    a + b\n"
                                "}\n"
                                "total = [1,\n"
                                "         2,\n"
                                "         3].sum()\n"
                                "println adder(2, 3)\n"
                                "println total\n")
    completed = console.run("-e", script)
    assert completed.returncode == 0, completed.stderr
    stdout_lines = [line.strip() for line in completed.stdout.splitlines()]
    assert stdout_lines[-2:] == ["5", "6"], completed.stdout


def test_multiline_statement_in_interactive_script(console):
    script = console.write_file("multi-i.groovy", "total = [1,\n  2,\n  3].sum()\n")
    child, _ = console.start("-C", "-i", script)
    assert results(child, "total") == ["==>6"]
    assert exit_console(child) == 0


# --- Ctrl-C ---------------------------------------------------------------------------------------------------------


def _interrupt_and_recover(child):
    """Presses Ctrl-C and waits until the console is back at its prompt."""
    child.sendintr()
    child.expect_exact("Execution interrupted by ctrl+c", timeout=30)
    # an interrupted evaluation surfaces as an InterruptedException, which goes through the normal error handling
    # and its stack trace question; an interrupted result listing returns straight to the prompt
    if child.expect_exact([STACK_TRACE_PROMPT, PROMPT], timeout=30) == 0:
        child.sendline("n")
        child.expect_exact(PROMPT, timeout=30)


def test_ctrl_c_interrupts_an_endless_loop(console):
    child, _ = console.start("-C")
    child.sendline("while (true) {}")
    time.sleep(3)
    _interrupt_and_recover(child)
    assert child.isalive()
    assert exit_console(child) == 0


@pytest.mark.xfail(reason="known bug: after ctrl+c stops a loop the thread's interrupted flag is never cleared, so "
                          "the @ThreadInterrupt check fails every later evaluation with 'Execution interrupted' "
                          "until the console is restarted (Thread.sleep clears the flag itself, so it is unaffected)")
def test_console_is_usable_after_ctrl_c_interrupts_a_loop(console):
    child, _ = console.start("-C")
    child.sendline("while (true) {}")
    time.sleep(3)
    _interrupt_and_recover(child)
    child.sendline("1+1")
    assert child.expect_exact(["==>2", STACK_TRACE_PROMPT], timeout=30) == 0
    child.expect_exact(PROMPT)
    assert exit_console(child) == 0


def test_ctrl_c_interrupts_a_sleep(console):
    child, _ = console.start("-C")
    child.sendline("Thread.sleep(60000)")
    time.sleep(3)
    _interrupt_and_recover(child)
    assert results(child, "1+1") == ["==>2"]
    assert exit_console(child) == 0


@writes_preferences
def test_ctrl_c_interrupts_printing_of_an_endless_result(console):
    child, _ = console.start("-C")
    # remove the result limit so the listing would never end on its own
    assert send(child, ":set max-iteration -1") == []
    time.sleep(0.5)
    child.sendline("[hasNext: { true }, next: { 1 }] as Iterator")
    for _ in range(50):
        child.expect_exact("==>1")
    _interrupt_and_recover(child)
    # whether the next line then works depends on timing: if ctrl+c lands inside the iterator's closures the same
    # interrupted-flag bug as test_console_is_usable_after_ctrl_c_interrupts_a_loop hits, so only check survival
    assert child.isalive()
    assert exit_console(child) == 0


def test_ctrl_c_keeps_a_half_typed_line(console):
    # today ctrl+c at the prompt is ignored by the line reader, so the typed text stays and is completed by the
    # next keys typed
    child, _ = console.start("-C")
    child.send("1+")
    time.sleep(1)
    child.sendintr()
    time.sleep(1)
    child.sendline("1")
    child.expect_exact("==>2")
    child.expect_exact(PROMPT)
    assert exit_console(child) == 0


@pytest.mark.xfail(reason="known bug: ctrl+c prints 'Execution interrupted by ctrl+c' but an endless traversal keeps "
                          "running (no prompt after 90s), although the reference docs say traversals respond to it")
def test_ctrl_c_interrupts_an_endless_traversal(console):
    child, _ = console.start("-C")
    load_modern(child)
    child.sendline("g.inject(1).repeat(identity()).until(constant(false)).count()")
    time.sleep(3)
    _interrupt_and_recover(child)
    assert child.isalive()
    assert exit_console(child) == 0


def test_ctrl_c_at_an_idle_prompt_does_not_exit(console):
    child, _ = console.start("-C")
    child.sendintr()
    time.sleep(2)
    assert child.isalive()
    assert results(child, "1+1") == ["==>2"]
    assert exit_console(child) == 0


# --- other evaluation behavior --------------------------------------------------------------------------------------


def test_static_import_is_kept_between_lines(console):
    child, _ = console.start("-C")
    assert results(child, "import static java.lang.Math.max") == ["==>static java.lang.Math.max"]
    assert results(child, "max(3, 7)") == ["==>7"]
    assert exit_console(child) == 0


def test_console_commands_inside_a_script_file(console):
    script = console.write_file("commands.groovy",
                                ":plugin unuse tinkerpop.utilities\n"
                                "afterCommand = 'ran'\n")
    child, _ = console.start("-C", "-i", script)
    assert results(child, "afterCommand") == ["==>ran"]
    listed = results(child, ":plugin list")
    assert "==>tinkerpop.utilities" in listed, listed
    assert exit_console(child) == 0


def test_interactive_script_error_reports_the_line_and_stops(console):
    script = console.write_file("stops.groovy", "a = 1\nb = 1/0\nc = 3\n")
    child = console.spawn("-C", "-i", script)
    child.expect_exact("Error in %s at [2: b = 1/0] - Division by zero" % script)
    child.expect_exact(PROMPT)
    assert results(child, "a") == ["==>1"]
    # the line after the error was not run
    child.sendline("c")
    child.expect_exact("No such property: c")
    answer_stack_trace_prompt(child)
    assert exit_console(child) == 0


def test_stack_overflow_prints_a_hint(console):
    child, _ = console.start("-C")
    assert results(child, "def deep(n) { deep(n + 1) }") == ["==>true"]
    child.sendline("deep(0)")
    child.expect_exact("A StackOverflowError can indicate that the Gremlin traversal being executed is too long.")
    answer_stack_trace_prompt(child)
    assert results(child, "1+1") == ["==>2"]
    assert exit_console(child) == 0


def test_console_classes_are_reachable_through_the_class_loader(console):
    child, _ = console.start("-C")
    assert results(child, "this.class.classLoader.loadClass('org.apache.tinkerpop.gremlin.driver.Cluster').name") == \
        ["==>org.apache.tinkerpop.gremlin.driver.Cluster"]
    assert results(child, "Class.forName('org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph')"
                          ".simpleName") == ["==>TinkerGraph"]
    assert exit_console(child) == 0


def test_last_result_is_available_as_underscore(console):
    child, _ = console.start("-C")
    assert results(child, "21 * 2") == ["==>42"]
    assert results(child, "_ + 1") == ["==>43"]
    assert exit_console(child) == 0
