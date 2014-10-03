/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.hotspot;

import static com.oracle.graal.compiler.GraalDebugConfig.*;

import java.io.*;
import java.util.*;

import com.oracle.graal.compiler.common.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.internal.*;

/**
 * Facility for printing the {@linkplain KeyRegistry#getDebugValues() values} collected across all
 * {@link DebugValueMap#getTopLevelMaps() threads}.
 */
public class DebugValuesPrinter {

    public void printDebugValues() throws GraalInternalError {
        TTY.println();
        TTY.println("<DebugValues>");
        List<DebugValueMap> topLevelMaps = DebugValueMap.getTopLevelMaps();
        List<DebugValue> debugValues = KeyRegistry.getDebugValues();
        if (debugValues.size() > 0) {
            try {
                ArrayList<DebugValue> sortedValues = new ArrayList<>(debugValues);
                Collections.sort(sortedValues);

                String summary = DebugValueSummary.getValue();
                if (summary == null) {
                    summary = "Complete";
                }
                switch (summary) {
                    case "Name":
                        printSummary(topLevelMaps, sortedValues);
                        break;
                    case "Partial": {
                        DebugValueMap globalMap = new DebugValueMap("Global");
                        for (DebugValueMap map : topLevelMaps) {
                            flattenChildren(map, globalMap);
                        }
                        globalMap.normalize();
                        printMap(new DebugValueScope(null, globalMap), sortedValues);
                        break;
                    }
                    case "Complete": {
                        DebugValueMap globalMap = new DebugValueMap("Global");
                        for (DebugValueMap map : topLevelMaps) {
                            globalMap.addChild(map);
                        }
                        globalMap.group();
                        globalMap.normalize();
                        printMap(new DebugValueScope(null, globalMap), sortedValues);
                        break;
                    }
                    case "Thread":
                        for (DebugValueMap map : topLevelMaps) {
                            TTY.println("Showing the results for thread: " + map.getName());
                            map.group();
                            map.normalize();
                            printMap(new DebugValueScope(null, map), sortedValues);
                        }
                        break;
                    default:
                        throw new GraalInternalError("Unknown summary type: %s", summary);
                }
                for (DebugValueMap topLevelMap : topLevelMaps) {
                    topLevelMap.reset();
                }
            } catch (Throwable e) {
                // Don't want this to change the exit status of the VM
                PrintStream err = System.err;
                err.println("Error while printing debug values:");
                e.printStackTrace();
            }
        }
        TTY.println("</DebugValues>");
    }

    private void flattenChildren(DebugValueMap map, DebugValueMap globalMap) {
        globalMap.addChild(map);
        for (DebugValueMap child : map.getChildren()) {
            flattenChildren(child, globalMap);
        }
        map.clearChildren();
    }

    private void printSummary(List<DebugValueMap> topLevelMaps, List<DebugValue> debugValues) {
        DebugValueMap result = new DebugValueMap("Summary");
        for (int i = debugValues.size() - 1; i >= 0; i--) {
            DebugValue debugValue = debugValues.get(i);
            int index = debugValue.getIndex();
            long total = collectTotal(topLevelMaps, index);
            result.setCurrentValue(index, total);
        }
        printMap(new DebugValueScope(null, result), debugValues);
    }

    private long collectTotal(List<DebugValueMap> maps, int index) {
        long total = 0;
        for (int i = 0; i < maps.size(); i++) {
            DebugValueMap map = maps.get(i);
            total += map.getCurrentValue(index);
            total += collectTotal(map.getChildren(), index);
        }
        return total;
    }

    /**
     * Tracks the scope when printing a {@link DebugValueMap}, allowing "empty" scopes to be
     * omitted. An empty scope is one in which there are no (nested) non-zero debug values.
     */
    static class DebugValueScope {

        final DebugValueScope parent;
        final int level;
        final DebugValueMap map;
        private boolean printed;

        public DebugValueScope(DebugValueScope parent, DebugValueMap map) {
            this.parent = parent;
            this.map = map;
            this.level = parent == null ? 0 : parent.level + 1;
        }

        public void print() {
            if (!printed) {
                printed = true;
                if (parent != null) {
                    parent.print();
                }
                printIndent(level);
                TTY.println("%s", map.getName());
            }
        }
    }

    private void printMap(DebugValueScope scope, List<DebugValue> debugValues) {

        for (DebugValue value : debugValues) {
            long l = scope.map.getCurrentValue(value.getIndex());
            if (l != 0 || !SuppressZeroDebugValues.getValue()) {
                scope.print();
                printIndent(scope.level + 1);
                TTY.println(value.getName() + "=" + value.toString(l));
            }
        }

        for (DebugValueMap child : scope.map.getChildren()) {
            printMap(new DebugValueScope(scope, child), debugValues);
        }
    }

    private static void printIndent(int level) {
        for (int i = 0; i < level; ++i) {
            TTY.print("    ");
        }
        TTY.print("|-> ");
    }
}
