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
package org.apache.tinkerpop.gremlin.groovy.jsr223;

import org.apache.tinkerpop.shaded.jackson.databind.JsonNode;
import org.apache.tinkerpop.shaded.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Compiles every Groovy translation of the Gherkin feature traversals with the {@link GremlinGroovyScriptEngine}.
 * The translations (gremlin-test's {@code translations.json}) are otherwise only compared as text, so a Groovy
 * grammar or literal change that makes the generated Groovy invalid would go unnoticed.
 */
public class GroovyTranslationCompileTest {

    private static final String TRANSLATIONS = "/org/apache/tinkerpop/gremlin/language/translator/translations.json";

    @Test
    public void shouldCompileEveryGroovyTranslation() throws Exception {
        final JsonNode scenarios;
        try (InputStream in = GroovyTranslationCompileTest.class.getResourceAsStream(TRANSLATIONS)) {
            assertNotNull("translations.json not found on the test classpath", in);
            scenarios = new ObjectMapper().readTree(in);
        }

        // reset() between batches keeps the class cache from holding thousands of script classes
        final GremlinGroovyScriptEngine engine = new GremlinGroovyScriptEngine();
        final List<String> failures = new ArrayList<>();
        int compiled = 0;
        for (JsonNode scenario : scenarios) {
            for (JsonNode traversal : scenario.get("traversals")) {
                final JsonNode groovy = traversal.get("groovy");
                if (groovy == null || groovy.isNull()) continue;
                try {
                    engine.compile(groovy.asText());
                    compiled++;
                } catch (Exception ex) {
                    final Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    failures.add(scenario.get("scenario").asText() + ": " + groovy.asText() + "\n    " +
                            String.valueOf(cause.getMessage()).replace('\n', ' '));
                }
                if (compiled % 500 == 0) engine.reset();
            }
        }

        assertTrue("expected thousands of translations but compiled " + compiled, compiled > 1000);

        final Set<String> failedScenarios = new TreeSet<>();
        failures.forEach(f -> failedScenarios.add(f.substring(0, f.indexOf(':'))));
        assertEquals("the set of Groovy translations that do not compile changed (if a known translator bug was " +
                        "fixed, remove it from KNOWN_BROKEN):\n" + String.join("\n", failures),
                KNOWN_BROKEN, failedScenarios);
    }

    /**
     * Known translator bug, independent of the Groovy version: the Groovy translator copies a map key written as
     * {@code T.label:} unchanged, but Groovy only accepts a non-identifier key in parentheses ({@code (T.label):}).
     */
    private static final Set<String> KNOWN_BROKEN = new TreeSet<>(Arrays.asList(
            "g_V_mergeE_inlineXemptyX_optionXonCreate_nullX",
            "g_V_mergeE_inlineXemptyX_two_exist",
            "g_mergeE_inline_with_outVinV_options_map",
            "g_mergeE_inline_with_outVinV_options_select",
            "g_injectXlabel_person_name_marko_label_person_name_stephenX_mergeVXidentityX",
            "g_injectXlabel_person_name_marko_label_person_name_stephenX_mergeV",
            "g_injectXlist1_list2_list3X_fold_asXmX_mergeVXselectXmX_limitXlocal_1X_unfoldX_optionXonCreate_selectXmX_rangeXlocal_1_2X_unfoldX_optionXonMatch_selectXmX_tailXlocalX_unfoldX_to_create",
            "g_injectXlist1_list2_list3X_fold_asXmX_mergeVXselectXmX_limitXlocal_1X_unfoldX_optionXonCreate_selectXmX_rangeXlocal_1_2X_unfoldX_optionXonMatch_selectXmX_tailXlocalX_unfoldX_to_match"));
}
